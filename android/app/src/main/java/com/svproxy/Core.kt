@file:Suppress("DEPRECATION")
package com.svproxy

import android.net.Uri
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/** 统一网络层：纯 OkHttp，无 JS 引擎 / WebView */
object Http {
    const val UA_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
    const val UA_MOBILE = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36"

    data class Resp(val code: Int, val headers: Map<String, String>, val body: String, val finalUrl: String) {
        val ok get() = code in 200..299
        fun header(n: String): String? = headers.entries.firstOrNull { it.key.equals(n, true) }?.value
    }
    private fun base() = OkHttpClient.Builder().retryOnConnectionFailure(true)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).writeTimeout(25, TimeUnit.SECONDS)
    private val follow = base().followRedirects(true).followSslRedirects(true).build()
    private val manual = base().followRedirects(false).followSslRedirects(false).build()
    private fun Request.Builder.h(h: Map<String, String>): Request.Builder { h.forEach { (k, v) -> if (v.isNotEmpty()) header(k, v) }; return this }
    private fun run(c: OkHttpClient, r: Request): Resp? = try {
        c.newCall(r).execute().use { resp ->
            val hh = HashMap<String, String>()
            for (i in 0 until resp.headers.size) hh[resp.headers.name(i)] = resp.headers.value(i)
            Resp(resp.code, hh, try { resp.body?.string() ?: "" } catch (e: Exception) { "" }, resp.request.url.toString())
        }
    } catch (e: Exception) { null }

    fun get(url: String, h: Map<String, String> = emptyMap(), followRedirects: Boolean = true) =
        run(if (followRedirects) follow else manual, Request.Builder().url(url).get().h(h).build())
    fun head(url: String, h: Map<String, String> = emptyMap()) =
        run(follow, Request.Builder().url(url).head().h(h).build())
    fun postJson(url: String, json: String, h: Map<String, String> = emptyMap()) =
        run(follow, Request.Builder().url(url).post(json.toRequestBody("application/json; charset=utf-8".toMediaType())).h(h).build())
    fun postForm(url: String, form: Map<String, String>, h: Map<String, String> = emptyMap()): Resp? {
        val b = FormBody.Builder(); form.forEach { (k, v) -> b.add(k, v) }
        return run(follow, Request.Builder().url(url).post(b.build()).h(h).build())
    }
    fun location(url: String, h: Map<String, String> = emptyMap()): String? {
        val r = get(url, h, false) ?: return null
        if (r.code !in 300..399) return null
        val loc = r.header("Location") ?: return null
        return try { URL(URL(url), loc).toString() } catch (e: Exception) { null }
    }
    fun effectiveUrl(url: String, h: Map<String, String> = emptyMap()) = get(url, h, true)?.finalUrl
    fun toHttps(u: String?) = if (u.isNullOrEmpty()) null else if (u.startsWith("http://")) "https://${u.substring(7)}" else u
}

/** 统一输出模型 */
data class Media(
    var platform: String, var type: String, var title: String = "", var author: String = "",
    var avatar: String = "", var cover: String = "", var url: String? = null,
    var images: List<String> = emptyList(), var backups: List<String> = emptyList(),
    var liveVideos: List<String> = emptyList(), var musicUrl: String = "", var musicTitle: String = "",
    var duration: String = "", var quality: String = "", var sizeLabel: String = "",
    var publishTime: String = "", var extras: List<Pair<String, String>> = emptyList(),
)
data class ResolveOutput(val platform: String, val platformLabel: String, val items: List<Media>, val note: String = "")
sealed class ResolveOutcome {
    data class Ok(val output: ResolveOutput) : ResolveOutcome()
    data class Err(val code: Int, val msg: String, val platform: String = "") : ResolveOutcome()
}

