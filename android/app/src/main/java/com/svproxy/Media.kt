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

/**
 * 媒体辅助：带 Referer 下载 + 带 Referer 加载网络图片。
 * 不引入图片库（Glide/Coil），直接用 OkHttp + BitmapFactory，保持依赖最小。
 */
object Media {

    const val REFERER = "https://www.douyin.com/"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36"

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "sv-media").apply { isDaemon = true } }

    private fun safeName(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]"), "_").take(70).ifBlank {
            "sv-${System.currentTimeMillis()}"
        }

    /**
     * 下载到 App 私有外部目录，返回文件。调用方负责用 FileProvider 打开。
     * 在后台线程执行，回调回主线程由调用方处理。
     */
    fun download(
        context: Context,
        url: String,
        fileName: String,
        mime: String,
        onDone: (Result<File>) -> Unit,
    ) {
        pool.submit {
            val result = runCatching {
                val ext = when {
                    mime.startsWith("image") -> ".jpg"
                    mime.startsWith("audio") -> ".mp3"
                    else -> ".mp4"
                }
                var name = safeName(fileName)
                if (!name.contains('.')) name += ext
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "sv")
                val target = File(dir, name)

                val req = Request.Builder().url(url).get()
                    .header("User-Agent", UA)
                    .header("Referer", REFERER)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val body = resp.body ?: error("空响应体")
                    dir.mkdirs()
                    body.byteStream().use { input ->
                        FileOutputStream(target).use { out -> input.copyTo(out, 64 * 1024) }
                    }
                }
                target
            }
            onDone(result)
        }
    }

    /** 用 content:// 把已下载文件交给系统播放器 / 图片查看器 */
    fun open(context: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** 带 Referer 异步加载图片到 ImageView（主线程回调） */
    fun loadImage(url: String, into: ImageView) {
        if (url.isEmpty()) return
        pool.submit {
            val bmp = runCatching {
                val req = Request.Builder().url(url).get()
                    .header("User-Agent", UA)
                    .header("Referer", REFERER)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val bytes = resp.body?.bytes() ?: return@use null
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }.getOrNull()
            into.post { if (bmp != null) into.setImageBitmap(bmp) }
        }
    }
}
