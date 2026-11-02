package com.svproxy

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/** 纯 Kotlin 单 Activity：粘贴分享链接 → 自动识别平台 → 解析 → 播放 / 下载 */
class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "sv-parse").apply { isDaemon = true } }

    private lateinit var etInput: EditText
    private lateinit var etCookie: EditText
    private lateinit var etCount: EditText
    private lateinit var spPlatform: Spinner
    private lateinit var spLevel: Spinner
    private lateinit var btnAdvanced: Button
    private lateinit var llAdvanced: LinearLayout
    private lateinit var btnPaste: Button
    private lateinit var btnParse: Button
    private lateinit var progress: ProgressBar
    private lateinit var tvError: TextView
    private lateinit var resultRoot: LinearLayout
    private lateinit var tvPlatform: TextView
    private lateinit var tvMeta: TextView
    private lateinit var tvNote: TextView
    private lateinit var tvCount: TextView
    private lateinit var hsItems: View
    private lateinit var llItems: LinearLayout
    private lateinit var tvTitle: TextView
    private lateinit var tvAuthor: TextView
    private lateinit var tvPublish: TextView
    private lateinit var ivCover: ImageView
    private lateinit var tvUrlLabel: TextView
    private lateinit var tvUrl: TextView
    private lateinit var btnPlayRow: View
    private lateinit var btnPlay: Button
    private lateinit var btnDownload: Button
    private lateinit var btnCopy: Button
    private lateinit var tvExtra: TextView
    private lateinit var hsThumbs: View
    private lateinit var llThumbs: LinearLayout
    private lateinit var tvLive: TextView
    private lateinit var tvBackup: TextView
    private lateinit var btnMusic: Button

    private var output: ResolveOutput? = null
    private var selected = 0
    private var platformKeys: List<String> = emptyList()
    private val levelKeys = listOf("standard", "exhigh", "lossless", "hires", "sky", "jyeffect", "jymaster")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bind()
        val entries = AggregateResolver.selectable()
        platformKeys = entries.map { it.first }
        spPlatform.adapter = darkAdapter(entries.map { it.second })
        spLevel.adapter = darkAdapter(listOf("标准", "极高", "无损", "Hires", "沉浸环绕", "高清环绕", "超清母带"))
        spLevel.setSelection(0)

        btnAdvanced.setOnClickListener {
            val show = llAdvanced.visibility == View.GONE
            llAdvanced.visibility = if (show) View.VISIBLE else View.GONE
            btnAdvanced.text = if (show) "▾ 收起高级设置" else "▸ 高级设置（Cookie / 音质 / 数量）"
        }
        btnPaste.setOnClickListener { val t = readClipboard(); if (t.isEmpty()) toast("剪贴板是空的") else doParse(t) }
        btnParse.setOnClickListener { doParse(etInput.text.toString()) }
        btnCopy.setOnClickListener { current()?.url?.let { copy(it); toast("直链已复制") } }
        btnPlay.setOnClickListener { current()?.url?.let { openUrl(it) } }
        btnDownload.setOnClickListener { current()?.let { v -> v.url?.let { u -> download(v, u, mimeOf(v)) } } }
        btnMusic.setOnClickListener { current()?.takeIf { it.musicUrl.isNotEmpty() }?.let { download(it, it.musicUrl, "audio/mpeg") } }
    }

    private fun bind() {
        etInput = findViewById(R.id.etInput); etCookie = findViewById(R.id.etCookie); etCount = findViewById(R.id.etCount)
        spPlatform = findViewById(R.id.spPlatform); spLevel = findViewById(R.id.spLevel)
        btnAdvanced = findViewById(R.id.btnAdvanced); llAdvanced = findViewById(R.id.llAdvanced)
        btnPaste = findViewById(R.id.btnPaste); btnParse = findViewById(R.id.btnParse)
        progress = findViewById(R.id.progress); tvError = findViewById(R.id.tvError)
        resultRoot = findViewById(R.id.resultRoot); tvPlatform = findViewById(R.id.tvPlatform)
        tvMeta = findViewById(R.id.tvMeta); tvNote = findViewById(R.id.tvNote); tvCount = findViewById(R.id.tvCount)
        hsItems = findViewById(R.id.hsItems); llItems = findViewById(R.id.llItems)
        tvTitle = findViewById(R.id.tvTitle); tvAuthor = findViewById(R.id.tvAuthor); tvPublish = findViewById(R.id.tvPublish)
        ivCover = findViewById(R.id.ivCover); tvUrlLabel = findViewById(R.id.tvUrlLabel); tvUrl = findViewById(R.id.tvUrl)
        btnPlayRow = findViewById(R.id.btnPlayRow); btnPlay = findViewById(R.id.btnPlay)
        btnDownload = findViewById(R.id.btnDownload); btnCopy = findViewById(R.id.btnCopy)
        tvExtra = findViewById(R.id.tvExtra); hsThumbs = findViewById(R.id.hsThumbs); llThumbs = findViewById(R.id.llThumbs)
        tvLive = findViewById(R.id.tvLive); tvBackup = findViewById(R.id.tvBackup); btnMusic = findViewById(R.id.btnMusic)
    }

    private fun darkAdapter(items: List<String>) = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            super.getView(position, convertView, parent).apply { findViewById<TextView>(android.R.id.text1)?.setTextColor(0xFFE6E8EC.toInt()) }
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            super.getDropDownView(position, convertView, parent).apply {
                setBackgroundColor(0xFF1F2937.toInt())
                findViewById<TextView>(android.R.id.text1)?.setTextColor(0xFFE6E8EC.toInt())
            }
    }

    private fun doParse(text: String) {
        val value = text.trim()
        if (value.isEmpty()) { showError("请先粘贴分享链接或整段分享文案"); return }
        etInput.setText(value)
        progress.visibility = View.VISIBLE; tvError.visibility = View.GONE; resultRoot.visibility = View.GONE
        btnParse.isEnabled = false; btnPaste.isEnabled = false
        val req = AggregateResolver.Request(
            text = value,
            platformKey = platformKeys.getOrElse(spPlatform.selectedItemPosition) { "auto" },
            cookie = etCookie.text.toString().trim(),
            count = etCount.text.toString().toIntOrNull()?.coerceIn(1, 200) ?: 18,
            neteaseLevel = levelKeys.getOrElse(spLevel.selectedItemPosition) { "standard" },
        )
        worker.submit {
            val r = try { AggregateResolver.parse(req) } catch (e: Exception) { ResolveOutcome.Err(-1, "解析异常：${e.message ?: e}") }
            runOnUiThread {
                progress.visibility = View.GONE; btnParse.isEnabled = true; btnPaste.isEnabled = true
                when (r) {
                    is ResolveOutcome.Ok -> render(r.output)
                    is ResolveOutcome.Err -> showError("[${r.code}] ${r.msg}")
                }
            }
        }
    }

    private fun showError(msg: String) { tvError.text = msg; tvError.visibility = View.VISIBLE; resultRoot.visibility = View.GONE }
    private fun current(): Media? = output?.items?.getOrNull(selected)

    private fun render(out: ResolveOutput) {
        output = out; selected = 0; resultRoot.visibility = View.VISIBLE
        tvPlatform.text = out.platformLabel
        if (out.note.isNotEmpty()) { tvNote.text = out.note; tvNote.visibility = View.VISIBLE } else tvNote.visibility = View.GONE
        if (out.items.size > 1) {
            tvCount.text = "共 ${out.items.size} 条，点上方按钮切换"; tvCount.visibility = View.VISIBLE
            hsItems.visibility = View.VISIBLE; llItems.removeAllViews()
            out.items.forEachIndexed { i, item ->
                val b = Button(this).apply {
                    text = "${i + 1}. ${item.title.take(8).ifEmpty { item.type }}"; textSize = 11f; isAllCaps = false
                    setTextColor(0xFFD1D5DB.toInt())
                    background = resources.getDrawable(R.drawable.bg_btn_ghost, null)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)).apply { marginEnd = dp(6) }
                    setOnClickListener { selected = i; current()?.let { renderItem(it) } }
                }
                llItems.addView(b)
            }
        } else { tvCount.visibility = View.GONE; hsItems.visibility = View.GONE }
        out.items.firstOrNull()?.let { renderItem(it) }
    }

    private fun renderItem(v: Media) {
        val meta = mutableListOf(when (v.type) { "video" -> "视频"; "image" -> "图集"; "live" -> "实况"; "audio" -> "音频"; else -> v.type })
        if (v.quality.isNotEmpty()) meta.add(v.quality)
        if (v.duration.isNotEmpty()) meta.add(v.duration)
        if (v.sizeLabel.isNotEmpty()) meta.add(v.sizeLabel)
        tvMeta.text = meta.joinToString(" · ")
        tvTitle.text = v.title.ifEmpty { "(无标题)" }
        tvAuthor.text = if (v.author.isNotEmpty()) "@${v.author}" else ""
        tvPublish.text = v.publishTime
        val ref = Media.refererFor(v.platform)
        ivCover.visibility = if (v.cover.isNotEmpty()) View.VISIBLE else View.GONE
        if (v.cover.isNotEmpty()) { ivCover.setImageBitmap(null); Media.loadImage(v.cover, ref, ivCover) }

        val vis = if (v.url.isNullOrEmpty()) View.GONE else View.VISIBLE
        tvUrlLabel.visibility = vis; tvUrl.visibility = vis; tvUrl.text = v.url ?: ""
        btnPlayRow.visibility = vis; btnCopy.visibility = vis
        btnDownload.text = when (v.type) { "audio" -> "下载音频"; "image" -> "下载图片"; else -> "下载" }
        btnDownload.isEnabled = true

        llThumbs.removeAllViews()
        if (v.images.isNotEmpty()) {
            hsThumbs.visibility = View.VISIBLE
            v.images.forEachIndexed { i, u ->
                val iv = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(76), dp(100)).apply { marginEnd = dp(8) }
                    scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF000000.toInt())
                    setOnClickListener { download(v, u, "image/jpeg", "_${i + 1}") }
                }
                Media.loadImage(u, ref, iv); llThumbs.addView(iv)
            }
        } else hsThumbs.visibility = View.GONE

        if (v.liveVideos.isNotEmpty()) { tvLive.text = "实况 Live Photo：${v.liveVideos.size} 组（图片 + 视频）"; tvLive.visibility = View.VISIBLE }
        else tvLive.visibility = View.GONE

        if (v.backups.isNotEmpty()) {
            tvBackup.text = "备选直链 ${v.backups.size} 条（点击复制第一条）\n" + v.backups.take(3).joinToString("\n") { it.take(90) }
            tvBackup.visibility = View.VISIBLE
            tvBackup.setOnClickListener { copy(v.backups.first()); toast("备选直链已复制") }
        } else tvBackup.visibility = View.GONE

        if (v.musicUrl.isNotEmpty()) { btnMusic.text = "♪ ${v.musicTitle.ifEmpty { "背景音乐" }} · 点击下载"; btnMusic.visibility = View.VISIBLE }
        else btnMusic.visibility = View.GONE

        if (v.extras.isNotEmpty()) { tvExtra.text = v.extras.joinToString("\n") { "${it.first}: ${it.second}" }; tvExtra.visibility = View.VISIBLE }
        else tvExtra.visibility = View.GONE
    }

    private fun mimeOf(v: Media) = when (v.type) { "audio" -> "audio/mpeg"; "image" -> "image/jpeg"; else -> "video/mp4" }
    private fun nameOf(v: Media, suffix: String = "") =
        ((v.title.takeIf { it.isNotBlank() } ?: "${v.platform}_${System.currentTimeMillis()}")
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n#]"), "_").trim().take(40)) + suffix

    private fun download(v: Media, url: String, mime: String, suffix: String = "") {
        toast("下载中…"); btnDownload.isEnabled = false
        Media.download(this, url, nameOf(v, suffix), mime, Media.refererFor(v.platform)) { result ->
            runOnUiThread {
                btnDownload.isEnabled = true
                result.fold(
                    onSuccess = { f ->
                        toast("已保存 ${String.format("%.1f", f.length() / 1048576.0)} MB，正在打开")
                        try { Media.open(this, f, mime) } catch (e: Exception) { toast("已保存到 ${f.absolutePath}，但没有可用播放器") }
                    },
                    onFailure = { e -> toast("下载失败：${e.message ?: e}") },
                )
            }
        }
    }

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { toast("无法打开，试试「下载」") }
    }
    private fun readClipboard(): String = try {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val c = cm.primaryClip
        if (c != null && c.itemCount > 0) c.getItemAt(0).text?.toString() ?: "" else ""
    } catch (e: Exception) { "" }
    private fun copy(text: String) {
        try { (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("sv", text)) }
        catch (e: Exception) { toast("复制失败") }
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    override fun onDestroy() { super.onDestroy(); worker.shutdownNow() }
}
