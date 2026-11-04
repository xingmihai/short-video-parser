package com.svproxy

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.transition.TransitionManager
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import java.util.concurrent.Executors

/** 纯 Kotlin 单 Activity（Material 3 Expressive）：粘贴分享链接 → 识别平台 → 解析 → 播放 / 下载 */
class MainActivity : AppCompatActivity() {
    companion object { private const val REQ_STORAGE = 1001 }

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "sv-parse").apply { isDaemon = true } }

    private lateinit var contentRoot: LinearLayout
    private lateinit var btnClear: View
    private lateinit var fabQuick: View
    private lateinit var etInput: EditText
    private lateinit var etCookie: EditText
    private lateinit var etCount: EditText
    private lateinit var spPlatform: MaterialAutoCompleteTextView
    private lateinit var spLevel: MaterialAutoCompleteTextView
    private lateinit var btnAdvanced: Button
    private lateinit var llAdvanced: LinearLayout
    private lateinit var btnSaveToken: Button
    private lateinit var btnClearToken: Button
    private lateinit var tvTokenState: TextView
    private lateinit var btnPaste: Button
    private lateinit var btnParse: Button
    private lateinit var progress: WavyProgressView
    private lateinit var tvError: TextView
    private lateinit var resultRoot: View
    private lateinit var tvPlatform: TextView
    private lateinit var tvMeta: TextView
    private lateinit var tvNote: TextView
    private lateinit var tvCount: TextView
    private lateinit var hsItems: View
    private lateinit var llItems: LinearLayout
    private lateinit var tvTitle: TextView
    private lateinit var tvAuthor: TextView
    private lateinit var tvPublish: TextView
    private lateinit var tvAssetsLoading: TextView
    private lateinit var ivCover: ImageView
    private lateinit var tvUrlLabel: TextView
    private lateinit var tvUrl: TextView
    private lateinit var btnPlayRow: View
    private lateinit var btnPlay: Button
    private lateinit var btnDownload: Button
    private lateinit var btnCopy: Button
    private lateinit var llLive: LinearLayout
    private lateinit var tvExtra: TextView
    private lateinit var hsThumbs: View
    private lateinit var llThumbs: LinearLayout
    private lateinit var tvLive: TextView
    private lateinit var tvBackup: TextView
    private lateinit var btnMusic: Button

    private var output: ResolveOutput? = null
    private var selected = 0
    private var platformKeys: List<String> = emptyList()
    private var platformLabels: List<String> = emptyList()
    private var selectedPlatform = 0
    private var selectedLevel = 0
    private val levelKeys = listOf("standard", "exhigh", "lossless", "hires", "sky", "jyeffect", "jymaster")

    private var pendingDownload: (() -> Unit)? = null
    private var assetSeq = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 12+ 跟随壁纸动态色，低版本回落到 M3 baseline 紫
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bind()

        val entries = AggregateResolver.selectable()
        platformKeys = entries.map { it.first }
        platformLabels = entries.map { it.second }
        spPlatform.setAdapter(dropdownAdapter(platformLabels))
        spPlatform.setText(platformLabels.firstOrNull().orEmpty(), false)
        spPlatform.setOnItemClickListener { _, _, pos, _ ->
            selectedPlatform = pos
            etCookie.setText(TokenStore.get(this, currentPlatformKey()))
            updateTokenState()
        }
        selectedPlatform = 0
        etCookie.setText(TokenStore.get(this, currentPlatformKey()))
        updateTokenState()

        spLevel.setAdapter(dropdownAdapter(listOf("标准", "极高", "无损", "Hires", "沉浸环绕", "高清环绕", "超清母带")))
        spLevel.setText("标准", false)
        spLevel.setOnItemClickListener { _, _, pos, _ -> selectedLevel = pos }
        selectedLevel = 0

        btnAdvanced.setOnClickListener {
            TransitionManager.beginDelayedTransition(contentRoot)
            val show = llAdvanced.visibility == View.GONE
            llAdvanced.visibility = if (show) View.VISIBLE else View.GONE
            btnAdvanced.text = if (show) "▾ 收起高级设置" else "▸ 高级设置（Cookie / 音质 / 数量）"
        }
        btnSaveToken.setOnClickListener {
            TokenStore.save(this, currentPlatformKey(), etCookie.text.toString().trim())
            updateTokenState()
            toast("已保存到「${currentPlatformLabel()}」")
        }
        btnClearToken.setOnClickListener {
            TokenStore.clear(this, currentPlatformKey())
            etCookie.setText("")
            updateTokenState()
            toast("已清除「${currentPlatformLabel()}」的 Token")
        }
        btnClear.setOnClickListener {
            assetSeq++
            etInput.setText("")
            tvError.visibility = View.GONE
            resultRoot.visibility = View.GONE
            tvAssetsLoading.visibility = View.GONE
            output = null
        }
        val pasteAndParse = View.OnClickListener {
            val t = readClipboard()
            if (t.isEmpty()) toast("剪贴板是空的") else doParse(t)
        }
        btnPaste.setOnClickListener(pasteAndParse)
        fabQuick.setOnClickListener(pasteAndParse)
        btnParse.setOnClickListener { doParse(etInput.text.toString()) }
        btnCopy.setOnClickListener { current()?.let { v -> displayUrlOf(v)?.let { u -> copy(u); toast("直链已复制") } } }
        btnPlay.setOnClickListener { current()?.let { v -> playUrlOf(v)?.let { u -> openUrl(u) } } }
        btnDownload.setOnClickListener { current()?.let { v -> requestDownload { downloadMany(v, downloadItems(v)) } } }
        btnMusic.setOnClickListener {
            current()?.takeIf { it.musicUrl.isNotEmpty() }?.let { v ->
                requestDownload { download(v, v.musicUrl, "audio/mpeg") }
            }
        }
    }

    private fun bind() {
        contentRoot = findViewById(R.id.contentRoot)
        btnClear = findViewById(R.id.btnClear)
        fabQuick = findViewById(R.id.fabQuick)
        etInput = findViewById(R.id.etInput); etCookie = findViewById(R.id.etCookie); etCount = findViewById(R.id.etCount)
        spPlatform = findViewById(R.id.spPlatform); spLevel = findViewById(R.id.spLevel)
        btnAdvanced = findViewById(R.id.btnAdvanced); llAdvanced = findViewById(R.id.llAdvanced)
        btnSaveToken = findViewById(R.id.btnSaveToken); btnClearToken = findViewById(R.id.btnClearToken)
        tvTokenState = findViewById(R.id.tvTokenState)
        btnPaste = findViewById(R.id.btnPaste); btnParse = findViewById(R.id.btnParse)
        progress = findViewById(R.id.progress); tvError = findViewById(R.id.tvError)
        resultRoot = findViewById(R.id.resultRoot); tvPlatform = findViewById(R.id.tvPlatform)
        tvMeta = findViewById(R.id.tvMeta); tvNote = findViewById(R.id.tvNote); tvCount = findViewById(R.id.tvCount)
        hsItems = findViewById(R.id.hsItems); llItems = findViewById(R.id.llItems)
        tvTitle = findViewById(R.id.tvTitle); tvAuthor = findViewById(R.id.tvAuthor); tvPublish = findViewById(R.id.tvPublish)
        tvAssetsLoading = findViewById(R.id.tvAssetsLoading); ivCover = findViewById(R.id.ivCover)
        tvUrlLabel = findViewById(R.id.tvUrlLabel); tvUrl = findViewById(R.id.tvUrl)
        btnPlayRow = findViewById(R.id.btnPlayRow); btnPlay = findViewById(R.id.btnPlay)
        btnDownload = findViewById(R.id.btnDownload); btnCopy = findViewById(R.id.btnCopy)
        llLive = findViewById(R.id.llLive)
        tvExtra = findViewById(R.id.tvExtra); hsThumbs = findViewById(R.id.hsThumbs); llThumbs = findViewById(R.id.llThumbs)
        tvLive = findViewById(R.id.tvLive); tvBackup = findViewById(R.id.tvBackup); btnMusic = findViewById(R.id.btnMusic)
    }

    private fun currentPlatformKey() = platformKeys.getOrElse(selectedPlatform) { "auto" }
    private fun currentPlatformLabel() = platformLabels.getOrElse(selectedPlatform) { "自动识别" }

    private fun updateTokenState() {
        val key = currentPlatformKey()
        tvTokenState.text = if (TokenStore.has(this, key))
            "「${currentPlatformLabel()}」已保存 Token，切换平台会自动带出"
        else
            "「${currentPlatformLabel()}」未保存 Token；解析时会自动按平台保存"
    }

    private fun dropdownAdapter(items: List<String>) =
        object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getView(position, convertView, parent).apply {
                    val onSurface = MaterialColors.getColor(
                        context, com.google.android.material.R.attr.colorOnSurface, Color.BLACK
                    )
                    val surface = MaterialColors.getColor(
                        context, com.google.android.material.R.attr.colorSurfaceContainerHigh, Color.WHITE
                    )
                    setBackgroundColor(surface)
                    findViewById<TextView>(android.R.id.text1)?.apply {
                        setTextColor(onSurface)
                        setPadding(20, 16, 20, 16)
                    }
                }
        }

    private fun doParse(text: String) {
        val value = text.trim()
        if (value.isEmpty()) { showError("请先粘贴分享链接或整段分享文案"); return }
        etInput.setText(value)
        progress.visibility = View.VISIBLE; progress.setRunning(true)
        tvError.visibility = View.GONE; resultRoot.visibility = View.GONE
        tvAssetsLoading.visibility = View.GONE
        btnParse.isEnabled = false; btnPaste.isEnabled = false
        val cookie = etCookie.text.toString().trim()
        if (cookie.isNotEmpty()) TokenStore.save(this, currentPlatformKey(), cookie)
        val req = AggregateResolver.Request(
            text = value,
            platformKey = currentPlatformKey(),
            cookie = cookie,
            count = etCount.text.toString().toIntOrNull()?.coerceIn(1, 200) ?: 18,
            neteaseLevel = levelKeys.getOrElse(selectedLevel) { "standard" },
        )
        worker.submit {
            val r = try { AggregateResolver.parse(req) } catch (e: Exception) { ResolveOutcome.Err(-1, "解析异常：${e.message ?: e}") }
            runOnUiThread {
                progress.visibility = View.GONE; progress.setRunning(false)
                btnParse.isEnabled = true; btnPaste.isEnabled = true
                when (r) {
                    is ResolveOutcome.Ok -> {
                        // 同时按实际识别出的平台保存，下次直接选该平台即可带出
                        if (cookie.isNotEmpty()) TokenStore.save(this, r.output.platform, cookie)
                        updateTokenState()
                        render(r.output)
                    }
                    is ResolveOutcome.Err -> showError("[${r.code}] ${r.msg}")
                }
            }
        }
    }

    private fun showError(msg: String) {
        TransitionManager.beginDelayedTransition(contentRoot)
        tvError.text = msg; tvError.visibility = View.VISIBLE; resultRoot.visibility = View.GONE
        tvAssetsLoading.visibility = View.GONE
        progress.visibility = View.GONE; progress.setRunning(false)
    }

    private fun current(): Media? = output?.items?.getOrNull(selected)

    private fun render(out: ResolveOutput) {
        TransitionManager.beginDelayedTransition(contentRoot)
        output = out; selected = 0; resultRoot.visibility = View.VISIBLE
        tvPlatform.text = out.platformLabel
        if (out.note.isNotEmpty()) { tvNote.text = out.note; tvNote.visibility = View.VISIBLE } else tvNote.visibility = View.GONE
        if (out.items.size > 1) {
            tvCount.text = "共 ${out.items.size} 条，点上方标签切换"; tvCount.visibility = View.VISIBLE
            hsItems.visibility = View.VISIBLE; llItems.removeAllViews()
            out.items.forEachIndexed { i, item ->
                val chip = Chip(this).apply {
                    text = "${i + 1}. ${item.title.take(8).ifEmpty { item.type }}"
                    textSize = 11f
                    isCheckable = true
                    isChecked = i == 0
                    isClickable = true
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)
                    ).apply { marginEnd = dp(6) }
                    setOnClickListener {
                        selected = i
                        for (c in 0 until llItems.childCount) {
                            (llItems.getChildAt(c) as? Chip)?.isChecked = (c == i)
                        }
                        current()?.let { renderItem(it) }
                    }
                }
                llItems.addView(chip)
            }
        } else { tvCount.visibility = View.GONE; hsItems.visibility = View.GONE }
        // 文本、直链、按钮先显示；封面/缩略图/大小随后异步补
        out.items.firstOrNull()?.let { renderItem(it) }
    }

    private fun metaList(v: Media): List<String> {
        val meta = mutableListOf(when (v.type) { "video" -> "视频"; "image" -> "图集"; "live" -> "实况动图"; "audio" -> "音频"; else -> v.type })
        if (v.quality.isNotEmpty()) meta.add(v.quality)
        if (v.duration.isNotEmpty()) meta.add(v.duration)
        if (v.sizeLabel.isNotEmpty()) meta.add(v.sizeLabel)
        return meta
    }

    private fun displayUrlOf(v: Media): String? = when (v.type) {
        "live" -> v.liveVideos.firstOrNull() ?: v.images.firstOrNull() ?: v.url
        "image" -> v.url?.takeIf { it.isNotEmpty() } ?: v.images.firstOrNull()
        else -> v.url?.takeIf { it.isNotEmpty() }
    }

    private fun playUrlOf(v: Media): String? = when (v.type) {
        "live" -> v.liveVideos.firstOrNull()
        "image" -> null
        else -> v.url?.takeIf { it.isNotEmpty() }
    }

    /** 主下载按钮要下载的内容：动图下视频、图集下全部图片，不再只下静态图 */
    private fun downloadItems(v: Media): List<Pair<String, String>> = when (v.type) {
        "live" -> v.liveVideos.map { it to "video/mp4" }.ifEmpty { v.images.map { it to "image/jpeg" } }
        "image" -> v.images.map { it to "image/jpeg" }.ifEmpty { listOfNotNull(v.url?.let { it to "image/jpeg" }) }
        "audio" -> listOfNotNull(v.url?.let { it to "audio/mpeg" })
        else -> listOfNotNull(v.url?.let { it to "video/mp4" })
    }

    private fun renderItem(v: Media) {
        tvMeta.text = metaList(v).joinToString(" · ")
        tvTitle.text = v.title.ifEmpty { "(无标题)" }
        tvAuthor.text = if (v.author.isNotEmpty()) "@${v.author}" else ""
        tvPublish.text = v.publishTime

        // 先占位，不阻塞结果卡显示
        ivCover.visibility = if (v.cover.isNotEmpty()) View.VISIBLE else View.GONE
        ivCover.setImageBitmap(null)

        val shown = displayUrlOf(v)
        val hasUrl = !shown.isNullOrEmpty()
        tvUrlLabel.text = when (v.type) { "live" -> "动图视频直链"; "image" -> "图片直链"; else -> "无水印直链" }
        tvUrlLabel.visibility = if (hasUrl) View.VISIBLE else View.GONE
        tvUrl.visibility = if (hasUrl) View.VISIBLE else View.GONE
        tvUrl.text = shown ?: ""

        val items = downloadItems(v)
        btnPlayRow.visibility = View.VISIBLE
        (btnPlayRow as? MaterialButtonToggleGroup)?.clearChecked()
        btnPlay.visibility = if (playUrlOf(v) != null) View.VISIBLE else View.GONE
        btnDownload.visibility = if (items.isNotEmpty()) View.VISIBLE else View.GONE
        btnDownload.text = when (v.type) {
            "live" -> "下载全部动图（视频）"
            "image" -> "下载全部图片"
            "audio" -> "下载音频"
            else -> "下载"
        }
        btnDownload.isEnabled = true
        btnCopy.visibility = if (hasUrl) View.VISIBLE else View.GONE
        btnCopy.text = if (v.type == "live") "复制动图视频直链" else "复制直链"

        llLive.removeAllViews()
        if (v.liveVideos.isNotEmpty()) {
            llLive.visibility = View.VISIBLE
            v.liveVideos.forEachIndexed { i, u ->
                val b = MaterialButton(this).apply {
                    text = "动图 ${i + 1} · 下载视频"
                    textSize = 12f
                    isAllCaps = false
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(4) }
                    setOnClickListener { requestDownload { download(v, u, "video/mp4", "_live_${i + 1}") } }
                }
                llLive.addView(b)
            }
        } else llLive.visibility = View.GONE

        llThumbs.removeAllViews()
        if (v.images.isNotEmpty()) {
            hsThumbs.visibility = View.VISIBLE
            v.images.forEachIndexed { i, u ->
                val iv = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(76), dp(100)).apply { marginEnd = dp(8) }
                    scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF000000.toInt())
                    setOnClickListener { requestDownload { download(v, u, "image/jpeg", "_img_${i + 1}") } }
                }
                llThumbs.addView(iv)
            }
        } else hsThumbs.visibility = View.GONE

        if (v.liveVideos.isNotEmpty()) {
            tvLive.text = "实况动图 ${v.liveVideos.size} 组（图片 + 视频）：主按钮下载全部视频，也可逐组下载"
            tvLive.visibility = View.VISIBLE
        } else tvLive.visibility = View.GONE

        if (v.backups.isNotEmpty()) {
            tvBackup.text = "备选直链 ${v.backups.size} 条（点击复制第一条）\n" + v.backups.take(3).joinToString("\n") { it.take(90) }
            tvBackup.visibility = View.VISIBLE
            tvBackup.setOnClickListener { copy(v.backups.first()); toast("备选直链已复制") }
        } else tvBackup.visibility = View.GONE

        if (v.musicUrl.isNotEmpty()) { btnMusic.text = "♪ ${v.musicTitle.ifEmpty { "背景音乐" }} · 点击下载"; btnMusic.visibility = View.VISIBLE }
        else btnMusic.visibility = View.GONE

        if (v.extras.isNotEmpty()) { tvExtra.text = v.extras.joinToString("\n") { "${it.first}: ${it.second}" }; tvExtra.visibility = View.VISIBLE }
        else tvExtra.visibility = View.GONE

        loadAssets(v)
    }

    /** 结果卡先出文本，封面 / 缩略图 / 大小异步补；切换结果时丢弃旧回调 */
    private fun loadAssets(v: Media) {
        val seq = ++assetSeq
        val ref = MediaKit.refererFor(v.platform)
        val pending = (if (v.cover.isNotEmpty()) 1 else 0) + v.images.size
        if (pending > 0) {
            tvAssetsLoading.visibility = View.VISIBLE
            tvAssetsLoading.text = "资源加载中…（$pending）"
            var left = pending
            fun done() {
                if (seq != assetSeq) return
                left--
                tvAssetsLoading.text = if (left > 0) "资源加载中…（$left）" else "资源已加载"
                if (left <= 0) tvAssetsLoading.postDelayed({
                    if (seq == assetSeq) tvAssetsLoading.visibility = View.GONE
                }, 600)
            }
            // 延后一帧，先把结果卡绘制出来
            contentRoot.post {
                if (seq != assetSeq) return@post
                if (v.cover.isNotEmpty()) MediaKit.loadImage(v.cover, ref, ivCover) { done() }
                v.images.forEachIndexed { i, u ->
                    val iv = llThumbs.getChildAt(i) as? ImageView
                    if (iv != null) MediaKit.loadImage(u, ref, iv) { done() } else done()
                }
            }
        } else tvAssetsLoading.visibility = View.GONE

        val probe = v.url?.takeIf { it.isNotEmpty() }
        if (probe != null && v.sizeLabel.isEmpty() && v.type != "image" && v.type != "live") {
            MediaKit.probeSize(probe, ref) { label ->
                runOnUiThread {
                    if (seq != assetSeq || label.isEmpty()) return@runOnUiThread
                    v.sizeLabel = label
                    tvMeta.text = metaList(v).joinToString(" · ")
                }
            }
        }
    }

    private fun nameOf(v: Media, suffix: String = "") =
        ((v.title.takeIf { it.isNotBlank() } ?: "${v.platform}_${System.currentTimeMillis()}")
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n#]"), "_").trim().take(40)) + suffix

    private fun download(v: Media, url: String, mime: String, suffix: String = "") {
        toast("下载中…")
        MediaKit.download(this, url, nameOf(v, suffix), mime, MediaKit.refererFor(v.platform)) { result ->
            runOnUiThread {
                result.fold(
                    onSuccess = { saved ->
                        toast("已保存到 Download/${saved.displayName}，正在打开")
                        try { MediaKit.open(this@MainActivity, saved, mime) }
                        catch (e: Exception) { toast("已保存到 Download/${saved.displayName}") }
                    },
                    onFailure = { e -> toast("下载失败：${e.message ?: e}") },
                )
            }
        }
    }

    private fun downloadMany(v: Media, items: List<Pair<String, String>>) {
        if (items.isEmpty()) { toast("没有可下载的内容"); return }
        toast("下载中…共 ${items.size} 项，保存到 Download/")
        btnDownload.isEnabled = false
        var left = items.size
        var okCount = 0
        items.forEachIndexed { i, (u, m) ->
            MediaKit.download(this, u, nameOf(v, "_${i + 1}"), m, MediaKit.refererFor(v.platform)) { res ->
                runOnUiThread {
                    res.onSuccess { okCount++ }
                        .onFailure { e -> toast("第 ${i + 1} 项失败：${e.message ?: e}") }
                    left--
                    if (left == 0) {
                        btnDownload.isEnabled = true
                        toast("已保存 $okCount/${items.size} 项到 Download/")
                    }
                }
            }
        }
    }

    /** Android 8/9 写公共 Download/ 需要运行时权限；Android 10+ 走 MediaStore 不需要 */
    private fun requestDownload(block: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 29) { block(); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            block(); return
        }
        pendingDownload = block
        requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE) {
            val cb = pendingDownload
            pendingDownload = null
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) cb?.invoke()
            else toast("需要存储权限才能写入 Download/")
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
