package com.svproxy.proxy

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 上游请求池。
 *
 * 只发请求、拿状态行和响应头，不把 body 读进内存；
 * body 的 InputStream 以 handleId 形式交给 JS，JS 决定后由网关 pipe 回客户端。
 * followRedirects 关闭 —— 302 跟随策略由 JS 控制（worker.js 的手动跟随逻辑）。
 */
class UpstreamPool {

    data class Handle(val call: Call, val response: Response)

    private val seq = AtomicInteger(0)
    private val handles = ConcurrentHashMap<String, Handle>()

    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 发起上游请求
     * @return Triple(handleId, status, headers)
     */
    fun open(url: String, method: String, headers: Map<String, String?>, timeoutMs: Int): Triple<String, Int, Map<String, String>> {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> if (!v.isNullOrEmpty()) builder.header(k, v) }

        val upper = method.uppercase()
        if (upper == "HEAD" || upper == "GET") {
            builder.method(upper, null)
        } else {
            builder.method(upper, okhttp3.RequestBody.create(null, ByteArray(0)))
        }

        val call = client.newCall(builder.build())
        call.timeout().timeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        val response = call.execute()

        val handleId = "up-${seq.incrementAndGet()}"
        handles[handleId] = Handle(call, response)

        val out = HashMap<String, String>()
        for (i in 0 until response.headers.size) {
            out[response.headers.name(i)] = response.headers.value(i)
        }
        return Triple(handleId, response.code, out)
    }

    fun bodyStream(handleId: String) = handles[handleId]?.response?.body?.byteStream()

    fun close(handleId: String) {
        handles.remove(handleId)?.response?.close()
    }

    fun releaseAll() {
        handles.values.forEach { runCatching { it.response.close() } }
        handles.clear()
    }
}
