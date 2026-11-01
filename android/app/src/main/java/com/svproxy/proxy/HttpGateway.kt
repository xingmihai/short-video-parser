package com.svproxy.proxy

import android.util.Log
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 极简 HTTP/1.1 网关。
 *
 * 职责边界：只负责"收请求 + 回写响应"，不做任何业务判断。
 * 收到请求后通过事件交给 JS（即 index.android.bundle 里的 src/proxy.js），
 * 由 JS 决策后再回调 respondText / pipeToClient 写回。
 */
class HttpGateway {

    companion object {
        private const val TAG = "SvProxy"
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val REQUEST_WAIT_MS = 60_000L
    }

    /** 等待 JS 决策中的客户端连接 */
    private class ClientConn(
        val socket: Socket,
        val out: OutputStream,
        val latch: CountDownLatch,
    )

    private val seq = AtomicInteger(0)
    private val pending = ConcurrentHashMap<String, ClientConn>()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val ioPool = Executors.newCachedThreadPool { r ->
        Thread(r, "sv-io-${seq.incrementAndGet()}").apply { isDaemon = true }
    }

    @Volatile
    var port: Int = 0
        private set

    var onRequest: ((requestId: String, method: String, path: String, query: Map<String, String>, headers: Map<String, String>) -> Unit)? =
        null

    val isRunning: Boolean
        get() = serverSocket != null && serverSocket?.isClosed == false

