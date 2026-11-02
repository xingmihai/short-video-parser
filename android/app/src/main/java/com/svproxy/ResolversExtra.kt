@file:Suppress("DEPRECATION")
package com.svproxy

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/** AI 平台 / 音乐 / 抖音主页批量 / 聚合分发 */

object DoubaoVideoResolver {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"
    private const val API = "https://www.doubao.com/creativity/share/get_video_share_info?version_code=20800&language=zh-CN&device_platform=web&aid=497858&real_aid=497858&pkg_type=release_version&device_id=&pc_version=3.8.3&region=&sys_region=&samantha_web=1&use-olympus-account=1&web_tab_id=4d5d17a6-6729-4c1e-9f55-09f093100f0a"
    private const val COOKIE = "i18next=zh-CN; ttwid=1%7CFGYNcsGXqieXVBP4QNemEXTZLZoArf0NEPXt1WhSmm8%7C1772615343%7Cc6580d4acaa52df7479b610305cc558176ba8dfcdd55bf8d9ce6fe86d3948009"
    fun parse(text: String, cookie: String): ResolveOutcome {
        val real = Http.effectiveUrl(Platform.firstUrl(text) ?: return err(400, "请输入豆包视频链接", Platform.DOUBAO_VIDEO), headers(UA))
            ?: return err(400, "请输入豆包视频链接", Platform.DOUBAO_VIDEO)
        val sid = queryOf(real, "share_id") ?: return err(400, "无法从链接提取 share_id", Platform.DOUBAO_VIDEO)
        val vid = queryOf(real, "video_id") ?: return err(400, "无法从链接提取 video_id", Platform.DOUBAO_VIDEO)
        val h = LinkedHashMap<String, String>()
        h["accept"] = "application/json, text/plain, */*"; h["accept-language"] = "zh-CN,zh;q=0.9"
        h["agw-js-conv"] = "str"; h["content-type"] = "application/json"; h["origin"] = "https://www.doubao.com"
        h["referer"] = "https://www.doubao.com/video-sharing?share_id=$sid&source_type=mobile&video_id=$vid&share_scene=video_viewer"
        h["user-agent"] = UA; h["Cookie"] = cookie.ifEmpty { COOKIE }
        val resp = Http.postJson(API, "{\"share_id\":\"$sid\",\"vid\":\"$vid\",\"creation_id\":\"\"}", h)
            ?: return err(500, "解析失败（接口无响应）", Platform.DOUBAO_VIDEO)
        val root = try { JSONObject(resp.body) } catch (e: Exception) { return err(500, "解析失败：响应不是 JSON", Platform.DOUBAO_VIDEO) }
        if (root.optInt("code", -1) != 0) return err(500, "解析失败：API 返回 code=${root.optInt("code", -1)}", Platform.DOUBAO_VIDEO)
        val data = root.obj("data") ?: root
        val urls = mutableListOf<String>(); collect(data, urls)
        val vUrl = urls.firstOrNull { it.contains(".mp4") || it.contains("video") || it.contains("vod") } ?: urls.firstOrNull()
        val vCover = urls.firstOrNull { it.contains("image") || it.contains("jpg") || it.contains("png") } ?: ""
        val vTitle = data.pick("title", "description", "prompt", "name")
        val vBackups = urls.filter { it != vUrl }.take(5)
        return ok(Platform.DOUBAO_VIDEO, listOf(m(Platform.DOUBAO_VIDEO) {
            type = "video"; title = vTitle; cover = vCover; url = vUrl; backups = vBackups
            extras = listOf("share_id" to sid, "video_id" to vid)
        }))
    }
    private fun collect(n: Any?, out: MutableList<String>) {
        when (n) {
            is JSONObject -> { val k = n.keys(); while (k.hasNext()) { val key = k.next(); val v = n.opt(key); if (v is String && v.startsWith("http") && v !in out) out.add(v) else collect(v, out) } }
            is JSONArray -> for (i in 0 until n.length()) collect(n.opt(i), out)
        }
    }
}

