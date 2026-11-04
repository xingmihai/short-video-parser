package com.svproxy

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.ImageView
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 一次下载的结果：公共 Download/ 里的 content Uri + 显示名 */
data class Saved(val uri: Uri, val displayName: String)

/** 带对应平台 Referer 的下载 / 图片加载，不引第三方图片库 */
object MediaKit {
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

    /**
     * 下载到公共 Download 目录（/storage/emulated/0/Download/）。
     * Android 10+ 通过 MediaStore.Downloads 写入；Android 8/9 直接写文件并用 FileProvider 暴露。
     */
    fun download(context: Context, url: String, fileName: String, mime: String, referer: String, onDone: (Result<Saved>) -> Unit) {
        pool.submit {
            val result = runCatching {
                val ext = when { mime.startsWith("image") -> ".jpg"; mime.startsWith("audio") -> ".mp3"; else -> ".mp4" }
                var name = safeName(fileName); if (!name.contains('.')) name += ext
                val b = Request.Builder().url(url).get().header("User-Agent", UA)
                if (referer.isNotEmpty()) b.header("Referer", referer)
                client.newCall(b.build()).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val body = resp.body ?: error("空响应体")
                    if (Build.VERSION.SDK_INT >= 29) {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, name)
                            put(MediaStore.Downloads.MIME_TYPE, mime)
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                            put(MediaStore.Downloads.IS_PENDING, 1)
                        }
                        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: error("无法在 Download/ 创建文件")
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            body.byteStream().use { input -> input.copyTo(out, 64 * 1024) }
                        } ?: error("无法写入 Download/")
                        values.clear()
                        values.put(MediaStore.Downloads.IS_PENDING, 0)
                        context.contentResolver.update(uri, values, null, null)
                        Saved(uri, name)
                    } else {
                        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        dir.mkdirs()
                        val target = File(dir, name)
                        body.byteStream().use { input -> FileOutputStream(target).use { out -> input.copyTo(out, 64 * 1024) } }
                        Saved(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target), name)
                    }
                }
            }
            onDone(result)
        }
    }

    fun open(context: Context, saved: Saved, mime: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(saved.uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    /** 结果先显示，大小随后异步探测（不阻塞首屏） */
    fun probeSize(url: String, referer: String, onDone: (String) -> Unit) {
        if (url.isEmpty()) { onDone(""); return }
        pool.submit {
            val label = runCatching {
                val b = Request.Builder().url(url).head().header("User-Agent", UA)
                if (referer.isNotEmpty()) b.header("Referer", referer)
                client.newCall(b.build()).execute().use { resp ->
                    val len = resp.header("Content-Length")?.toLongOrNull() ?: 0L
                    if (len > 0) formatSize(len) else ""
                }
            }.getOrDefault("")
            onDone(label)
        }
    }

    /** 图片异步加载，onDone 在主线程回调，用于「先显示再加载」的计数 */
    fun loadImage(url: String, referer: String, into: ImageView, onDone: ((Boolean) -> Unit)? = null) {
        if (url.isEmpty()) { onDone?.invoke(false); return }
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
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (bmp != null) into.setImageBitmap(bmp)
                onDone?.invoke(bmp != null)
            }
        }
    }
}