    /** 启动监听，返回实际端口 */
    @Synchronized
    fun start(port: Int): Int {
        if (isRunning) return this.port
        val ss = ServerSocket(port)
        this.port = ss.localPort
        this.serverSocket = ss
        acceptThread = Thread({
            while (!ss.isClosed) {
                try {
                    val socket = ss.accept()
                    ioPool.submit { handleConnection(socket) }
                } catch (e: SocketException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "accept error", e)
                }
            }
        }, "sv-accept").apply { isDaemon = true; start() }
        return this.port
    }

    @Synchronized
    fun stop() {
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "close server", e)
        }
        serverSocket = null
        pending.values.forEach { closeQuietly(it.socket) }
        pending.clear()
    }

    // ---------- 请求读取 ----------

    private fun handleConnection(socket: Socket) {
        try {
            socket.soTimeout = REQUEST_WAIT_MS.toInt()
            socket.tcpNoDelay = true
            val input = socket.getInputStream()
            val out = BufferedOutputStream(socket.getOutputStream(), 32 * 1024)

            val head = readHead(input) ?: run {
                closeQuietly(socket)
                return
            }

            val parts = head.first.split(" ")
            val method = parts.getOrNull(0) ?: "GET"
            val rawTarget = parts.getOrNull(1) ?: "/"
            val path: String
            val rawQuery: String
            val qIndex = rawTarget.indexOf('?')
            if (qIndex >= 0) {
                path = rawTarget.substring(0, qIndex)
                rawQuery = rawTarget.substring(qIndex + 1)
            } else {
                path = rawTarget
                rawQuery = ""
            }
            val query = parseQuery(rawQuery)
            val headers = parseHeaders(head.second)

            val requestId = "req-${seq.incrementAndGet()}-${System.currentTimeMillis()}"
            val latch = CountDownLatch(1)
            pending[requestId] = ClientConn(socket, out, latch)

            try {
                onRequest?.invoke(requestId, method, path, query, headers)
            } catch (e: Exception) {
                Log.e(TAG, "emit request failed", e)
            }

            // 等 JS 决策；超时由网关兜底
            if (!latch.await(REQUEST_WAIT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "request $requestId timeout")
                runCatching {
                    writeSimple(out, 504, "Gateway Timeout", mapOf("Content-Type" to "application/json"),
                        """{"code":504,"msg":"JS处理超时"}""")
                }
            }
            flushAndClose(out, socket)
        } catch (e: Exception) {
            Log.w(TAG, "connection error", e)
            closeQuietly(socket)
        } finally {
            pending.entries.removeIf { it.value.socket === socket }
        }
    }

    /** 读到 \r\n\r\n 为止，返回 (请求行, 其余头部文本) */
    private fun readHead(input: InputStream): Pair<String, String>? {
        val buf = ByteArray(MAX_HEADER_BYTES)
        var pos = 0
        while (pos < buf.size) {
            val b = input.read()
            if (b < 0) break
            buf[pos++] = b.toByte()
            if (pos >= 4 &&
                buf[pos - 4] == '\r'.code.toByte() &&
                buf[pos - 3] == '\n'.code.toByte() &&
                buf[pos - 2] == '\r'.code.toByte() &&
                buf[pos - 1] == '\n'.code.toByte()
            ) {
                break
            }
        }
        if (pos == 0) return null
        val text = String(buf, 0, pos, Charsets.ISO_8859_1)
        val idx = text.indexOf("\r\n")
        return if (idx > 0) text.substring(0, idx) to text.substring(idx + 2) else text to ""
    }

    private fun parseHeaders(block: String): Map<String, String> {
        val out = HashMap<String, String>()
        block.split("\r\n").forEach { line ->
            val i = line.indexOf(':')
            if (i > 0) out[line.substring(0, i).trim().lowercase(Locale.US)] = line.substring(i + 1).trim()
        }
        return out
    }

    private fun parseQuery(raw: String): Map<String, String> {
        val out = HashMap<String, String>()
        if (raw.isEmpty()) return out
        raw.split("&").forEach { kv ->
            val i = kv.indexOf('=')
            if (i > 0) {
                val k = URLDecoder.decode(kv.substring(0, i), "UTF-8")
                val v = URLDecoder.decode(kv.substring(i + 1), "UTF-8")
                out[k] = v
            } else if (kv.isNotEmpty()) {
                out[URLDecoder.decode(kv, "UTF-8")] = ""
            }
        }
        return out
    }

    // ---------- JS 回写接口 ----------

    /** 回写文本响应（JSON / 204 等） */
    fun respondText(requestId: String, status: Int, headers: Map<String, String?>, body: String) {
        val conn = pending[requestId] ?: return
        val bytes = body.toByteArray(Charsets.UTF_8)
        val merged = headers.toMutableMap()
        merged.putIfAbsent("Content-Length", bytes.size.toString())
        runCatching {
            writeSimple(conn.out, status, reason(status), merged, body)
        }
        finish(requestId, conn)
    }

    /** 回写流式响应：从 stream 读取并 chunked 写给客户端 */
    fun pipeStream(requestId: String, status: Int, headers: Map<String, String?>, stream: InputStream?) {
        val conn = pending[requestId] ?: run { closeQuietly(stream); return }
        runCatching {
            writeHead(conn.out, status, reason(status), headers, chunked = true)
            if (stream != null) {
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = stream.read(buf)
                    if (n <= 0) break
                    writeChunk(conn.out, buf, n)
                }
            }
            writeChunkEnd(conn.out)
        }
        closeQuietly(stream)
        finish(requestId, conn)
    }

    private fun finish(requestId: String, conn: ClientConn) {
        pending.remove(requestId)
        runCatching { conn.out.flush() }
        conn.latch.countDown()
    }

    // ---------- HTTP 写出 ----------

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        204 -> "No Content"
        206 -> "Partial Content"
        301, 302, 307, 308 -> "Found"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        504 -> "Gateway Timeout"
        else -> "OK"
    }

    private fun writeSimple(out: OutputStream, status: Int, reason: String, headers: Map<String, String?>, body: String) {
        writeHead(out, status, reason, headers, chunked = false)
        out.write(body.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun writeHead(out: OutputStream, status: Int, reason: String, headers: Map<String, String?>, chunked: Boolean) {
        val sb = StringBuilder(256)
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        headers.forEach { (k, v) ->
            if (!v.isNullOrEmpty()) sb.append(k).append(": ").append(v).append("\r\n")
        }
        if (chunked) sb.append("Transfer-Encoding: chunked\r\n") else sb.append("Connection: close\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun writeChunk(out: OutputStream, buf: ByteArray, len: Int) {
        out.write(String.format(Locale.US, "%x\r\n", len).toByteArray(Charsets.ISO_8859_1))
        out.write(buf, 0, len)
        out.write("\r\n".toByteArray(Charsets.ISO_8859_1))
    }

    private fun writeChunkEnd(out: OutputStream) {
        out.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
    }

    private fun flushAndClose(out: OutputStream, socket: Socket) {
        runCatching { out.flush() }
        closeQuietly(socket)
    }

    private fun closeQuietly(c: AutoCloseable?) {
        runCatching { c?.close() }
    }
}
