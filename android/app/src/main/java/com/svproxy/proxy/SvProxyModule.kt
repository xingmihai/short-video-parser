package com.svproxy.proxy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import java.io.File
import java.util.concurrent.Executors

/**
 * JS 侧看到的 SvProxyGateway（名字保持不变，避免改 index.js 注册名）。
 *
 * 本地 HTTP 网关已移除：不监听端口、不转发请求、没有局域网地址。
 * 原生只提供：
 *   requestText  解析用的 HTTP 请求（手动/自动跟 302，绕开浏览器限制）
 *   download     带 Referer 下载直链到 App 私有目录并调用系统播放器打开
 *   copy/read    剪贴板
 * 业务与界面逻辑全部在 index.android.bundle 里。
 */
class SvProxyModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val MODULE_NAME = "SvProxyGateway"
        const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36"
    }

    private val upstream = UpstreamPool()
    private val pool = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "sv-worker").apply { isDaemon = true }
    }

    override fun getName() = MODULE_NAME

    /**
     * 解析用：发请求并读回文本响应。
     * redirect: "manual" 时保留 302 + Location，供 JS 循环跟随。
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

    /**
     * 带 Referer 下载直链。不经过任何本地服务器：
     *   OkHttp 请求（可带防盗链头）→ 写入 getExternalFilesDir(Download)/sv/
     *   → FileProvider 生成 content:// → ACTION_VIEW 交给系统播放器/图片查看器
     *
     * @param mime 形如 video/mp4、image/jpeg、audio/mpeg
     */
    @ReactMethod
    fun download(
        url: String,
        referer: String,
        fileName: String,
        mime: String,
        promise: Promise,
    ) {
        pool.submit {
            try {
                val safe = fileName
                    .replace(Regex("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]"), "_")
                    .take(70)
                    .ifBlank { "sv-${System.currentTimeMillis()}" }
                val ext = when {
                    mime.startsWith("image") -> ".jpg"
                    mime.startsWith("audio") -> ".mp3"
                    else -> ".mp4"
                }
                val name = if (safe.contains('.')) safe else safe + ext
                val dir = File(reactContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "sv")
                val target = File(dir, name)

                val headers = HashMap<String, String?>()
                headers["User-Agent"] = DEFAULT_UA
                if (referer.isNotEmpty()) headers["Referer"] = referer
                val size = upstream.downloadTo(url, headers, target)

                val uri = FileProvider.getUriForFile(
                    reactContext,
                    "${reactContext.packageName}.fileprovider",
                    target,
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                reactContext.startActivity(intent)

                promise.resolve(Arguments.createMap().apply {
                    putString("path", target.absolutePath)
                    putDouble("size", size.toDouble())
                })
            } catch (e: Exception) {
                promise.reject("DOWNLOAD_FAILED", e.message ?: "下载失败", e)
            }
        }
    }

    /** 复制文本到系统剪贴板 */
    @ReactMethod
    fun copyText(text: String, promise: Promise) {
        reactContext.runOnUiQueueThread {
            try {
                val cm = reactContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("sv", text))
                promise.resolve(true)
            } catch (e: Exception) {
                promise.reject("CLIPBOARD_FAILED", e.message, e)
            }
        }
    }

    /** 读取剪贴板：抖音「分享 → 复制链接」后一键粘贴解析 */
    @ReactMethod
    fun readText(promise: Promise) {
        reactContext.runOnUiQueueThread {
            try {
                val cm = reactContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = cm.primaryClip
                promise.resolve(
                    if (clip != null && clip.itemCount > 0) {
                        clip.getItemAt(0).text?.toString() ?: ""
                    } else ""
                )
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
        pool.shutdownNow()
    }
}