object DoubaoChatResolver {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"
    fun parse(text: String, cookie: String): ResolveOutcome {
        val vInputUrl = Platform.firstUrl(text) ?: return err(400, "缺少 url 参数", Platform.DOUBAO_CHAT)
        val html = Http.get(vInputUrl, headers(UA, cookie = cookie))?.body ?: return err(500, "网页内容为空，抓取失败", Platform.DOUBAO_CHAT)
        val attr = Regex("""<script[^>]*data-fn-args=(?:"([^"]*)"|'([^']*)')[^>]*>""", RegexOption.IGNORE_CASE)
        var rawArgs: String? = null
        for (mm in attr.findAll(html)) {
            val c = mm.groupValues[1].ifEmpty { mm.groupValues[2] }; if (c.isEmpty()) continue
            if (rawArgs == null) rawArgs = c
            if (mm.value.contains("modern-run-router-data-fn") && mm.value.contains("data-fn-name=\"r\"")) { rawArgs = c; break }
        }
        val decoded = (rawArgs ?: return err(404, "未找到 script[data-fn-args]", Platform.DOUBAO_CHAT))
            .replace("&quot;", "\"").replace("&#34;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        val arr = try { JSONArray(decoded) } catch (e: Exception) { return err(422, "data-fn-args 不是合法 JSON", Platform.DOUBAO_CHAT) }
        val messages = (arr.optJSONObject(2) ?: JSONObject()).pathArr("data", "message_snapshot", "message_list")
        val creations = mutableListOf<JSONObject>()
        for (i in 0 until (messages?.length() ?: 0)) fromMessage(messages!!.optJSONObject(i), creations)
        val vImgs = mutableListOf<String>(); val vVideos = mutableListOf<String>()
        for (c in creations) {
            c.obj("image")?.pathStr("image_ori_raw", "url")?.takeIf { it.isNotEmpty() && it !in vImgs }?.let { vImgs.add(it) }
            c.obj("video")?.let { v ->
                v.pick("download_url").takeIf { it.isNotEmpty() }?.let { vVideos.add(sanitize(it)) }
                val mr = v.pick("video_model")
                if (mr.isNotEmpty()) {
                    val model = try { JSONObject(mr) } catch (e: Exception) { null }
                    model?.arr("video_list")?.objs()?.forEach { variant ->
                        for (k in arrayOf("main_url", "backup_url_1")) {
                            val enc = variant.pick(k); if (enc.isEmpty()) continue
                            val plain = try { String(Base64.decode(enc, Base64.DEFAULT)) } catch (e: Exception) { "" }
                            if (plain.isNotEmpty() && !watermarked(plain)) vVideos.add(sanitize(plain))
                        }
                    }
                }
            }
        }
        val uniq = vVideos.distinct()
        if (vImgs.isEmpty() && uniq.isEmpty()) return err(404, "当前分享数据仅包含水印资源，未发现可用无水印直链", Platform.DOUBAO_CHAT)
        val vItems = mutableListOf<Media>()
        uniq.forEachIndexed { i, u -> vItems.add(m(Platform.DOUBAO_CHAT) { type = "video"; title = "生成视频 ${i + 1}"; url = u }) }
        vImgs.forEachIndexed { i, u -> vItems.add(m(Platform.DOUBAO_CHAT) { type = "image"; title = "生成图片 ${i + 1}"; url = u; cover = u; images = listOf(u) }) }
        return ok(Platform.DOUBAO_CHAT, vItems)
    }
    private fun fromMessage(msg: JSONObject?, out: MutableList<JSONObject>) {
        msg ?: return
        creations(msg, out)
        val c = msg.pick("content"); if (c.isNotEmpty()) try { creations(JSONArray(c), out) } catch (e: Exception) { }
        msg.arr("content_block")?.objs()?.forEach { b ->
            creations(b, out)
            for (k in arrayOf("content", "content_v2")) { val s = b.pick(k); if (s.isNotEmpty()) try { creations(JSONArray(s), out) } catch (e: Exception) { } }
        }
    }
    private fun creations(n: Any?, out: MutableList<JSONObject>) {
        when (n) {
            is JSONObject -> {
                n.pathArr("creation_block", "creations")?.objs()?.forEach { out.add(it) }
                n.arr("creations")?.objs()?.forEach { out.add(it) }
                val k = n.keys(); while (k.hasNext()) { val v = n.opt(k.next()); if (v is JSONObject || v is JSONArray) creations(v, out) }
            }
            is JSONArray -> for (i in 0 until n.length()) { val v = n.opt(i); if (v is JSONObject || v is JSONArray) creations(v, out) }
        }
    }
    private fun watermarked(u: String): Boolean { val l = u.lowercase(); return l.contains("video_gen_watermark") || l.contains("watermark_dyn") || l.contains("logo_type=video_gen_watermark") }
    private fun sanitize(u: String): String = try {
        val uri = Uri.parse(u)
        val kept = uri.queryParameterNames.filter { it !in setOf("lr", "logo_type", "download") }
            .joinToString("&") { "$it=${uri.getQueryParameter(it)}" }
        "${uri.scheme}://${uri.host}${uri.path}" + if (kept.isNotEmpty()) "?$kept" else ""
    } catch (e: Exception) { u }
}

object JimengResolver {
    private const val API = "https://jimeng.jianying.com/mweb/v1/get_item_info"
    fun parse(text: String, cookie: String): ResolveOutcome {
        val id = itemId(text) ?: return err(400, "链接格式错误，无法提取ID", Platform.JIMENG)
        val resp = Http.postJson(API, "{\"published_item_id\":\"$id\"}",
            mapOf("User-Agent" to Http.UA_DESKTOP, "Content-Type" to "application/json"))
            ?: return err(500, "请求失败", Platform.JIMENG)
        val root = try { JSONObject(resp.body) } catch (e: Exception) { return err(500, "JSON解析失败", Platform.JIMENG) }
        val ret = root.pick("ret")
        if (ret.isNotEmpty() && ret != "0") return err(500, "API错误：${root.pick("errmsg")}", Platform.JIMENG)
        val d = root.obj("data") ?: return err(500, "响应缺少 data", Platform.JIMENG)
        val common = d.obj("common_attr") ?: JSONObject()
        val vAuthorObj = d.obj("author") ?: JSONObject()
        val video = d.obj("video") ?: JSONObject()
        val stat = d.obj("statistic") ?: JSONObject()
        var vCover = ""
        common.obj("cover_url_map")?.let { cm ->
            val k = cm.keys(); var fb = ""
            while (k.hasNext()) { val key = k.next(); val v = cm.opt(key).svStr(); if (v.isEmpty()) continue; if (fb.isEmpty()) fb = v; if (key in setOf("1080", "720", "original")) vCover = v }
            if (vCover.isEmpty()) vCover = fb
        }
        if (vCover.isEmpty()) vCover = common.pick("cover_url")
        val tc = video.obj("transcoded_video") ?: JSONObject()
        val origin = video.obj("origin_video") ?: JSONObject()
        val main = tc.obj("origin")?.pick("video_url")?.ifEmpty { null } ?: origin.pick("video_url").ifEmpty { null }
        val vBackups = mutableListOf<String>()
        origin.pick("video_url").takeIf { it.isNotEmpty() && it != main }?.let { vBackups.add(it) }
        val tk = tc.keys(); while (tk.hasNext()) { val u = tc.obj(tk.next())?.pick("video_url") ?: ""; if (u.isNotEmpty() && u != main && u !in vBackups) vBackups.add(u) }
        val dur = video.optLong("duration_ms", 0L)
        val vUrl = main?.let { Http.toHttps(it) ?: it }
        val vCoverH = Http.toHttps(vCover) ?: vCover
        return ok(Platform.JIMENG, listOf(m(Platform.JIMENG) {
            type = "video"; title = common.pick("description"); author = vAuthorObj.pick("name")
            avatar = vAuthorObj.pick("avatar_url"); cover = vCoverH; url = vUrl
            backups = vBackups.map { Http.toHttps(it) ?: it }
            quality = if (main != null) "origin" else ""
            duration = if (dur > 0) "${dur / 1000}s" else ""
            publishTime = fmtTime(common.optLong("create_time", 0L))
            extras = listOf("item_id" to common.pick("published_item_id"), "使用数" to stat.pick("usage_num"), "播放数" to stat.pick("play_num")).filter { it.second.isNotEmpty() }
        }))
    }
    private fun itemId(text: String): String? {
        val vInputUrl = Platform.firstUrl(text)
        if (vInputUrl == null) return Regex("""^\d{16,}$""").find(text.trim())?.value
        val candidates = mutableListOf(vInputUrl)
        if (vInputUrl.contains("/s/")) Http.location(vInputUrl, headers(Http.UA_DESKTOP))?.let { candidates.add(it) }
        for (u in candidates) {
            listOf("item_id", "id").forEach { queryOf(u, it)?.let { return it } }
            try {
                val parts = (Uri.parse(u).path ?: "").trim('/').split('/').filter { it.isNotEmpty() }
                parts.lastOrNull()?.takeIf { Regex("""^\d+$""").matches(it) }?.let { return it }
            } catch (e: Exception) { }
        }
        return null
    }
}

object NeteaseResolver {
    private const val KEY = "e82ckenh8dichen8"
    private const val API = "https://interface3.music.163.com/eapi/song/enhance/player/url/v1"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/2.10.2.200154"
    private val LEVELS = mapOf("standard" to "标准音质", "exhigh" to "极高音质", "lossless" to "无损音质", "hires" to "Hires音质", "sky" to "沉浸环绕声", "jyeffect" to "高清环绕声", "jymaster" to "超清母带")
    fun parse(text: String, cookie: String, level: String): ResolveOutcome {
        val raw = Platform.firstUrl(text) ?: text.trim()
        var s = raw
        if (s.contains("163cn.tv")) s = Http.location(s, mapOf("User-Agent" to UA)) ?: s
        val vId: String = when {
            s.contains("music.163.com") && s.indexOf("id=") >= 0 ->
                s.substring(s.indexOf("id=") + 3).split('&', '#')[0]
            else -> Regex("""\d{5,}""").find(s)?.value
        } ?: return err(400, "无法提取歌曲 ID", Platform.NETEASE)
        val id = vId
        val cookies = "os=pc;appver=8.9.75;" + cookie
        val h = mapOf("User-Agent" to UA, "Referer" to "", "Cookie" to cookies)
        val resp = Http.postForm(API, mapOf("params" to encrypt(id, level)), h) ?: return err(500, "歌曲直链接口请求失败", Platform.NETEASE)
        val root = try { JSONObject(resp.body) } catch (e: Exception) { return err(500, "响应解析失败", Platform.NETEASE) }
        val first = root.arr("data")?.optJSONObject(0) ?: return err(500, "接口未返回歌曲数据（可能需要 MUSIC_U Cookie）", Platform.NETEASE)
        val vUrl = first.pick("url")
        if (vUrl.isEmpty()) return err(400, "信息获取不完整（VIP 歌曲需填 MUSIC_U Cookie）", Platform.NETEASE)
        val vSize = first.optLong("size", 0L); val realLevel = first.pick("level")
        val detail = Http.postForm("https://interface3.music.163.com/api/v3/song/detail",
            mapOf("c" to "[{\"id\":${first.pick("id").ifEmpty { id }},\"v\":0}]"), mapOf("User-Agent" to UA))
        var vName = "歌曲 $id"; var vPic = ""; var vArtist = ""; var vAlbum = ""
        try {
            val song = JSONObject(detail?.body ?: "").arr("songs")?.optJSONObject(0)
            if (song != null) {
                vName = song.pick("name"); vPic = song.pathStr("al", "picUrl"); vAlbum = song.pathStr("al", "name")
                vArtist = song.arr("ar")?.objs()?.joinToString("/") { it.pick("name") } ?: ""
            }
        } catch (e: Exception) { }
        val vQuality = LEVELS[realLevel] ?: LEVELS[level] ?: realLevel
        return ok(Platform.NETEASE, listOf(m(Platform.NETEASE) {
            type = "audio"; title = vName; author = vArtist; cover = vPic
            url = vUrl.replaceFirst("http://", "https://"); quality = vQuality
            sizeLabel = formatSize(vSize)
            extras = listOf("专辑" to vAlbum, "音质" to vQuality).filter { it.second.isNotEmpty() }
        }))
    }
    private fun encrypt(id: String, level: String): String {
        val path = "/api/song/enhance/player/url/v1"
        val config = "{\"os\": \"pc\", \"appver\": \"\", \"osver\": \"\", \"deviceId\": \"pyncm!\", \"requestId\": \"${Random.nextInt(20000000, 30000000)}\"}"
        val esc = config.replace("\\", "\\\\").replace("\"", "\\\"")
        val payload = "{\"ids\": [\"$id\"], \"level\": \"$level\", \"encodeType\": \"flac\", \"header\": \"$esc\"" +
            (if (level == "sky") ", \"immerseType\": \"c51\"" else "") + "}"
        val digest = md5("nobody${path}use${payload}md5forencrypt")
        val plain = "$path-36cd479b6b5-$payload-36cd479b6b5-$digest"
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(KEY.toByteArray(Charsets.UTF_8), "AES"))
        return hex(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }
    private fun md5(s: String) = hex(MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)))
    private fun hex(b: ByteArray): String { val sb = StringBuilder(); for (x in b) sb.append(String.format(Locale.ROOT, "%02x", x.toInt() and 0xff)); return sb.toString() }
}

