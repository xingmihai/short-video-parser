package com.svproxy.proxy

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * JS 侧看到的 SvProxyGateway。
 *
 * 对外只暴露"能力"，不含业务逻辑 —— 业务在 index.android.bundle 里：
 *   SvProxyRequest 事件  →  handleRequest()  →  respondText / pipeToClient
 */
class SvProxyModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val MODULE_NAME = "SvProxyGateway"
        const val EVENT_REQUEST = "SvProxyRequest"
    }

    private val gateway = HttpGateway()
    private val upstream = UpstreamPool()
    private val pool = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "sv-worker").apply { isDaemon = true }
    }

    init {
        gateway.onRequest = { requestId, method, path, query, headers ->
            emitRequest(requestId, method, path, query, headers)
        }
    }

    override fun getName() = MODULE_NAME

    private fun emitRequest(
        requestId: String,
        method: String,
        path: String,
        query: Map<String, String>,
        headers: Map<String, String>,
    ) {
        val body = Arguments.createMap().apply {
            putString("requestId", requestId)
            putString("method", method)
            putString("path", path)
            putMap("query", Arguments.createMap().apply {
                query.forEach { (k, v) -> putString(k, v) }
            })
            putMap("headers", Arguments.createMap().apply {
                headers.forEach { (k, v) -> putString(k, v) }
            })
        }
        emit(EVENT_REQUEST, body)
    }

    private fun emit(name: String, data: WritableMap) {
        runCatching {
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(name, data)
        }
    }

    // ---------- 导出给 JS 的方法 ----------

    @ReactMethod
    fun start(port: Int, promise: Promise) {
        pool.submit {
            try {
                val actual = gateway.start(port)
                val host = localIpAddress() ?: "127.0.0.1"
                promise.resolve(Arguments.createMap().apply {
                    putInt("port", actual)
                    putString("host", host)
                })
            } catch (e: Exception) {
                promise.reject("START_FAILED", e.message ?: "启动失败", e)
            }
        }
    }

    @ReactMethod
    fun stop(promise: Promise) {
        pool.submit {
            gateway.stop()
            upstream.releaseAll()
            promise.resolve(null)
        }
    }

    @ReactMethod
    fun isRunning(promise: Promise) {
        promise.resolve(gateway.isRunning)
    }

    @ReactMethod
    fun getLocalIp(promise: Promise) {
        promise.resolve(localIpAddress() ?: "127.0.0.1")
    }

    @ReactMethod
    fun openUpstream(url: String, method: String, headers: ReadableMap, timeoutMs: Int, promise: Promise) {
        pool.submit {
            try {
                val map = headers.toHashMap().mapValues { it.value?.toString() }
                val (handleId, status, respHeaders) = upstream.open(url, method, map, timeoutMs)
                promise.resolve(Arguments.createMap().apply {
                    putString("handleId", handleId)
                    putInt("status", status)
                    putMap("headers", Arguments.createMap().apply {
                        respHeaders.forEach { (k, v) -> putString(k, v) }
                    })
                })
            } catch (e: Exception) {
                promise.reject("UPSTREAM_FAILED", e.message ?: "上游请求失败", e)
            }
        }
    }

    /**
     * 解析用：发 HTTP 请求并把响应体读成文本交给 JS（不受浏览器 CORS 限制）。
     * 抖音短链跟随、详情接口 JSON 都走这里。
     */
    @ReactMethod
    fun requestText(
        url: String,
        method: String,
        headers: ReadableMap,
        timeoutMs: Int,
        followRedirects: Boolean,
        promise: Promise,
    ) {
        pool.submit {
            try {
                val map = headers.toHashMap().mapValues { it.value?.toString() }
                val (status, respHeaders, body) =
                    upstream.requestText(url, method, map, timeoutMs, followRedirects)
                promise.resolve(Arguments.createMap().apply {
                    putInt("status", status)
                    putString("body", body)
                    putMap("headers", Arguments.createMap().apply {
                        respHeaders.forEach { (k, v) -> putString(k, v) }
                    })
                })
            } catch (e: Exception) {
                promise.reject("REQUEST_FAILED", e.message ?: "请求失败", e)
            }
        }
    }

    @ReactMethod
    fun pipeToClient(requestId: String, status: Int, headers: ReadableMap, handleId: String) {
        pool.submit {
            val map = headers.toHashMap().mapValues { it.value?.toString() }
            gateway.pipeStream(requestId, status, map, upstream.bodyStream(handleId))
            upstream.close(handleId)
        }
    }

    @ReactMethod
    fun respondText(requestId: String, status: Int, headers: ReadableMap, body: String) {
        val map = headers.toHashMap().mapValues { it.value?.toString() }
        gateway.respondText(requestId, status, map, body)
    }

    @ReactMethod
    fun closeHandle(handleId: String) {
        pool.submit { upstream.close(handleId) }
    }

    @ReactMethod
    fun sha256(text: String, promise: Promise) {
        pool.submit {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            promise.resolve(digest.joinToString("") { "%02x".format(it) })
        }
    }

    /** 复制文本到系统剪贴板（App 内「复制直链 / 复制代理链接」） */
    @ReactMethod
    fun copyText(text: String, promise: Promise) {
        reactContext.runOnUiQueueThread {
            try {
                val cm = reactContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("sv-proxy", text))
                promise.resolve(true)
            } catch (e: Exception) {
                promise.reject("CLIPBOARD_FAILED", e.message, e)
            }
        }
    }

    /** 读取剪贴板文本：用户从抖音复制分享文案后，App 内一键「粘贴并解析」 */
    @ReactMethod
    fun readText(promise: Promise) {
        reactContext.runOnUiQueueThread {
            try {
                val cm = reactContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                val clip = cm.primaryClip
                val text = if (clip != null && clip.itemCount > 0) {
                    clip.getItemAt(0).text?.toString() ?: ""
                } else ""
                promise.resolve(text)
            } catch (e: Exception) {
                promise.resolve("")
            }
        }
    }

    @ReactMethod
    fun addListener(eventName: String) { /* RN 事件订阅占位 */ }

    @ReactMethod
    fun removeListeners(count: Int) { /* RN 事件订阅占位 */ }

    override fun onCatalystInstanceDestroy() {
        super.onCatalystInstanceDestroy()
        gateway.stop()
        upstream.releaseAll()
    }

    // ---------- 工具 ----------

    private fun localIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { addr ->
                    !addr.isLoopbackAddress &&
                        !addr.isLinkLocalAddress &&
                        addr is InetAddress &&
                        addr.address.size == 4
                }?.hostAddress
        } catch (e: Exception) {
            null
        }
    }
}
