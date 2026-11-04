@file:Suppress("DEPRECATION")

package com.svproxy

import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.round

/**
 * 抖音分享链接解析 —— 纯 Kotlin 实现，逻辑移植自
 * jiuhunwl/short_videos · api/douyin-cloudflare workers/workers.js
 * 原作者 JH-Ahua（www.jiuhunwl.cn），MIT。
 *
 * 没有任何 JS 引擎、没有 index.android.bundle、没有明文/字节码资产。
 * 所有网络请求由 OkHttp 完成，JSON 用 Android 内置 org.json。
 */

// ---------------------------------------------------------------------------
// 数据模型
// ---------------------------------------------------------------------------

data class Author(val name: String, val id: String, val avatar: String)
data class Music(val title: String, val author: String, val url: String, val cover: String)
data class LivePhoto(val image: String, val video: String)

data class MediaResult(
    val type: String,                 // video / image / live / unknown
    val title: String,
    val author: Author,
    val cover: String,
    val url: String?,                 // 主视频无水印直链
    val quality: String,
    val duration: Double?,            // 秒
    val size: Long,
    val sizeLabel: String,
    val createTime: Long,
    val publishTime: String,
    val videoBackup: List<String>,
    val images: List<String>,
    val livePhoto: List<LivePhoto>,
    val music: Music,
    val videoId: String?,
)

sealed class ParseResult {
    data class Ok(val data: MediaResult) : ParseResult()
    data class Err(val code: Int, val msg: String) : ParseResult()
}

// ---------------------------------------------------------------------------
// JSON 辅助：org.json 的 optString 会把JSONObject / 数字转成字符串，这里严格挑类型
// ---------------------------------------------------------------------------

private fun Any?.asStr(): String = when (this) {
    null, JSONObject.NULL -> ""
    is String -> this
    is Number, is Boolean -> toString()
    is JSONObject, is JSONArray -> ""
    else -> ""
}

/** 依次取第一个非空字符串，对应 JS 的 `a || b || ""` */
private fun JSONObject.s(vararg keys: String): String {
    for (k in keys) {
        val v = opt(k).asStr()
        if (v.isNotEmpty()) return v
    }
    return ""
}

private fun JSONObject.o(name: String): JSONObject? = optJSONObject(name)
private fun JSONObject.a(name: String): JSONArray? = optJSONArray(name)

private fun JSONArray?.strings(): List<String> = this?.let { arr ->
    (0 until arr.length()).mapNotNull { i -> arr.opt(i).asStr().takeIf { it.isNotEmpty() } }
} ?: emptyList()

private fun JSONArray?.objects(): List<JSONObject> = this?.let { arr ->
    (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i) }
} ?: emptyList()

private fun JSONObject.firstStringArray(vararg names: String): List<String> {
    for (n in names) {
        val l = a(n).strings()
        if (l.isNotEmpty()) return l
    }
    return emptyList()
}

private fun JSONObject.firstObjectArray(vararg names: String): List<JSONObject> {
    for (n in names) {
        val l = a(n).objects()
        if (l.isNotEmpty()) return l
    }
    return emptyList()
}

// ---------------------------------------------------------------------------
// 解析主体
// ---------------------------------------------------------------------------

object DouyinResolver {

