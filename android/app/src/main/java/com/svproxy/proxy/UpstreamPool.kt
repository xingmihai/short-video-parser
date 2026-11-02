package com.svproxy.proxy

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 解析请求池：发 HTTP 请求并把响应体读成文本。
 *
 * 不再提供媒体流式代理（本地网关已移除），只保留抖音解析所需的请求能力：
 *   - 短链 302 手动跟随（redirect: manual）
 *   - 详情接口 JSON 自动跟随
 *   - 原画地址 302 手动跟随
 *   - HEAD 探测文件大小
 * 另外提供带 Referer 的直链下载（OkHttp → app 私有目录），不监听任何端口。
 */
class UpstreamPool {

    private fun baseBuilder() = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)

    /** 手动跟随：解析短链 / 原画 302 时保留 Location */
    private val manualClient = baseBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** 自动跟随：详情接口 / 下载直链 */
    private val followClient = baseBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * @return Triple(status, headers, bodyText)
     */
    fun requestText(
        url: String,
        method: String,
        headers: Map<String, String?>,
        timeoutMs: Int,
        followRedirects: Boolean,
    ): Triple<Int, Map<String, String>, String> {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> if (!v.isNullOrEmpty()) builder.header(k, v) }

        val upper = method.uppercase()
        if (upper == "HEAD" || upper == "GET") {
            builder.method(upper, null)
        } else {
            builder.method(upper, okhttp3.RequestBody.create(null, ByteArray(0)))
        }

        val http = if (followRedirects) followClient else manualClient
        val call = http.newCall(builder.build())
        call.timeout().timeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        call.execute().use { response ->
            val out = HashMap<String, String>()
            for (i in 0 until response.headers.size) {
                out[response.headers.name(i)] = response.headers.value(i)
            }
            val text = try {
                response.body?.string() ?: ""
            } catch (e: Exception) {
                ""
            }
            return Triple(response.code, out, text)
        }
    }

    /** 带自定义头下载到指定文件，返回写入字节数 */
    fun downloadTo(url: String, headers: Map<String, String?>, target: java.io.File): Long {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> if (!v.isNullOrEmpty()) builder.header(k, v) }
        target.parentFile?.mkdirs()

        var written = 0L
        followClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("空响应体")
            body.byteStream().use { input ->
                java.io.FileOutputStream(target).use { output ->
                    written = input.copyTo(output, 64 * 1024)
                }
            }
        }
        return written
    }
}
