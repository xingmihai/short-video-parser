package com.svproxy

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * 纯 Kotlin 单 Activity 界面：粘贴抖音分享链接 → 解析 → 播放 / 下载。
 *
 * 没有任何 JS 引擎、没有 React Native、没有 index.android.bundle。
 * 解析在后台线程跑 DouyinResolver（OkHttp + org.json），结果回主线程渲染。
 */
class MainActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "sv-parse").apply { isDaemon = true } }

    private lateinit var etInput: EditText
    private lateinit var btnPaste: Button
    private lateinit var btnParse: Button
    private lateinit var progress: ProgressBar
    private lateinit var tvError: TextView
    private lateinit var resultRoot: LinearLayout

    private lateinit var tvType: TextView
    private lateinit var tvMeta: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvAuthor: TextView
    private lateinit var tvPublish: TextView
    private lateinit var ivCover: ImageView
    private lateinit var tvUrlLabel: TextView
    private lateinit var tvUrl: TextView
    private lateinit var btnPlayRow: LinearLayout
    private lateinit var btnPlay: Button
    private lateinit var btnDownload: Button
    private lateinit var btnCopy: Button
    private lateinit var hsThumbs: View
    private lateinit var llThumbs: LinearLayout
    private lateinit var tvLive: TextView
    private lateinit var tvBackup: TextView
    private lateinit var btnMusic: Button

    private var current: MediaResult? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()

        btnPaste.setOnClickListener {
            val text = readClipboard()
            if (text.isEmpty()) {
                toast("剪贴板是空的")
            } else {
                doParse(text)
            }
        }
        btnParse.setOnClickListener { doParse(etInput.text.toString()) }
        btnCopy.setOnClickListener {
            current?.url?.let { copy(it); toast("直链已复制") }
        }
        btnPlay.setOnClickListener {
            current?.url?.let { openUrl(it) }
        }
        btnDownload.setOnClickListener {
            current?.url?.let { download(it, "video/mp4", mainName()) }
        }
        btnMusic.setOnClickListener {
            current?.music?.url?.let { download(it, "audio/mpeg", mainName() + "_bgm") }
        }
    }

    private fun bindViews() {
        etInput = findViewById(R.id.etInput)
        btnPaste = findViewById(R.id.btnPaste)
        btnParse = findViewById(R.id.btnParse)
        progress = findViewById(R.id.progress)
        tvError = findViewById(R.id.tvError)
        resultRoot = findViewById(R.id.resultRoot)

        tvType = findViewById(R.id.tvType)
        tvMeta = findViewById(R.id.tvMeta)
        tvTitle = findViewById(R.id.tvTitle)
        tvAuthor = findViewById(R.id.tvAuthor)
        tvPublish = findViewById(R.id.tvPublish)
        ivCover = findViewById(R.id.ivCover)
        tvUrlLabel = findViewById(R.id.tvUrlLabel)
        tvUrl = findViewById(R.id.tvUrl)
        btnPlayRow = findViewById(R.id.btnPlayRow)
        btnPlay = findViewById(R.id.btnPlay)
        btnDownload = findViewById(R.id.btnDownload)
        btnCopy = findViewById(R.id.btnCopy)
        hsThumbs = findViewById(R.id.hsThumbs)
        llThumbs = findViewById(R.id.llThumbs)
        tvLive = findViewById(R.id.tvLive)
        tvBackup = findViewById(R.id.tvBackup)
        btnMusic = findViewById(R.id.btnMusic)
    }

    // ---------------- 解析 ----------------

    private fun doParse(text: String) {
        val value = text.trim()
        if (value.isEmpty()) {
            showError("请先粘贴抖音分享链接或整段分享文案")
            return
        }
        etInput.setText(value)
        progress.visibility = View.VISIBLE
        tvError.visibility = View.GONE
        resultRoot.visibility = View.GONE
        btnParse.isEnabled = false
        btnPaste.isEnabled = false

        worker.submit {
            val result = try {
                DouyinResolver.parse(value)
            } catch (e: Exception) {
                ParseResult.Err(-1, "解析异常：${e.message ?: e.toString()}")
            }
            runOnUiThread {
                progress.visibility = View.GONE
                btnParse.isEnabled = true
                btnPaste.isEnabled = true
                when (result) {
                    is ParseResult.Ok -> render(result.data)
                    is ParseResult.Err -> showError("[${result.code}] ${result.msg}")
                }
            }
        }
    }

    private fun showError(msg: String) {
        tvError.text = msg
        tvError.visibility = View.VISIBLE
    }

    // ---------------- 结果渲染 ----------------

    private fun render(r: MediaResult) {
        current = r
        resultRoot.visibility = View.VISIBLE

        tvType.text = when (r.type) {
            "video" -> "视频"
            "image" -> "图集"
            "live" -> "实况"
            else -> r.type
        }
        val meta = mutableListOf<String>()
        if (r.quality.isNotEmpty()) meta.add(r.quality)
        r.duration?.let { meta.add("${it}s") }
        if (r.sizeLabel.isNotEmpty()) meta.add(r.sizeLabel)
        tvMeta.text = meta.joinToString(" · ")

        tvTitle.text = r.title
        tvAuthor.text = if (r.author.name.isNotEmpty()) "@${r.author.name}" else ""
        tvPublish.text = r.publishTime
        ivCover.visibility = if (r.cover.isNotEmpty()) View.VISIBLE else View.GONE
        if (r.cover.isNotEmpty()) Media.loadImage(r.cover, ivCover)

        val hasVideo = !r.url.isNullOrEmpty()
        val visibility = if (hasVideo) View.VISIBLE else View.GONE
        tvUrlLabel.visibility = visibility
        tvUrl.visibility = visibility
        tvUrl.text = r.url ?: ""
        btnPlayRow.visibility = visibility
        btnCopy.visibility = visibility
        btnDownload.text = "下载"
        btnDownload.isEnabled = true

        // 图集
        llThumbs.removeAllViews()
        if (r.images.isNotEmpty()) {
            hsThumbs.visibility = View.VISIBLE
            for ((i, u) in r.images.withIndex()) {
                val iv = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(76), dp(100)).apply {
                        marginEnd = dp(8)
                    }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(0xFF000000.toInt())
                    setOnClickListener { download(u, "image/jpeg", "${mainName()}_${i + 1}") }
                }
                Media.loadImage(u, iv)
                llThumbs.addView(iv)
            }
        } else {
            hsThumbs.visibility = View.GONE
        }

        if (r.livePhoto.isNotEmpty()) {
            tvLive.text = "实况 Live Photo：${r.livePhoto.size} 组（图片 + 视频）"
            tvLive.visibility = View.VISIBLE
        } else {
            tvLive.visibility = View.GONE
        }

        if (r.videoBackup.isNotEmpty()) {
            tvBackup.text = "备选直链 ${r.videoBackup.size} 条（点击复制）\n" +
                r.videoBackup.take(3).joinToString("\n") { it.take(90) }
            tvBackup.visibility = View.VISIBLE
            tvBackup.setOnClickListener { copy(r.videoBackup.firstOrNull() ?: ""); toast("备选直链已复制") }
        } else {
            tvBackup.visibility = View.GONE
        }

        if (r.music.url.isNotEmpty()) {
            btnMusic.text = "♪ ${r.music.title.ifEmpty { "背景音乐" }} · 点击下载"
            btnMusic.visibility = View.VISIBLE
        } else {
            btnMusic.visibility = View.GONE
        }
    }

    // ---------------- 动作 ----------------

    private fun mainName(): String {
        val r = current ?: return "sv"
        return (r.title.takeIf { it.isNotBlank() } ?: r.videoId ?: "sv")
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n#]"), "_").trim().take(40)
    }

    private fun download(url: String, mime: String, name: String) {
        toast("下载中…")
        btnDownload.isEnabled = false
        Media.download(this, url, name, mime) { result ->
            runOnUiThread {
                btnDownload.isEnabled = true
                result.fold(
                    onSuccess = { file ->
                        toast("已保存 ${file.length() / 1048576.0f} MB，正在打开")
                        try {
                            Media.open(this, file, mime)
                        } catch (e: Exception) {
                            toast("已保存到 ${file.absolutePath}，但没有可用播放器")
                        }
                    },
                    onFailure = { e -> toast("下载失败：${e.message ?: e}") },
                )
            }
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("无法打开，试试「下载」")
        }
    }

    private fun readClipboard(): String = try {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() ?: "" else ""
    } catch (e: Exception) {
        ""
    }

    private fun copy(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("sv", text))
        } catch (e: Exception) {
            toast("复制失败")
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }
}