object DouyinHomeResolver {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    fun parse(text: String, cookie: String, count: Int): ResolveOutcome {
        if (cookie.isBlank()) return err(400, "抖音主页批量解析需要登录 Cookie，请在「高级设置」里填写", Platform.DOUYIN_HOME)
        val short = Regex("""https?://v\.douyin\.com/[a-zA-Z0-9]+/?""").find(text)?.value
        val src = if (short != null) Http.effectiveUrl(short, headers(UA)) ?: short else text
        var secUid = Regex("""user/([^?/]+)""").find(src)?.groupValues?.get(1)
        if (secUid == null) { val t = text.trim(); if (t.length in 20..160 && !t.contains("http") && !t.contains(" ")) secUid = t }
        secUid ?: return err(400, "请提供有效的主页链接(url)或 sec_uid(id)", Platform.DOUYIN_HOME)
        val h = headers(UA, referer = "https://www.douyin.com/user/$secUid", cookie = cookie, accept = "application/json, text/plain, */*")
        val all = mutableListOf<JSONObject>()
        var cursor = 0L; var hasMore = true; var page = 0
        val maxPages = minOf(((count + 17) / 18).coerceAtLeast(1), 50)
        while (hasMore && all.size < count && page < maxPages) {
            page++
            val body = Http.get(api(secUid, cursor, minOf(18, count - all.size)), h)?.body ?: break
            val data = try { JSONObject(body) } catch (e: Exception) { break }
            val list = data.arr("aweme_list")?.objs() ?: break
            if (list.isEmpty() && all.isEmpty()) return err(500, "数据解析失败（Cookie 可能已失效）", Platform.DOUYIN_HOME)
            all.addAll(list)
            hasMore = data.optInt("has_more", 0) == 1
            cursor = data.optLong("max_cursor", 0L)
            if (!hasMore) break
            try { Thread.sleep(300) } catch (e: Exception) { }
        }
        if (all.isEmpty()) return err(500, "未获取到作品（Cookie 可能已失效或主页无公开作品）", Platform.DOUYIN_HOME)
        val vItems = all.take(count).mapIndexed { i, a -> item(a, i + 1) }
        return ok(Platform.DOUYIN_HOME, vItems, "共 ${vItems.size} 条（最多请求 $count 条，feed 接口需有效登录 Cookie）")
    }
    private fun api(secUid: String, cursor: Long, count: Int): String {
        val p = linkedMapOf(
            "device_platform" to "webapp", "aid" to "6383", "channel" to "channel_pc_web",
            "sec_user_id" to secUid, "max_cursor" to cursor.toString(), "count" to count.toString(),
            "publish_video_strategy_type" to "2", "pc_client_type" to "1", "version_code" to "170400",
            "version_name" to "17.4.0", "cookie_enabled" to "true", "screen_width" to "1920",
            "screen_height" to "1080", "browser_language" to "zh-CN", "browser_platform" to "Win32",
            "browser_name" to "Chrome", "browser_version" to "120.0.0.0", "browser_online" to "true",
            "engine_name" to "Blink", "engine_version" to "120.0.0.0", "os_name" to "Windows",
            "os_version" to "10", "cpu_core_num" to "16", "device_memory" to "8", "platform" to "PC",
            "downlink" to "10", "effective_type" to "4g", "round_trip_time" to "50",
            "webid" to "verify_${java.lang.Long.toHexString(System.nanoTime())}".take(23),
        )
        return "https://www.douyin.com/aweme/v1/web/aweme/post/?" + p.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
    }
    private fun item(a: JSONObject, index: Int): Media {
        val id = a.pick("aweme_id")
        val vImgs = a.arr("images")?.objs()?.mapNotNull { it.arr("url_list").strs().firstOrNull() } ?: emptyList()
        val isImage = vImgs.isNotEmpty()
        val dur = a.path("video")?.optLong("duration", 0L) ?: 0L
        val stat = a.obj("statistics") ?: JSONObject()
        val vCover = if (isImage) vImgs.firstOrNull() ?: "" else a.pathArr("video", "cover", "url_list").strs().firstOrNull() ?: ""
        val raw = a.pathArr("video", "play_addr", "url_list").strs().firstOrNull() ?: ""
        val vUrl = if (isImage || raw.isEmpty()) null else {
            val hash = raw.indexOf('#'); val base = if (hash < 0) raw else raw.substring(0, hash); val frag = if (hash < 0) "" else raw.substring(hash)
            if (base.contains("mime_type=")) raw else base + (if (base.contains("?")) "&" else "?") + "mime_type=video_mp4" + frag
        }
        return m(Platform.DOUYIN_HOME) {
            type = if (isImage) "image" else "video"
            title = a.pick("desc").ifEmpty { "作品 $index" }
            author = a.pathStr("author", "nickname")
            avatar = a.pathArr("author", "avatar_thumb", "url_list").strs().firstOrNull() ?: ""
            cover = vCover; url = vUrl; images = vImgs
            musicUrl = a.pathArr("music", "play_url", "url_list").strs().firstOrNull() ?: ""
            musicTitle = a.pathStr("music", "title")
            duration = if (dur > 0) "${dur / 1000}s" else ""
            publishTime = fmtTime(a.optLong("create_time", 0L))
            extras = listOf("序号" to "$index", "作品ID" to id, "点赞" to stat.pick("digg_count"), "评论" to stat.pick("comment_count"), "单作品链接" to "https://www.douyin.com/video/$id").filter { it.second.isNotEmpty() }
        }
    }
}