/** 平台识别（源自 api/sv.php 的关键词分发表） */
enum class Platform(val key: String, val label: String, val needCookie: Boolean = false) {
    DOUYIN("douyin", "抖音"), DOUYIN_HOME("douyin_home", "抖音主页批量", true),
    KUAISHOU("kuaishou", "快手"), XIAOHONGSHU("xiaohongshu", "小红书"),
    BILIBILI("bilibili", "哔哩哔哩"), WEIBO("weibo", "微博"), TOUTIAO("toutiao", "今日头条"),
    PIPIXIA("pipixia", "皮皮虾"), PIPIGX("pipigx", "皮皮搞笑"), ZUIYOU("zuiyou", "最右"),
    DOUBAO_VIDEO("doubao_video", "豆包 AI 视频"), DOUBAO_CHAT("doubao_chat", "豆包对话图文"),
    JIMENG("jimeng", "即梦 AI"), NETEASE("netease", "网易云音乐"), UNKNOWN("unknown", "未知");
    companion object {
        fun fromKey(k: String) = entries.firstOrNull { it.key == k } ?: UNKNOWN
        fun detect(text: String): Platform {
            val s = text.lowercase()
            if (s.contains("jimeng.jianying.com") || s.contains("v.jimeng.aiseet") || s.contains("jimengai")) return JIMENG
            if (s.contains("163cn.tv") || s.contains("music.163.com") || s.contains("netease")) return NETEASE
            if (s.contains("doubao.com")) return if (s.contains("video-sharing") || s.contains("share_id") || s.contains("video_id")) DOUBAO_VIDEO else DOUBAO_CHAT
            if (s.contains("ippzone") || s.contains("pipigx")) return PIPIGX
            if (s.contains("pipix")) return PIPIXIA
            if (s.contains("izuiyou") || s.contains("xiaochuankeji")) return ZUIYOU
            if (s.contains("toutiao")) return TOUTIAO
            if (s.contains("weibo")) return WEIBO
            if (s.contains("b23.tv") || s.contains("bilibili")) return BILIBILI
            if (s.contains("xiaohongshu") || s.contains("xhslink") || s.contains("xhs.cn") || s.contains("xhs.com")) return XIAOHONGSHU
            if (s.contains("kuaishou") || s.contains("chenzhongtech") || s.contains("kwai")) return KUAISHOU
            if ((s.contains("douyin") || s.contains("iesdouyin")) && s.contains("douyin.com/user/")) return DOUYIN_HOME
            if (s.contains("douyin") || s.contains("iesdouyin")) return DOUYIN
            return UNKNOWN
        }
        private val URL_RE = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
        fun firstUrl(text: String) = URL_RE.find(text)?.value?.trimEnd('\'', '"', '，', '。', '；', ',', '.', ';')
    }
}

// ---- org.json 扩展 ----
fun Any?.svStr(): String = when (this) {
    null, JSONObject.NULL -> ""
    is String -> this
    is Number, is Boolean -> toString()
    is JSONObject, is JSONArray -> ""
    else -> ""
}
fun JSONObject.pick(vararg keys: String): String { for (k in keys) { val v = opt(k).svStr(); if (v.isNotEmpty()) return v }; return "" }
fun JSONObject.obj(n: String): JSONObject? = optJSONObject(n)
fun JSONObject.arr(n: String): JSONArray? = optJSONArray(n)
fun JSONArray?.strs(): List<String> = this?.let { a -> (0 until a.length()).mapNotNull { a.opt(it).svStr().takeIf { s -> s.isNotEmpty() } } } ?: emptyList()
fun JSONArray?.objs(): List<JSONObject> = this?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()
fun JSONObject.path(vararg keys: String): JSONObject? { var c: JSONObject? = this; for (k in keys) c = c?.obj(k) ?: return null; return c }
fun JSONObject.pathStr(vararg keys: String): String { if (keys.isEmpty()) return ""; var c = this; for (i in 0 until keys.size - 1) c = c.obj(keys[i]) ?: return ""; return c.pick(keys.last()) }
fun JSONObject.pathArr(vararg keys: String): JSONArray? { if (keys.isEmpty()) return null; var c = this; for (i in 0 until keys.size - 1) c = c.obj(keys[i]) ?: return null; return c.arr(keys.last()) }

// ---- 共享工具 ----
fun err(code: Int, msg: String, p: Platform) = ResolveOutcome.Err(code, msg, p.key)
fun ok(p: Platform, items: List<Media>, note: String = "") = ResolveOutcome.Ok(ResolveOutput(p.key, p.label, items, note))
fun m(p: Platform, block: Media.() -> Unit): Media = Media(p.key, "video").apply(block)
fun headers(ua: String = Http.UA_DESKTOP, referer: String = "", cookie: String = "", accept: String = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"): Map<String, String> {
    val h = LinkedHashMap<String, String>()
    h["User-Agent"] = ua; h["Accept"] = accept; h["Accept-Language"] = "zh-CN,zh;q=0.9"
    if (referer.isNotEmpty()) h["Referer"] = referer
    if (cookie.isNotEmpty()) h["Cookie"] = cookie
    return h
}
fun queryOf(url: String, key: String): String? = try { Uri.parse(url).getQueryParameter(key) } catch (e: Exception) { null }
fun formatSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val u = arrayOf("B", "KB", "MB", "GB", "TB"); var v = bytes.toDouble(); var i = 0
    while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
    if (i == 0) return "${kotlin.math.round(v).toLong()}B"
    return String.format(java.util.Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.') + u[i]
}
fun decode(s: String) = try { URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { s }
fun fmtTime(sec: Long): String {
    if (sec <= 0) return ""
    return try {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
        f.timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
        f.format(java.util.Date(sec * 1000))
    } catch (e: Exception) { "" }
}