    private const val DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 13; 2210132C Build/TKQ1.220829.002) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36"
    private const val ORIGINAL_PLAY_ENDPOINT = "https://aweme.snssdk.com/aweme/v1/play/"

    private val REDIRECT_DONE_HOSTS = setOf("www.douyin.com")
    private val PLATFORM_HOSTS = setOf("www.douyin.com", "www.iesdouyin.com")
    private val V26_HOST_RE = Regex("://[^/]+")

    private fun baseBuilder() = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)

    /** 手动跟随：保留 302 + Location */
    private val manualClient = baseBuilder()
        .followRedirects(false).followSslRedirects(false).build()

    /** 自动跟随：详情接口 / HEAD 探测 / 下载 */
    private val followClient = baseBuilder()
        .followRedirects(true).followSslRedirects(true).build()

    // ---------------- 对外入口 ----------------

    fun parse(shareText: String): ParseResult {
        val shareUrl = extractFirstUrl(shareText)
            ?: return ParseResult.Err(400, "未在文本中识别到有效链接")

        val resolved = followRedirectsToPlatform(shareUrl)
            ?: return ParseResult.Err(400, "无法解析重定向或链接不属于抖音")

        val h = host(resolved)
        if (h == null || h !in PLATFORM_HOSTS) {
            return ParseResult.Err(400, "该链接不是抖音网页链接")
        }

        val realUrl = extractVideoAddress(resolved)
        val awemeId = getVideoId(realUrl)
            ?: return ParseResult.Err(400, "链接格式错误，无法提取ID。处理后的链接: $realUrl")

        if (!Regex("^[1-9][0-9]{15,19}$").matches(awemeId)) {
            return ParseResult.Err(500, "请求失败（请输入作品 aweme_id，而不是合集 mix_id）")
        }

        val detail = fetchAwemeDetail(awemeId)
            ?: return ParseResult.Err(500, "请求失败（详情接口未返回有效数据）")

        val payload = buildResult(detail, awemeId)

        val ok = (payload.type == "video" && !payload.url.isNullOrEmpty()) ||
            ((payload.type == "image" || payload.type == "live") && payload.images.isNotEmpty())
        if (!ok) return ParseResult.Err(404, "解析失败，未找到有效内容")

        return ParseResult.Ok(payload)
    }

    // ---------------- 链接处理 ----------------

    private val URL_RE = Regex(
        """\bhttps?://(?:www\.|[-a-zA-Z0-9.@:%_+~#=]{1,256}\.[a-zA-Z0-9()]{1,6})\b(?:[-a-zA-Z0-9()@:%_+.~#?&/=]*)?""",
        RegexOption.IGNORE_CASE,
    )

    private fun extractFirstUrl(text: String): String? = URL_RE.find(text)?.value

    private fun host(url: String): String? = try {
        Uri.parse(url).host?.lowercase(Locale.ROOT)
    } catch (e: Exception) {
        null
    }

    /** 手动跟 302，只返回下一跳的绝对地址 */
    private fun requestLocation(url: String): String? {
        val req = Request.Builder().url(url).get()
            .header("User-Agent", DEFAULT_UA)
            .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()
        return try {
            manualClient.newCall(req).execute().use { resp ->
                if (resp.code < 300 || resp.code >= 400) return@use null
                val loc = resp.header("Location") ?: return@use null
                try {
                    java.net.URL(java.net.URL(url), loc).toString()
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun followRedirectsToPlatform(startUrl: String, max: Int = 8): String? {
        var current = startUrl
        repeat(max) {
            val h = host(current)
            if (h != null && h in REDIRECT_DONE_HOSTS) return current
            val next = requestLocation(current) ?: return@repeat
            current = next
        }
        val finalHost = host(current)
        return if (finalHost != null && finalHost in PLATFORM_HOSTS) current else null
    }

    /** 去掉查询串与尾部斜杠，保留 modal_id */
    private fun extractVideoAddress(url: String): String {
        val u = try { Uri.parse(url) } catch (e: Exception) { return url }
        val h = u.host ?: return url
        val cleanPath = (u.path ?: "/").trimEnd('/')
        val normalized = "${u.scheme ?: "https"}://$h$cleanPath"
        if (h in PLATFORM_HOSTS) {
            val modalId = u.getQueryParameter("modal_id")
            if (!modalId.isNullOrEmpty()) {
                return "$normalized?modal_id=${URLEncoder.encode(modalId, "UTF-8")}"
            }
        }
        return normalized
    }

    private fun getVideoId(url: String): String? {
        val u = try { Uri.parse(url) } catch (e: Exception) { return null }
        for (k in arrayOf("vid", "id", "modal_id", "v", "s", "pid")) {
            val v = u.getQueryParameter(k)
            if (!v.isNullOrEmpty()) return v
        }
        var last = (u.path ?: "").trimEnd('/').split("/").lastOrNull { it.isNotEmpty() }
            ?: return null
        if (last.endsWith(".html")) last = last.dropLast(5)
        return last.ifEmpty { null }
    }

    // ---------------- 详情接口 ----------------

    private fun fetchAwemeDetail(awemeId: String): JSONObject? {
        val url = Uri.parse("https://api.amemv.com/aweme/v1/aweme/detail/").buildUpon()
            .appendQueryParameter("aweme_id", awemeId)
            .appendQueryParameter("aid", "6383")
            .appendQueryParameter("device_platform", "web")
            .appendQueryParameter("version_code", "190500")
            .appendQueryParameter("version_name", "19.5.0")
            .build().toString()

        val req = Request.Builder().url(url).get()
            .header("User-Agent", MOBILE_UA)
            .header("Accept", "application/json, text/plain, */*")
            .build()

        return try {
            followClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                val json = JSONObject(body)
                if (json.optInt("status_code", -1) != 0) return@use null
                val detail = json.o("aweme_detail") ?: return@use null
                if (detail.s("aweme_id") != awemeId) return@use null
                detail
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- 原画直链 ----------------

    private fun buildOriginalPlayUrl(vid: String, ratio: String = "default"): String =
        "$ORIGINAL_PLAY_ENDPOINT?video_id=${URLEncoder.encode(vid, "UTF-8")}&ratio=$ratio&line=0"

    private fun resolveOriginalVideoUrl(vid: String?): String? {
        if (vid.isNullOrEmpty()) return null
        val fallback = buildOriginalPlayUrl(vid)
        var current = fallback
        try {
            repeat(3) {
                val next = requestLocation(current) ?: return@repeat
                current = next
            }
        } catch (e: Exception) {
            return fallback
        }
        return if (current == fallback) fallback else (toHttps(current) ?: fallback)
    }

    /** 批量转 vid：去重 + 上限 30 + 并发 8 */
    private fun resolveVidBatch(vids: List<String?>): Map<String, String> {
        val limited = vids.filterNotNull().distinct().take(30)
        if (limited.isEmpty()) return emptyMap()
        val pool = Executors.newFixedThreadPool(minOf(8, limited.size))
        return try {
            val futures = limited.map { v ->
                pool.submit(Callable { v to (resolveOriginalVideoUrl(v) ?: "") })
            }
            futures.mapNotNull { it.get() }.toMap()
        } finally {
            pool.shutdown()
        }
    }

    // ---------------- 媒体信息提取 ----------------

    private fun extractVid(video: JSONObject?): String? {
        if (video == null) return null
        val playAddr = video.o("play_addr")
        if (playAddr != null) {
            val v = playAddr.s("uri")
            if (v.isNotEmpty()) return v
        }
        val playAddrArr = video.a("playAddr").objects()
        if (playAddrArr.isNotEmpty()) {
            val v = playAddrArr[0].s("uri")
            if (v.isNotEmpty()) return v
        }
        return video.s("uri").ifEmpty { null }
    }

    private fun v26Rewrite(url: String): String =
        V26_HOST_RE.replaceFirst(url, "://v26-luna.douyinvod.com")

    private fun playUrlCandidates(rate: JSONObject): List<String> {
        val out = mutableListOf<String>()
        val playAddr = rate.a("playAddr").objects()
        for (p in playAddr) {
            val src = p.s("src")
            if (src.isNotEmpty()) out.add(src)
        }
        if (out.isEmpty()) {
            out.addAll(rate.o("play_addr")?.a("url_list").strings())
        }
        return out
    }

    private data class VideoPick(val url: String?, val backup: MutableList<String>, val gearName: String)

    private fun extractHighestQualityVideo(detail: JSONObject): VideoPick {
        val video = detail.o("video") ?: JSONObject()
        val rates = video.firstObjectArray("bitRateList", "bit_rate")
            .sortedByDescending { r ->
                (r.opt("bitRate") ?: r.opt("bit_rate") ?: 0).toString().toDoubleOrNull() ?: 0.0
            }

        var url: String? = null
        var gearName = ""
        val backup = mutableListOf<String>()

        for (rate in rates) {
            val candidates = playUrlCandidates(rate)
            if (candidates.isEmpty()) continue

            var v3: String? = null
            var v26: String? = null
            for (c in candidates) {
                if (c.contains("v3-web")) { v3 = c; break }
                if (v26 == null && c.contains("v26-web")) v26 = c
            }
            val best = when {
                v3 != null -> v3
                v26 != null -> v26Rewrite(v26)
                else -> candidates[0]
            }
            if (url == null) {
                url = best
                gearName = rate.s("gearName", "gear_name")
            }
            for (c in candidates) {
                val conv = if (c.contains("v26-web")) v26Rewrite(c) else c
                if (conv != url && conv !in backup) backup.add(conv)
            }
            if (url != null && backup.isNotEmpty()) break
        }

        if (url == null) {
            var uri = video.s("uri")
            if (uri.isEmpty()) uri = video.o("play_addr")?.s("uri") ?: ""

            val playApi = video.s("playApi").ifEmpty {
                video.o("play_addr")?.a("url_list").strings().firstOrNull() ?: ""
            }
            url = when {
                playApi.isNotEmpty() -> playApi.replace("playwm", "play")
                uri.isNotEmpty() -> buildOriginalPlayUrl(uri)
                else -> null
            }

            val urlList = video.o("play_addr")?.a("url_list").strings()
            urlList.drop(1).forEach { link ->
                val conv = link.replace("playwm", "play")
                if (conv.isNotEmpty() && conv != url && conv !in backup) backup.add(conv)
            }
        }
        return VideoPick(url, backup, gearName)
    }

    private fun extractLivePhotoVideoUrl(videoInfo: JSONObject?): String? {
        if (videoInfo == null) return null
        var live: String? = null
        var v26: String? = null

        val playAddr = videoInfo.a("playAddr").objects()
        for (a in playAddr) {
            val src = a.s("src")
            if (src.isEmpty()) continue
            if (src.contains("v3-web")) { live = src; break }
            if (v26 == null && src.contains("v26-web")) v26 = src
        }
        if (live == null && v26 != null) live = v26Rewrite(v26)
        if (live == null) {
            live = playAddr.getOrNull(1)?.s("src")?.takeIf { it.isNotEmpty() }
                ?: playAddr.getOrNull(0)?.s("src")?.takeIf { it.isNotEmpty() }
        }

        if (live == null) {
            val list = videoInfo.o("play_addr")?.a("url_list").strings()
            var v26c: String? = null
            for (c in list) {
                if (c.contains("v3-web")) { live = c; break }
                if (v26c == null && c.contains("v26-web")) v26c = c
            }
            if (live == null && v26c != null) live = v26Rewrite(v26c)
            if (live == null) live = list.getOrNull(1) ?: list.getOrNull(0)
        }

        return live ?: videoInfo.s("playApi").ifEmpty { null }
    }

    private fun pickImageUrl(img: JSONObject): String? {
        val raw = img.a("urlList").strings().firstOrNull()
            ?: img.a("url_list").strings().let { it.firstOrNull() ?: it.lastOrNull() }
            ?: ""
        return if (raw.isNotEmpty()) toHttps(raw) else null
    }

    private fun pickCover(detail: JSONObject): String? {
        val video = detail.o("video")

        if (video != null) {
            video.o("originCover")?.firstStringArray("urlList")?.firstOrNull()?.let { return it }
            video.o("origin_cover")?.firstStringArray("url_list")?.firstOrNull()?.let { return it }
            video.s("originCover").takeIf { it.isNotEmpty() }?.let { return it }
            video.a("originCoverUrlList").strings().firstOrNull()?.let { return it }

            video.o("cover")?.let { c ->
                (c.a("urlList").strings() + c.a("url_list").strings()).firstOrNull()?.let { return it }
            }
            video.s("cover").takeIf { it.isNotEmpty() }?.let { return it }
        }

        detail.o("cover")?.a("url_list").strings().firstOrNull()?.let { return it }

        if (video != null) {
            video.o("dynamicCover")?.firstStringArray("urlList")?.firstOrNull()?.let { return it }
            video.o("dynamic_cover")?.firstStringArray("url_list")?.firstOrNull()?.let { return it }
        }

        detail.o("videoInfoRes")?.a("item_list")?.objects()?.firstOrNull()
            ?.o("video")?.o("cover")?.a("url_list").strings().firstOrNull()?.let { return it }

        detail.firstObjectArray("images", "image_list").firstOrNull()
            ?.a("url_list").strings().firstOrNull()?.let { return it }

        return null
    }

    // ---------------- 组装结果 ----------------

    private fun buildResult(detail: JSONObject, awemeId: String): MediaResult {
        val title = detail.s("desc")

        val authorObj = detail.o("author") ?: JSONObject()
        val author = Author(
            name = authorObj.s("nickname"),
            id = authorObj.s("uid", "unique_id", "short_id"),
            avatar = authorObj.o("avatar_thumb")?.a("url_list").strings().firstOrNull() ?: "",
        )

        val musicObj = detail.o("music") ?: JSONObject()
        val musicPlay = musicObj.o("play_url") ?: JSONObject()
        val musicCover = musicObj.o("cover_thumb") ?: musicObj.o("cover_thumb_medium") ?: JSONObject()
        val music = Music(
            title = musicObj.s("title", "music_name"),
            author = musicObj.s("author", "owner_nickname"),
            url = toHttps(musicPlay.a("url_list").strings().firstOrNull() ?: "") ?: "",
            cover = toHttps(musicCover.a("url_list").strings().firstOrNull() ?: "") ?: "",
        )

        val video = detail.o("video")
        val durationRaw = video?.opt("duration")?.toString()?.toDoubleOrNull()
        val duration = durationRaw?.let { if (it >= 1000) round(it / 1000 * 100) / 100 else it }
        val createTime = normalizeCreateTime(detail.opt("create_time") ?: detail.opt("createTime"))

        val images = detail.firstObjectArray("images", "image_list")
        val imageUrls = mutableListOf<String>()
        val livePhotos = mutableListOf<LivePhoto>()
        val backups = mutableListOf<String>()
        var url: String? = null
        var quality = ""
        var size = 0L
        var videoId: String? = null
        var type: String

        if (images.isNotEmpty()) {
            type = "image"
            val candidates = mutableListOf<Triple<String, String, String?>>()
            for (img in images) {
                val imgUrl = pickImageUrl(img) ?: continue
                imageUrls.add(imgUrl)
                val vi = img.o("video") ?: JSONObject()
                val liveRaw = extractLivePhotoVideoUrl(vi) ?: continue
                val liveUrl = toHttps(liveRaw.replace("playwm", "play")) ?: continue
                candidates.add(Triple(imgUrl, liveUrl, extractVid(vi)))
            }

            val vidMap = resolveVidBatch(candidates.map { it.third })
            for ((imgUrl, fallback, vid) in candidates) {
                val resolved = vid?.let { vidMap[it] }?.takeIf { it.isNotEmpty() } ?: fallback
                livePhotos.add(LivePhoto(imgUrl, ensureVideoMimeType(resolved)))
            }
            if (livePhotos.isNotEmpty()) type = "live"
        } else {
            type = "video"
            val pick = extractHighestQualityVideo(detail)
            val playUri = extractVid(video)
            var main = pick.url?.let { toHttps(it.replace("playwm", "play")) }

            val original = resolveOriginalVideoUrl(playUri)
            if (original != null) {
                if (!main.isNullOrEmpty() && main != original) pick.backup.add(0, main)
                main = original
                quality = "original"
            } else if (pick.gearName.isNotEmpty()) {
                quality = pick.gearName
            }

            main = ensureVideoMimeType(main ?: "")
            if (main.isNotEmpty()) {
                url = main
                // 结果卡先显示，大小由 UI 层 MediaKit.probeSize 异步补上，不阻塞首屏
                size = 0L
            }
            for (c in pick.backup) {
                val conv = ensureVideoMimeType(toHttps(c.replace("playwm", "play")) ?: "")
                if (conv.isNotEmpty() && conv != url && conv !in backups) backups.add(conv)
            }
            videoId = playUri ?: awemeId
        }

        return MediaResult(
            type = type,
            title = title,
            author = author,
            cover = toHttps(pickCover(detail) ?: "") ?: "",
            url = url,
            quality = quality,
            duration = duration,
            size = size,
            sizeLabel = formatSizeLabel(size),
            createTime = createTime,
            publishTime = formatPublishTime(createTime),
            videoBackup = backups,
            images = imageUrls,
            livePhoto = livePhotos,
            music = music,
            videoId = videoId,
        )
    }

    private fun probeVideoSize(url: String): Long {
        if (url.isEmpty()) return 0
        val req = Request.Builder().url(url).head().header("User-Agent", DEFAULT_UA).build()
        return try {
            followClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use 0
                val ct = resp.header("Content-Type")?.lowercase(Locale.ROOT) ?: ""
                if (!ct.contains("video") && !ct.contains("octet-stream")) return@use 0
                resp.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 } ?: 0
            }
        } catch (e: Exception) {
            0
        }
    }

    // ---------------- 纯字符串工具 ----------------

    private fun toHttps(url: String): String? {
        if (url.isEmpty()) return null
        return if (url.startsWith("http://")) "https://${url.substring(7)}" else url
    }

    /** 只做字符串追加，不重写查询串——CDN 直链带签名，改顺序会失效 */
    private fun ensureVideoMimeType(url: String): String {
        if (url.isEmpty() || !Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url)) return url
        val hashIdx = url.indexOf('#')
        val base = if (hashIdx == -1) url else url.substring(0, hashIdx)
        val fragment = if (hashIdx == -1) "" else url.substring(hashIdx)
        val qIdx = base.indexOf('?')
        if (qIdx != -1) {
            for (pair in base.substring(qIdx + 1).split('&')) {
                val key = pair.substringBefore('=')
                val decoded = try {
                    java.net.URLDecoder.decode(key, "UTF-8")
                } catch (e: Exception) { key }
                if (decoded == "mime_type") return url
            }
            return "$base&mime_type=video_mp4$fragment"
        }
        return "$base?mime_type=video_mp4$fragment"
    }

    private fun normalizeCreateTime(value: Any?): Long {
        var ts = value?.toString()?.toDoubleOrNull() ?: return 0
        if (ts <= 0) return 0
        ts = if (ts >= 1e12) kotlin.math.floor(ts / 1000) else kotlin.math.floor(ts)
        return if (ts < 1e9 || ts > 4102444800) 0 else ts.toLong()
    }

    private fun formatPublishTime(createTime: Long): String {
        if (createTime == 0L) return ""
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
            fmt.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
            fmt.format(Date(createTime * 1000))
        } catch (e: Exception) {
            ""
        }
    }

    private fun formatSizeLabel(bytes: Long): String {
        if (bytes <= 0) return ""
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit++
        }
        if (unit == 0) return "${round(value).toLong()}B"
        val label = String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
        return "$label${units[unit]}"
    }
}