object AggregateResolver {
    data class Request(val text: String, val platformKey: String = "auto", val cookie: String = "", val count: Int = 18, val neteaseLevel: String = "standard")
    fun parse(req: Request): ResolveOutcome {
        val text = req.text.trim()
        if (text.isEmpty()) return ResolveOutcome.Err(400, "请输入链接或整段分享文案")
        val p = if (req.platformKey == "auto") Platform.detect(text) else Platform.fromKey(req.platformKey)
        if (p == Platform.UNKNOWN) return ResolveOutcome.Err(201, "不支持您输入的链接平台，可在「平台」里手动选择")
        return when (p) {
            Platform.DOUYIN -> douyin(text)
            Platform.DOUYIN_HOME -> DouyinHomeResolver.parse(text, req.cookie, req.count)
            Platform.KUAISHOU -> KuaishouResolver.parse(text, req.cookie)
            Platform.XIAOHONGSHU -> XhsResolver.parse(text, req.cookie)
            Platform.BILIBILI -> BilibiliResolver.parse(text, req.cookie)
            Platform.WEIBO -> WeiboResolver.parse(text, req.cookie)
            Platform.TOUTIAO -> ToutiaoResolver.parse(text, req.cookie)
            Platform.PIPIXIA -> PipixiaResolver.parse(text, req.cookie)
            Platform.PIPIGX -> PipigxResolver.parse(text, req.cookie)
            Platform.ZUIYOU -> ZuiyouResolver.parse(text, req.cookie)
            Platform.DOUBAO_VIDEO -> DoubaoVideoResolver.parse(text, req.cookie)
            Platform.DOUBAO_CHAT -> DoubaoChatResolver.parse(text, req.cookie)
            Platform.JIMENG -> JimengResolver.parse(text, req.cookie)
            Platform.NETEASE -> NeteaseResolver.parse(text, req.cookie, req.neteaseLevel)
            Platform.UNKNOWN -> ResolveOutcome.Err(201, "不支持的平台")
        }
    }
    private fun douyin(text: String): ResolveOutcome = when (val r = DouyinResolver.parse(text)) {
        is ParseResult.Err -> ResolveOutcome.Err(r.code, r.msg, Platform.DOUYIN.key)
        is ParseResult.Ok -> {
            val d = r.data
            ok(Platform.DOUYIN, listOf(m(Platform.DOUYIN) {
                type = d.type; title = d.title; author = d.author.name; avatar = d.author.avatar
                cover = d.cover; url = d.url; images = d.images; backups = d.videoBackup
                liveVideos = d.livePhoto.map { it.video }
                musicUrl = d.music.url; musicTitle = d.music.title
                duration = d.duration?.let { "${it}s" } ?: ""; quality = d.quality
                sizeLabel = d.sizeLabel; publishTime = d.publishTime
                extras = listOf("作品ID" to (d.videoId ?: ""), "作者ID" to d.author.id).filter { it.second.isNotEmpty() }
            }))
        }
    }
    fun selectable(): List<Pair<String, String>> = buildList {
        add("auto" to "自动识别")
        Platform.entries.filter { it != Platform.UNKNOWN }.forEach { add(it.key to it.label) }
    }
}
