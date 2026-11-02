@file:Suppress("DEPRECATION")
package com.svproxy

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** 通用平台解析：快手 / 小红书 / B站 / 微博 / 头条 / 皮皮虾 / 皮皮搞笑 / 最右 */
private fun M(p: Platform, b: Media.() -> Unit) = m(p, b)

object KuaishouResolver {
    private const val UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1 Edg/122.0.0.0"
    fun parse(text: String, cookie: String): ResolveOutcome {
        val raw = Platform.firstUrl(text) ?: return err(400, "未识别到有效链接", Platform.KUAISHOU)
        val finalUrl = Http.effectiveUrl(raw, headers(UA)) ?: raw
        val html = Http.get(finalUrl, headers(UA, cookie = cookie))?.body ?: return err(500, "页面内容获取失败", Platform.KUAISHOU)
        var vType = ""; var vId = ""
        for ((t, re) in arrayOf("short-video" to Regex("short-video/([^?]+)"), "long-video" to Regex("long-video/([^?]+)"), "photo" to Regex("photo/([^?]+)"))) {
            re.find(finalUrl)?.groupValues?.get(1)?.let { vType = t; vId = it; break }
        }
        if (vId.isEmpty()) return err(400, "无法识别的链接类型", Platform.KUAISHOU)
        initState(html)?.let { return it }
        apollo(html, vId, vType)?.let { return it }
        return err(404, "未找到有效媒体信息", Platform.KUAISHOU)
    }
    private fun initState(html: String): ResolveOutcome? {
        val raw = Regex("""window\.INIT_STATE\s*=\s*(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.trim()?.trimEnd(';') ?: return null
        val root = try { JSONObject(raw) } catch (e: Exception) {
            try { JSONObject(raw.replace("\\\"", "\"").replace("\\/", "/")) } catch (e2: Exception) { return null }
        }
        var photo: JSONObject? = null
        val keys = root.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (!k.startsWith("tusjoh")) continue
            val v = root.obj(k) ?: continue
            if (v.has("fid") || v.has("photo")) { photo = v.obj("photo") ?: v; break }
        }
        photo ?: return null
        val vTitle = photo.pick("caption"); val vAuthor = photo.pick("userName"); val vAvatar = photo.pick("headUrl")
        val vCover = photo.arr("coverUrls")?.objs()?.firstOrNull()?.pick("url") ?: ""
        val vMusicObj = photo.obj("music") ?: photo.obj("soundTrack") ?: JSONObject()
        val vMusic = vMusicObj.arr("audioUrls")?.objs()?.firstOrNull()?.pick("url") ?: ""
        val vMusicTitle = vMusicObj.pick("name")
        val vExtras = listOf("点赞" to photo.pick("likeCount")).filter { it.second.isNotEmpty() }

        val atlas = photo.path("ext_params", "atlas")
        val vImgs = atlas?.arr("list").strs().map { "http://tx2.a.yximgs.com/$it" }
        if (vImgs.isNotEmpty()) {
            val mp = atlas?.pick("music") ?: ""
            return ok(Platform.KUAISHOU, listOf(M(Platform.KUAISHOU) {
                type = "image"; title = vTitle; author = vAuthor; avatar = vAvatar
                cover = vImgs.first(); images = vImgs
                musicUrl = if (mp.isNotEmpty()) "http://txmov2.a.kwimgs.com$mp" else vMusic
                musicTitle = vMusicTitle; extras = vExtras + ("图片数" to "${vImgs.size}")
            }))
        }
        if (photo.pick("photoType") == "SINGLE_PICTURE" || photo.optBoolean("singlePicture", false)) {
            if (vCover.isNotEmpty()) return ok(Platform.KUAISHOU, listOf(M(Platform.KUAISHOU) {
                type = "image"; title = vTitle; author = vAuthor; avatar = vAvatar
                cover = vCover; url = vCover; images = listOf(vCover)
                musicUrl = vMusic; musicTitle = vMusicTitle; extras = vExtras
            }))
        }
        var vUrl = photo.arr("mainMvUrls")?.objs()?.firstOrNull()?.pick("url") ?: ""
        if (vUrl.isEmpty()) vUrl = photo.obj("manifest")?.arr("adaptationSet")?.objs()?.firstOrNull()
            ?.arr("representation")?.objs()?.firstOrNull()?.pick("url") ?: ""
        if (vUrl.isNotEmpty()) return ok(Platform.KUAISHOU, listOf(M(Platform.KUAISHOU) {
            type = "video"; title = vTitle; author = vAuthor; avatar = vAvatar
            cover = vCover; url = vUrl; musicUrl = vMusic; musicTitle = vMusicTitle; extras = vExtras
        }))
        return null
    }
    private fun apollo(html: String, id: String, ctype: String): ResolveOutcome? {
        var raw = Regex("""window\.__APOLLO_STATE__\s*=\s*(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return null
        raw = Regex("""function\s*\([^)]*\)\s*\{[^}]*\}""").replace(raw, ":")
        raw = Regex(""",\s*(?=\}|])""").replace(raw, "").replace(";(:());", "")
        val client = try { JSONObject(raw).obj("defaultClient") } catch (e: Exception) { null } ?: return null
        val d = client.obj("VisionVideoDetailPhoto:$id") ?: return null
        var vAuthor = ""
        val it = client.keys()
        while (it.hasNext()) { val k = it.next(); if (k.startsWith("VisionVideoDetailAuthor:")) { vAuthor = client.obj(k)?.pick("name") ?: ""; break } }
        val vUrl = if (ctype == "long-video") d.pathArr("manifestH265", "json", "adaptationSet")
            ?.objs()?.firstOrNull()?.arr("representation")?.objs()?.firstOrNull()
            ?.arr("backupUrl").strs().firstOrNull() ?: "" else d.pick("photoUrl")
        if (vUrl.isEmpty()) return null
        return ok(Platform.KUAISHOU, listOf(M(Platform.KUAISHOU) {
            type = if (ctype == "photo") "image" else "video"
            title = d.pick("caption"); author = vAuthor; cover = d.pick("coverUrl"); url = vUrl
        }))
    }
}

object XhsResolver {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36 Edg/122.0.0.0"
    private const val UA2 = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Mobile Safari/537.36 EdgA/143.0.0.0"
    fun parse(text: String, cookie: String): ResolveOutcome {
        var url = (Platform.firstUrl(text) ?: return err(400, "未识别到有效链接", Platform.XIAOHONGSHU)).replace("xhs.com", "xhslink.com")
        val host = try { Uri.parse(url).host } catch (e: Exception) { null }
        if (host != "www.xiaohongshu.com") url = Http.effectiveUrl(url, headers(UA)) ?: url
        val id = extractId(url) ?: return err(400, "链接格式错误，无法提取ID。处理后: $url", Platform.XIAOHONGSHU)
        var note = fetch(url, id, UA, cookie) ?: fetch(url, id, UA2, cookie)
        if (note == null) {
            val html = Http.get(url, headers(UA, cookie = cookie))?.body ?: ""
            val token = Regex("token=(.*?)&").find(html)?.groupValues?.get(1)
                ?: Regex("\"xsec_token\":\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            if (!token.isNullOrEmpty()) {
                val api = "https://www.xiaohongshu.com/discovery/item/$id?app_platform=android&ignoreEngage=true&app_version=8.69.5&share_from_user_hidden=true&xsec_source=app_share&type=video&xsec_token=$token"
                note = fetch(api, id, UA, cookie)
            }
        }
        note ?: return err(404, "解析失败，未找到有效内容（可能需要 Cookie / xsec_token）", Platform.XIAOHONGSHU)
        return ok(Platform.XIAOHONGSHU, listOf(format(note)))
    }
    private fun extractId(url: String): String? {
        for (r in arrayOf(Regex("discovery/item/([a-zA-Z0-9]+)"), Regex("explore/([a-zA-Z0-9]+)"), Regex("item/([a-zA-Z0-9]+)"), Regex("note/([a-zA-Z0-9]+)")))
            r.find(url)?.groupValues?.get(1)?.let { return it }
        return null
    }
    private fun fetch(url: String, id: String, ua: String, cookie: String): JSONObject? {
        val html = Http.get(url, headers(ua, cookie = cookie))?.body ?: return null
        val raw = Regex("""<script>\s*window\.__INITIAL_STATE__\s*=\s*(\{[\s\S]*?\})</script>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.replace("undefined", "null") ?: return null
        val j = try { JSONObject(raw) } catch (e: Exception) { return null }
        return j.path("note", "noteDetailMap", id, "note") ?: j.path("noteData", "data", "noteData")
    }
    private fun format(note: JSONObject): Media {
        var vType = note.pick("type").ifEmpty { "unknown" }
        if (vType == "normal") vType = "image"
        val list = note.arr("imageList").objs()
        var vCover = list.firstOrNull()?.pick("urlPre", "urlDefault", "url") ?: ""
        if (vCover.isEmpty() && vType == "video") {
            val fid = note.pathStr("video", "image", "thumbnailFileid")
            if (fid.isNotEmpty()) vCover = "https://sns-img-hw.xhscdn.com/$fid"
        }
        if (vCover.isEmpty()) vCover = note.pathStr("cover", "url")
        if (vCover.isEmpty()) { val fid = note.pathStr("cover", "fileId"); if (fid.isNotEmpty()) vCover = "https://sns-img-hw.xhscdn.com/$fid?imageView2/2/w/0/format/jpg" }
        var vUrl: String? = null
        val vBackups = mutableListOf<String>()
        if (vType == "video") {
            val streams = mutableListOf<Pair<String, JSONObject>>()
            note.path("video", "media", "stream")?.let { st -> st.arr("h265").objs().forEach { streams.add("h265" to it) }; st.arr("h264").objs().forEach { streams.add("h264" to it) } }
            streams.sortWith(compareByDescending<Pair<String, JSONObject>> { if (it.first == "h265") 1 else 0 }.thenByDescending { it.second.pick("avgBitrate", "videoBitrate").toDoubleOrNull() ?: 0.0 })
            for ((_, s) in streams) { val u = s.pick("masterUrl"); if (u.isEmpty()) continue; if (vUrl == null) vUrl = u else if (u !in vBackups) vBackups.add(u) }
            if (vUrl == null) { val k = note.pathStr("video", "consumer", "originVideoKey"); if (k.isNotEmpty()) vUrl = "http://sns-video-bd.xhscdn.com/$k" }
        }
        val vImgs = mutableListOf<String>(); val vLive = mutableListOf<String>()
        for (img in list) {
            val u = img.pick("url", "urlDefault", "urlPre")
            if (u.isNotEmpty()) vImgs.add(imgUrl(u))
            val lv = img.path("stream", "h264")?.objs()?.firstOrNull()?.pick("masterUrl")
                ?: img.path("stream", "h265")?.objs()?.firstOrNull()?.pick("masterUrl") ?: ""
            if (lv.isNotEmpty()) vLive.add(lv)
        }
        if (vLive.isNotEmpty()) vType = "live"
        return M(Platform.XIAOHONGSHU) {
            type = vType; title = note.pick("title")
            author = note.pathStr("user", "nickname").ifEmpty { note.pathStr("user", "nickName") }
            avatar = note.pathStr("user", "avatar"); cover = imgUrl(vCover)
            url = vUrl; images = vImgs; backups = vBackups; liveVideos = vLive
            extras = listOf("简介" to note.pick("desc")).filter { it.second.isNotEmpty() }
        }
    }
    private fun imgUrl(u: String): String {
        if (u.isEmpty()) return ""
        if (!u.startsWith("http") && u.contains("3http")) return Regex("(?=3http)").split(u).joinToString("") { imgUrl(it.removePrefix("3")) }
        Regex("""/oss-sg/([a-zA-Z0-9_]+)/([a-zA-Z0-9]+)!""").find(u)?.let {
            val d = it.groupValues[1]; if (!Regex("^[a-f0-9]{32}$").matches(d) && d.toLongOrNull() == null) return "https://sns-img-hw.xhscdn.com/oss-sg/$d/${it.groupValues[2]}?imageView2/2/w/0/format/jpg"
        }
        Regex("""/([a-zA-Z0-9_]+)/([a-zA-Z0-9]+)!""").find(u)?.let {
            val d = it.groupValues[1]; if (!Regex("^[a-f0-9]{32}$").matches(d) && d.toLongOrNull() == null) return "https://sns-img-hw.xhscdn.com/$d/${it.groupValues[2]}?imageView2/2/w/0/format/jpg"
        }
        Regex("""(notes_pre_post|spectrum|notes_uhdr)/([a-zA-Z0-9]+)""").find(u)?.let { return "https://sns-img-hw.xhscdn.com/${it.groupValues[1]}/${it.groupValues[2]}?imageView2/2/w/0/format/jpg" }
        Regex("""/([a-zA-Z0-9]+)!""").find(u)?.let { return "https://ci.xiaohongshu.com/${it.groupValues[1]}?imageView2/2/w/0/format/jpg" }
        return u
    }
}

object BilibiliResolver {
    private const val UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_14_6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4606.81 Safari/537.36"
    fun parse(text: String, cookie: String): ResolveOutcome {
        val raw = Platform.firstUrl(text) ?: return err(201, "链接不能为空", Platform.BILIBILI)
        val clean = try { val u = Uri.parse(raw); "${u.scheme ?: "https"}://${u.host}${decode(u.path ?: "").trimEnd('/')}" } catch (e: Exception) { raw }
        val bvid = bv(clean) ?: return err(-1, "视频链接好像不太对", Platform.BILIBILI)
        val h = headers(UA, cookie = cookie, accept = "application/json, text/plain, */*")
        val view = Http.get("https://api.bilibili.com/x/web-interface/view?bvid=$bvid", h)?.body ?: return err(500, "请求视频信息失败", Platform.BILIBILI)
        val vj = try { JSONObject(view) } catch (e: Exception) { return err(500, "响应解析失败", Platform.BILIBILI) }
        if (vj.optInt("code", -1) != 0) return err(0, "解析失败（接口返回 ${vj.optInt("code", -1)}）", Platform.BILIBILI)
        val d = vj.obj("data") ?: return err(500, "响应缺少 data", Platform.BILIBILI)
        val pages = d.arr("pages").objs()
        if (pages.isEmpty()) return err(404, "没有分P信息", Platform.BILIBILI)
        val vItems = mutableListOf<Media>()
        for ((i, page) in pages.withIndex()) {
            val cid = page.optLong("cid", 0L)
            val api = "https://api.bilibili.com/x/player/playurl?otype=json&fnver=0&fnval=3&player=3&qn=112&bvid=$bvid&cid=$cid&platform=html5&high_quality=1"
            val body = Http.get(api, h)?.body ?: continue
            val rawUrl = try { JSONObject(body).pathArr("data", "durl")?.objs()?.firstOrNull()?.pick("url") ?: "" } catch (e: Exception) { "" }
            val vUrl = if (rawUrl.isNotEmpty()) Regex("""^https?://[^/]+/""").replaceFirst(rawUrl, "https://upos-sz-mirrorhw.bilivideo.com/") else ""
            val sec = page.optLong("duration", 0L)
            val vDur = if (sec <= 0) "" else { val hh = sec / 3600; val mm = (sec % 3600) / 60; val ss = sec % 60; if (hh > 0) String.format("%d:%02d:%02d", hh, mm, ss) else String.format("%02d:%02d", mm, ss) }
            val vTitle = if (pages.size > 1) "${d.pick("title")} · P${i + 1} ${page.pick("part")}" else d.pick("title")
            vItems.add(M(Platform.BILIBILI) {
                type = "video"; title = vTitle; author = d.pathStr("owner", "name")
                avatar = d.pathStr("owner", "face"); cover = d.pick("pic"); url = vUrl.ifEmpty { null }
                duration = vDur; extras = listOf("简介" to d.pick("desc"), "分P" to "${pages.size}").filter { it.second.isNotEmpty() }
            })
        }
        if (vItems.isEmpty()) return err(404, "无法获取视频直链", Platform.BILIBILI)
        return ok(Platform.BILIBILI, vItems, if (cookie.isEmpty()) "未填 Cookie，通常只能拿到 360P/480P；填 SESSDATA 可解锁更高画质" else "")
    }
    private fun bv(url: String): String? {
        val u = Uri.parse(url)
        var p = u.path ?: ""
        if (u.host == "b23.tv") p = Uri.parse(Http.location(url, headers(UA)) ?: return null).path ?: ""
        else if (u.host != "www.bilibili.com" && u.host != "m.bilibili.com") {
            return Regex("""(BV[a-zA-Z0-9]{10})""").find(Http.get(url, headers(UA))?.body ?: return null)?.groupValues?.get(1)
        }
        if (!p.contains("/video/")) return null
        return p.replace("/video/", "").trimEnd('/').ifEmpty { null }
    }
}

object WeiboResolver {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
    fun parse(text: String, cookie: String): ResolveOutcome {
        val url = Platform.firstUrl(text) ?: return err(400, "未识别到有效链接", Platform.WEIBO)
        val id = videoId(url) ?: return err(404, "无法从URL中提取视频ID: $url", Platform.WEIBO)
        val api = "https://weibo.com/tv/api/component?page=" + java.net.URLEncoder.encode("/tv/show/$id", "UTF-8")
        val resp = Http.postForm(api, mapOf("data" to "{\"Component_Play_Playinfo\":{\"oid\":\"$id\"}}"),
            headers(UA, referer = "https://weibo.com/", cookie = cookie, accept = "application/json, text/plain, */*"))
            ?: return err(500, "API请求失败", Platform.WEIBO)
        val j = try { JSONObject(resp.body) } catch (e: Exception) { return err(500, "API响应解析失败", Platform.WEIBO) }
        if (j.optInt("code", -1) != 100000) return err(404, "官方接口响应错误或视频不存在", Platform.WEIBO)
        val info = j.path("data", "Component_Play_Playinfo") ?: return err(404, "缺少 Component_Play_Playinfo", Platform.WEIBO)
        val vAll = mutableListOf<String>(); var vBest: String? = null; var vPri = -1; var vLabel = ""
        info.obj("urls")?.let { urls ->
            val k = urls.keys()
            while (k.hasNext()) {
                val label = k.next(); val v = urls.opt(label).svStr(); if (v.isEmpty()) continue
                val full = "https:" + v.removePrefix("https:").removePrefix("http:")
                val pri = when { label.contains("2K") -> 4; label.contains("1080P") -> 3; label.contains("720P") -> 2; label.contains("480P") -> 1; else -> 0 }
                if (full !in vAll) vAll.add(full)
                if (pri > vPri) { vPri = pri; vBest = full; vLabel = label }
            }
        }
        val secs = info.opt("duration_time").svStr().toLongOrNull() ?: 0L
        fun fix(s: String) = if (s.isEmpty()) "" else "https:" + s.removePrefix("https:").removePrefix("http:")
        val vAv = fix(info.pick("avatar")); val vCv = fix(info.pick("cover_image"))
        val vBackups = vAll.filter { it != vBest }
        val vExtras = listOf("播放" to info.pick("play_count"), "转发" to info.pick("reposts_count"), "评论" to info.pick("comments_count"), "点赞" to info.pick("attitudes_count"), "IP" to info.pick("ip_info_str"), "日期" to info.pick("date")).filter { it.second.isNotEmpty() }
        return ok(Platform.WEIBO, listOf(M(Platform.WEIBO) {
            type = "video"; title = info.pick("title"); author = info.pick("author")
            avatar = vAv; cover = vCv; url = vBest; backups = vBackups; quality = vLabel
            duration = if (secs > 0) "${secs}s" else ""; extras = vExtras
        }))
    }
    private fun videoId(url: String): String? {
        if (url.contains("video.weibo.com/show") || url.contains("weibo.com/tv/")) {
            queryOf(url, "fid")?.takeIf { it.isNotEmpty() }?.let { return it }
            Regex("""weibo\.com/tv/(show|v)/([^?&]+)""").find(url)?.groupValues?.get(2)?.let { return it }
        }
        if (url.contains("t.cn/")) return Http.location(url, headers(UA))?.let { videoId(it) }
        return Regex("""\d+:\d+""").find(url)?.value
    }
}

object ToutiaoResolver {
    private const val UA = "Mozilla/5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/95.0.4638.69 Safari/537.36"
    fun parse(text: String, cookie: String): ResolveOutcome {
        val url = Platform.firstUrl(text) ?: return err(201, "url为空", Platform.TOUTIAO)
        val id = Regex("""[0-9]+|(?<=video/)[0-9]+""").find(Http.location(url, headers(UA)) ?: url)?.value
            ?: return err(400, "无法解析视频 ID", Platform.TOUTIAO)
        val html = Http.get("https://www.toutiao.com/video/$id", headers(UA, cookie = cookie))?.body ?: return err(404, "无法找到数据", Platform.TOUTIAO)
        val start = "<script id=\"RENDER_DATA\" type=\"application/json\">"
        val i = html.indexOf(start); if (i < 0) return err(404, "无法找到 RENDER_DATA", Platform.TOUTIAO)
        val j = html.indexOf("</script>", i + start.length); if (j < 0) return err(404, "未找到结束标签", Platform.TOUTIAO)
        val root = try { JSONObject(decode(html.substring(i + start.length, j))) } catch (e: Exception) { return err(404, "JSON解析失败", Platform.TOUTIAO) }
        val data = root.obj("data") ?: return err(404, "当前分享链接已失效", Platform.TOUTIAO)
        val init = data.obj("initialVideo") ?: return err(404, "响应缺少 initialVideo", Platform.TOUTIAO)
        val list = init.pathArr("videoPlayInfo", "video_list")?.objs() ?: emptyList()
        val vUrl = (list.getOrNull(2)?.pick("main_url")?.ifEmpty { null } ?: list.getOrNull(1)?.pick("main_url") ?: "").ifEmpty { null }
        val user = init.path("itemCell", "userInfo")
        val vMusic = init.pathStr("itemCell", "videoAbility", "music")
        return ok(Platform.TOUTIAO, listOf(M(Platform.TOUTIAO) {
            type = "video"; title = init.pick("title"); author = user?.pick("name") ?: ""
            avatar = user?.pick("avatarURL") ?: ""; cover = init.pick("coverUrl")
            url = vUrl; musicUrl = vMusic
            extras = listOf("itemId" to data.pick("itemId"), "简介" to (user?.pick("description") ?: "")).filter { it.second.isNotEmpty() }
        }), if (cookie.isEmpty()) "页面 Cookie 可能需更新，解析失败可在高级设置里填" else "")
    }
}

object PipixiaResolver {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
    fun parse(text: String, cookie: String): ResolveOutcome {
        val raw = Platform.firstUrl(text) ?: return err(201, "链接不能为空", Platform.PIPIXIA)
        val url = Http.effectiveUrl(Http.effectiveUrl(raw, headers(UA)) ?: raw, headers(UA)) ?: raw
        val id = Regex("""item/(.*)\?""").find(url)?.groupValues?.get(1) ?: return err(404, "无法从 URL 中提取视频 ID", Platform.PIPIXIA)
        val body = Http.get("https://h5.pipix.com/bds/cell/cell_h5_comment/?count=5&aid=1319&app_name=super&cell_id=$id", headers(UA))?.body
            ?: return err(404, "API请求失败", Platform.PIPIXIA)
        val root = try { JSONObject(body) } catch (e: Exception) { return err(404, "JSON 解析失败", Platform.PIPIXIA) }
        val comments = root.pathArr("data", "cell_comments")
        val item = comments?.optJSONObject(1)?.path("comment_info", "item") ?: return err(404, "解析失败，未找到所需数据", Platform.PIPIXIA)
        val vUrl = item.pathArr("video", "video_high", "url_list")?.objs()?.firstOrNull()?.pick("url") ?: ""
        val vImgs = item.path("note")?.arr("multi_image")?.objs()?.mapNotNull { it.arr("url_list")?.objs()?.firstOrNull()?.pick("url")?.takeIf { u -> u.isNotEmpty() } } ?: emptyList()
        val vCover = item.pathArr("cover", "url_list")?.objs()?.firstOrNull()?.pick("url") ?: ""
        val vTitle = item.pick("content").ifEmpty { comments?.optJSONObject(1)?.pathStr("comment_info", "text") ?: "" }
        return ok(Platform.PIPIXIA, listOf(M(Platform.PIPIXIA) {
            type = if (vUrl.isEmpty() && vImgs.isNotEmpty()) "image" else "video"
            title = vTitle; author = item.pathStr("author", "name")
            avatar = item.pathArr("author", "avatar", "download_list")?.objs()?.firstOrNull()?.pick("url") ?: ""
            cover = vCover; url = vUrl.ifEmpty { null }; images = vImgs
        }))
    }
}

object PipigxResolver {
    fun parse(text: String, cookie: String): ResolveOutcome {
        val url = Platform.firstUrl(text) ?: return err(400, "未提供 url 参数", Platform.PIPIGX)
        val pid = queryOf(url, "pid") ?: return err(400, "提取参数出错：缺 pid", Platform.PIPIGX)
        val mid = queryOf(url, "mid") ?: return err(400, "提取参数出错：缺 mid", Platform.PIPIGX)
        val payload = "{\"pid\":${pid.toLongOrNull() ?: 0},\"mid\":${mid.toLongOrNull() ?: 0},\"type\":\"post\"}"
        val resp = Http.postJson("https://h5.pipigx.com/ppapi/share/fetch_content", payload,
            mapOf("Content-Type" to "application/json", "User-Agent" to Http.UA_DESKTOP))
            ?: return err(500, "请求发生错误", Platform.PIPIGX)
        if (resp.code >= 400) return err(resp.code, "HTTP 状态码 ${resp.code}", Platform.PIPIGX)
        val post = try { JSONObject(resp.body).path("data", "post") } catch (e: Exception) { null }
            ?: return err(500, "响应缺少 data.post", Platform.PIPIGX)
        val videos = post.arr("videos").objs()
        val thumb = videos.firstOrNull()?.pick("thumb") ?: ""
        val vUrl = videos.firstOrNull()?.pick("url") ?: ""
        val vCover = if (thumb.isNotEmpty()) "https://file.ippzone.com/img/frame/id/$thumb" else ""
        return ok(Platform.PIPIGX, listOf(M(Platform.PIPIGX) {
            type = "video"; title = post.pick("content"); cover = vCover; url = vUrl.ifEmpty { null }
        }))
    }
}

object ZuiyouResolver {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
    fun parse(text: String, cookie: String): ResolveOutcome {
        val raw = Platform.firstUrl(text) ?: return err(201, "请输入url", Platform.ZUIYOU)
        var pid = queryOf(raw, "pid")
        if (pid.isNullOrEmpty()) pid = queryOf(Http.location(raw, headers(UA)) ?: raw, "pid")
        if (pid.isNullOrEmpty()) return err(201, "找不到有效的 pid 参数（含重定向后）", Platform.ZUIYOU)
        val resp = Http.postJson("https://share.xiaochuankeji.cn/planck/share/post/detail_h5",
            "{\"pid\":${pid.toLongOrNull() ?: 0},\"h_av\":\"5.2.13.011\"}",
            mapOf("Content-Type" to "application/json", "Accept" to "application/json", "User-Agent" to UA))
            ?: return err(500, "API请求失败", Platform.ZUIYOU)
        val root = try { JSONObject(resp.body) } catch (e: Exception) { return err(500, "API响应解析失败", Platform.ZUIYOU) }
        val post = root.path("data", "post") ?: return err(500, "响应缺少 data.post", Platform.ZUIYOU)
        val member = post.obj("member") ?: JSONObject()
        val firstImg = post.arr("imgs")?.objs()?.firstOrNull()
        val vid = firstImg?.pick("id") ?: return err(201, "找不到有效的 vid", Platform.ZUIYOU)
        val vUrl = post.obj("videos")?.obj(vid)?.pick("url") ?: ""
        val vCover = firstImg?.pathArr("urls", "540_webp", "urls").strs().firstOrNull() ?: ""
        return ok(Platform.ZUIYOU, listOf(M(Platform.ZUIYOU) {
            type = "video"; title = post.pick("content"); author = member.pick("name")
            avatar = member.pathArr("avatar_urls", "origin", "urls").strs().firstOrNull() ?: ""
            cover = vCover; url = vUrl.ifEmpty { null }
        }))
    }
}
