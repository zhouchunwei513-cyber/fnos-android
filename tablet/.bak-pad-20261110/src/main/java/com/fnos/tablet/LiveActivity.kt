package com.fnos.tablet

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * 电视直播（对齐 PC 端 live.html）：
 *  - 左：频道列表（分组）；右：播放器
 *  - 线路：inner / ipv6 / frp（XTE 提供 /m3u/<line>.m3u8）
 *  - 播放：Media3 ExoPlayer（HLS）
 */
class LiveActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var list: ListView
    private lateinit var tvStatus: TextView
    private lateinit var adapter: ChannelAdapter

    private var serverBase = ""
    private var line = "inner"
    private val channels = ArrayList<M3u.Channel>()
    private var currentIndex = -1

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serverBase = intent.getStringExtra("base").orEmpty()
        if (serverBase.isBlank()) serverBase = Settings.load(this).server

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B0D12.toInt())
        }

        // 顶部工具条
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF14121B.toInt())
            setPadding(8, 0, 8, 0)
        }
        val btnClose = Button(this).apply { text = "✕"; setOnClickListener { finish() } }
        val tvTitle = TextView(this).apply {
            text = "电视直播"
            setTextColor(0xFFE6EDF3.toInt())
            textSize = 16f
            setPadding(16, 0, 16, 0)
        }
        val btnReload = Button(this).apply { text = "刷新"; setOnClickListener { loadChannels() } }
        bar.addView(btnClose, LinearLayout.LayoutParams(dp(52), dp(48)))
        bar.addView(tvTitle, LinearLayout.LayoutParams(0, dp(48), 1f))
        bar.addView(btnReload, LinearLayout.LayoutParams(dp(72), dp(48)))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        // 线路切换
        val linesBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF10141B.toInt())
            setPadding(8, 4, 8, 4)
        }
        for ((key, label) in listOf("inner" to "内网", "ipv6" to "IPv6", "frp" to "FRP")) {
            val b = Button(this).apply {
                text = label
                setOnClickListener { line = key; loadChannels() }
            }
            linesBar.addView(b, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        root.addView(linesBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        // 主体：左列表 + 右播放器
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        list = ListView(this).apply { setBackgroundColor(0xFF0E1218.toInt()) }
        adapter = ChannelAdapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> playChannel(position) }
        body.addView(list, LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.MATCH_PARENT))

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF000000.toInt())
        }
        player = ExoPlayer.Builder(this).build()
        playerView = PlayerView(this).apply { player = player }
        tvStatus = TextView(this).apply {
            setTextColor(0xFF8B98A5.toInt())
            text = "选择左侧频道开始播放"
            setPadding(16, 8, 16, 8)
        }
        right.addView(tvStatus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)))
        right.addView(playerView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        body.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        loadChannels()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun loadChannels() {
        val xte = M3u.xteBase(serverBase)
        val url = "$xte/m3u/$line.m3u8"
        tvStatus.text = "拉取频道列表… $url"
        thread {
            try {
                val text = httpGet(url)
                val parsed = M3u.parse(text, url)
                runOnUiThread {
                    channels.clear(); channels.addAll(parsed)
                    adapter.notifyDataSetChanged()
                    tvStatus.text = "线路 $line：${channels.size} 个频道"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "拉取失败：${e.message}"
                    Toast.makeText(this, "频道列表失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun playChannel(index: Int) {
        val ch = channels.getOrNull(index) ?: return
        currentIndex = index
        tvStatus.text = "正在播放：${ch.name}"
        player.stop()
        player.clearMediaItems()
        player.setMediaItem(MediaItem.fromUri(ch.url))
        player.prepare()
        player.playWhenReady = true
    }

    private fun httpGet(u: String): String {
        val conn = URL(u).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 FNOS-Tablet/1.0")
        try {
            val code = conn.responseCode
            if (code >= 400) throw IllegalStateException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }

    inner class ChannelAdapter : BaseAdapter() {
        override fun getCount() = channels.size
        override fun getItem(position: Int) = channels[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: TextView(this@LiveActivity).apply {
                setPadding(dp(16), dp(14), dp(16), dp(14))
                textSize = 15f
            }
            val c = channels[position]
            tv.text = if (c.group.isBlank()) c.name else "${c.name}"
            tv.setTextColor(if (position == currentIndex) 0xFF4EA1FF.toInt() else 0xFFD6DEE8.toInt())
            tv.setBackgroundColor(if (position == currentIndex) 0xFF18212E.toInt() else 0x00000000)
            return tv
        }
    }

    override fun onDestroy() {
        try { player.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
