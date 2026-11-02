package com.svproxy

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Environment
import android.widget.ImageView
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 带对应平台 Referer 的下载 / 图片加载，不引第三方图片库 */
object Media {
    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36"

    fun refererFor(p: String): String = when (p) {
        "douyin", "douyin_home", "doubao_video" -> "https://www.douyin.com/"
        "kuaishou" -> "https://www.kuaishou.com/"
        "xiaohongshu" -> "https://www.xiaohongshu.com/"
        "bilibili" -> "https://www.bilibili.com/"
        "weibo" -> "https://weibo.com/"
        "toutiao" -> "https://www.toutiao.com/"
        "pipixia" -> "https://h5.pipix.com/"
        "pipigx" -> "https://h5.pipigx.com/"
        "zuiyou" -> "https://share.xiaochuankeji.cn/"
        "doubao_chat" -> "https://www.doubao.com/"
        "jimeng" -> "https://jimeng.jianying.com/"
        "netease" -> "https://music.163.com/"
        else -> ""
    }

    private val client = OkHttpClient.Builder().followRedirects(true).followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "sv-media").apply { isDaemon = true } }

    private fun safeName(n: String) = n.replace(Regex("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]"), "_").take(70).ifBlank { "sv-${System.currentTimeMillis()}" }

    fun download(context: Context, url: String, fileName: String, mime: String, referer: String, onDone: (Result<File>) -> Unit) {
        pool.submit {
            val result = runCatching {
                val ext = when { mime.startsWith("image") -> ".jpg"; mime.startsWith("audio") -> ".mp3"; else -> ".mp4" }
                var name = safeName(fileName); if (!name.contains('.')) name += ext
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "sv")
                val target = File(dir, name)
                val b = Request.Builder().url(url).get().header("User-Agent", UA)
                if (referer.isNotEmpty()) b.header("Referer", referer)
                client.newCall(b.build()).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val body = resp.body ?: error("空响应体")
                    dir.mkdirs()
                    body.byteStream().use { input -> FileOutputStream(target).use { out -> input.copyTo(out, 64 * 1024) } }
                }
                target
            }
            onDone(result)
        }
    }

    fun open(context: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    fun loadImage(url: String, referer: String, into: ImageView) {
        if (url.isEmpty()) return
        pool.submit {
            val bmp = runCatching {
                val b = Request.Builder().url(url).get().header("User-Agent", UA)
                if (referer.isNotEmpty()) b.header("Referer", referer)
                client.newCall(b.build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val bytes = resp.body?.bytes() ?: return@use null
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }.getOrNull()
            into.post { if (bmp != null) into.setImageBitmap(bmp) }
        }
    }
}
