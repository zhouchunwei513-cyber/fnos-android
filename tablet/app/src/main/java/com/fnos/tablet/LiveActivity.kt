package com.fnos.tablet

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import org.json.JSONObject

/**
 * 电视直播 —— 与 PC 端**同一份** assets/web/live.html（PC 的直播窗就是 loadFile('live.html')）。
 *
 * 为什么不再用原生布局（v5.1.33）：
 *   PC 的直播窗界面（玻璃质感 / 线路 tab / 分组 + ★收藏 / 播放控制条 / 节目单）全部写在
 *   live.html 的内联 <style> 里，原生 ListView 怎么抄都只能"像"，不可能"一样"。
 *   现在整页搬进 assets/web/，用 WebViewAssetLoader 加载，外观与交互天然一致。
 *
 * 与 PC 的两个必要差异（PC 是 Electron 主进程 + preload，Android 是 WebView + JS 桥）：
 *  1) `window.fnosLive`（PC live-preload.js 的 contextBridge）由 LiveBridge 提供：
 *     拉播放列表 / 线路探测 / 收藏 / 日志走原生 HTTP，规避页面源的 CORS 限制。
 *  2) 播放：页面里 `<video> + hls.js` 在 WebView 里取 NAS 流会被 CORS/PNA 拦，
 *     所以注入脚本把 `playWithHls` 换成"调 mpvEmbed"→ 这里用 **ExoPlayer 内嵌播放**，
 *     盖在页面 #videoArea 的矩形上（矩形由注入脚本按 devicePixelRatio 换算成物理像素）。
 *     ← 对应 PC 的「MPV 应用内嵌入播放」(mpv:embed / mpv:embed-rect)。
 *
 * 横竖屏：Manifest 已声明 configChanges（不重建、页面不重载 → 当前频道不丢），
 * 旋转时只重新注入 PadCss.LIVE / LIVE_JS 并让原生播放器重新贴合播放区矩形。
 */
class LiveActivity : LocalWebActivity() {

    internal val bridge: LiveBridge by lazy { LiveBridge(this) }

    private lateinit var root: FrameLayout
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var closeBtn: TextView? = null
    private var sideBtn: TextView? = null
    private var isFullscreen = false
    private var embedTitle = ""

    /** 可用的直播 origin（如 http://100.103.38.13:34500），播放前由 bridge 解析一次 */
    @Volatile private var playOrigin = ""
    /** 已打印过改写日志的来源，避免每 10s 一条分片刷屏 */
    private val rewriteLogged = java.util.Collections.synchronizedSet(HashSet<String>())

    // ------------------------------------------------------------------ LocalWebActivity

    override fun pageUrl(): String = WebAssets.liveUrl()

    override fun preloadScript(): String = LiveBridge.SHIM

    override fun padCss(): String = PadCss.LIVE

    override fun padJs(): String = PadCss.LIVE_JS

    override fun dialogTitle(): String = "电视直播"

    /** 直播页底色（--bg: #0b0d12），首帧不闪白 */
    override fun pageBackgroundColor(): Int = 0xFF0B0D12.toInt()

    override fun wrapContent(w: WebView): View {
        root = FrameLayout(this).apply { setBackgroundColor(pageBackgroundColor()) }
        root.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return root
    }

    override fun onPrepareWeb(w: WebView) {
        // 页面源是 https://appassets.androidplatform.net，而直播源/台标是 http://NAS:34500
        // → 必须显式允许混合内容，否则台标全裂、页面里的 http 资源一律被拦
        try {
            w.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            w.settings.mediaPlaybackRequiresUserGesture = false
        } catch (_: Throwable) {}
        w.addJavascriptInterface(bridge, LiveBridge.IFACE)
        Log2.w(this, "live: ready url=${WebAssets.liveUrl()} base=${intent?.getStringExtra("base").orEmpty()}")
    }

    // ------------------------------------------------------------------ 原生播放（ExoPlayer）

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 首次播放时建播放器；UA 与平板壳保持一致，部分 IPTV 服务端会按 UA 拉流 */
    private fun ensurePlayer() {
        if (player != null) return
        val http = DefaultHttpDataSource.Factory().setUserAgent("Mozilla/5.0 FNOS-Tablet/1.0")
        // 地址纠正：XTE 清单里写的是它自己的内网 IP（192.168/10./172.16-31），
        // 分片（/proxy/ts）也是绝对地址；平板若走 Tailscale（100.x）就连不上。
        // 在数据源层把「私网 IPv4 且与可用 origin 不同」的请求原地改写，
        // 清单与分片一视同仁（只改清单 URL 不够）。
        val resolving = ResolvingDataSource.Factory(http) { spec ->
            val origin = playOrigin
            if (origin.isEmpty()) return@Factory spec
            val u = spec.uri
            val h = u.host.orEmpty()
            if (!isPrivateV4(h)) return@Factory spec
            val cur = "http://$h:" + (if (u.port > 0) u.port else 80)
            if (cur == origin) return@Factory spec
            val o = android.net.Uri.parse(origin)
            val nu = u.buildUpon().scheme(o.scheme).encodedAuthority(o.encodedAuthority).build()
            if (rewriteLogged.add(h)) Log2.w(this, "live: rewrite $cur -> $origin")
            spec.withUri(nu)
        }
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, resolving))
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(factory).build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log2.w(this@LiveActivity, "live: player error ${error.errorCodeName} ${error.message}")
                toast("播放失败：${error.message ?: error.errorCodeName}")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) Log2.w(this@LiveActivity, "live: ready ${embedTitle}")
                if (playbackState == Player.STATE_BUFFERING) Log2.w(this@LiveActivity, "live: buffering ${embedTitle}")
            }
        })
        player = p

        val pv = PlayerView(this).apply {
            setBackgroundColor(Color.BLACK)
            useController = true
            controllerAutoShow = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            this.player = p
        }
        root.addView(pv, FrameLayout.LayoutParams(0, 0).apply { gravity = Gravity.TOP or Gravity.START })
        pv.visibility = View.GONE
        playerView = pv

        val btn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setBackgroundColor(0x99000000.toInt())
            setOnClickListener { closeEmbed() }
        }
        root.addView(btn, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(10)
            rightMargin = dp(10)
        })
        btn.visibility = View.GONE
        closeBtn = btn

        // 频道列表开关：页面里的 #sideToggle 在 #videoArea 内，播放时会被原生播放器整个盖住
        // （横屏也一样），所以播放时另给一个原生 ☰，点一下展开/收起页面自己的频道栏。
        val sb = TextView(this).apply {
            text = "☰"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setBackgroundColor(0x99000000.toInt())
            setOnClickListener { toggleSidebar() }
        }
        root.addView(sb, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.START).apply {
            topMargin = dp(10)
            leftMargin = dp(10)
        })
        sb.visibility = View.GONE
        sideBtn = sb
    }

    /** 展开/收起页面自带频道栏（竖屏是抽屉，收起时把原生播放器让开，声音继续） */
    private fun toggleSidebar() {
        evalJs(
            "try{(function(){var a=document.getElementById('app');if(!a)return;" +
                "var open=a.classList.contains('sb-collapsed');" +
                "a.classList.toggle('sb-collapsed');" +
                "if(window.fnosLive&&window.fnosLive.padPlayerVisible){" +
                "window.fnosLive.padPlayerVisible({visible:!open});}})()}catch(e){}"
        )
    }

    /** 竖屏抽屉展开时把原生播放器临时让开（音频照常，关闭抽屉/换台后回来） */
    internal fun setPlayerVisible(visible: Boolean) {
        if (isFullscreen) return
        playerView?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        closeBtn?.visibility = if (visible) View.VISIBLE else View.GONE
        sideBtn?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** 私网 IPv4（RFC1918）——这类地址多半是 XTE 报的自家内网，平板不一定路由得到 */
    private fun isPrivateV4(h: String): Boolean {
        val p = h.split('.')
        if (p.size != 4) return false
        val a = p[0].toIntOrNull() ?: return false
        val b = p[1].toIntOrNull() ?: return false
        return when {
            a == 10 -> true
            a == 192 && b == 168 -> true
            a == 172 && b in 16..31 -> true
            else -> false
        }
    }

    /** LiveBridge.mpvEmbed：页面要播某路流 → 原生播放器贴到播放区矩形上 */
    internal fun startEmbed(url: String, title: String, rect: JSONObject?) {
        try {
            ensurePlayer()
            val pv = playerView ?: return
            val p = player ?: return
            playOrigin = try { bridge.playOrigin() } catch (_: Throwable) { "" }
            embedTitle = title
            isFullscreen = false
            pv.visibility = View.VISIBLE
            closeBtn?.visibility = View.VISIBLE
            sideBtn?.visibility = View.VISIBLE
            applyRect(rect)
            p.stop()
            p.clearMediaItems()
            p.setMediaItem(MediaItem.fromUri(url))
            p.prepare()
            p.playWhenReady = true
            evalJs("try{(function(){var p=document.getElementById('placeholder');if(p)p.style.display='none';})()}catch(e){}")
            Log2.w(this, "live: native play title=$title url=$url")
        } catch (e: Throwable) {
            Log2.w(this, "live: startEmbed failed ${e.message}")
            toast("播放失败：${e.message}")
        }
    }

    /** LiveBridge.mpvEmbedRect：播放区几何变化（窗口/旋转/侧栏折叠）→ 重新贴合 */
    internal fun moveEmbed(rect: JSONObject?) {
        if (playerView == null || playerView?.visibility == View.GONE) return
        if (isFullscreen) return
        applyRect(rect)
    }

    internal fun toggleFullscreen() {
        val pv = playerView ?: return
        if (pv.visibility != View.VISIBLE) return
        isFullscreen = !isFullscreen
        if (isFullscreen) {
            applyRect(null)
            evalJs("try{window.__fnosLivePadDrawer&&window.__fnosLivePadDrawer(false)}catch(e){}")
        } else {
            syncEmbedRect()
        }
        Log2.w(this, "live: fullscreen=$isFullscreen")
    }

    private fun closeEmbed() {
        try {
            player?.stop()
            player?.clearMediaItems()
        } catch (_: Throwable) {}
        playerView?.visibility = View.GONE
        closeBtn?.visibility = View.GONE
        sideBtn?.visibility = View.GONE
        isFullscreen = false
        // 页面恢复占位提示 + 隐藏"原生播放器"徽标
        evalJs(
            "try{(function(){var p=document.getElementById('placeholder');if(p)p.style.display='';" +
                "var b=document.getElementById('npEngine');if(b)b.style.display='none';})()}catch(e){}"
        )
        Log2.w(this, "live: embed closed")
    }

    /** rect 为物理像素 {x,y,width,height}（注入脚本已按 devicePixelRatio 换算） */
    private fun applyRect(rect: JSONObject?) {
        val pv = playerView ?: return
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        if (rect != null && rect.has("width") && rect.optInt("width", 0) > 0 && rect.optInt("height", 0) > 0) {
            lp.width = rect.optInt("width", 0)
            lp.height = rect.optInt("height", 0)
            lp.leftMargin = rect.optInt("x", 0)
            lp.topMargin = rect.optInt("y", 0)
        }
        lp.gravity = Gravity.TOP or Gravity.START
        pv.layoutParams = lp
        pv.requestLayout()

        // ✕ 一直在播放区右上角（全屏时就在整窗右上角）
        closeBtn?.let { b ->
            val blp = (b.layoutParams as? FrameLayout.LayoutParams) ?: return@let
            blp.topMargin = lp.topMargin + dp(10)
            blp.rightMargin = dp(10)
            blp.gravity = Gravity.TOP or Gravity.END
            b.layoutParams = blp
            b.requestLayout()
        }
        // ☰ 跟播放区左上角（与页面自己的 #sideToggle 同位）
        sideBtn?.let { b ->
            val blp = (b.layoutParams as? FrameLayout.LayoutParams) ?: return@let
            blp.topMargin = lp.topMargin + dp(6)
            blp.leftMargin = lp.leftMargin + dp(10)
            blp.gravity = Gravity.TOP or Gravity.START
            b.layoutParams = blp
            b.requestLayout()
        }
    }

    /** 向页面要一次 #videoArea 的物理像素矩形（旋转/尺寸变化后调用） */
    private fun syncEmbedRect() {
        if (playerView == null || playerView?.visibility == View.GONE) return
        if (isFullscreen) return
        try {
            web.evaluateJavascript(RECT_JS) { v ->
                if (v == null || v == "null") return@evaluateJavascript
                try {
                    applyRect(JSONObject(v))
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    private fun toast(msg: String) {
        try { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
    }

    internal fun clearWebCache() {
        try { web.clearCache(true) } catch (_: Throwable) {}
        Log2.w(this, "live: web cache cleared")
    }

    // ------------------------------------------------------------------ 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log2.w(this, "live: onCreate")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 旋转前若正展开着频道抽屉（播放器被让开），旋转后方向语义变了，统一把画面还原
        if (playerView?.visibility == View.INVISIBLE) setPlayerVisible(true)
        // 页面不重载（configChanges），这里只让原生播放器重新贴合播放区：
        // 旋转后布局/抽屉状态要等两三帧才稳定，所以 3 次采样
        val settle = intArrayOf(260, 520, 900)
        settle.forEach { d -> web.postDelayed({ syncEmbedRect() }, d.toLong()) }
        Log2.w(this, "live: config o=${newConfig.orientation} ${newConfig.screenWidthDp}x${newConfig.screenHeightDp}")
    }

    @Deprecated("兼容旧 API")
    override fun onBackPressed() {
        if (playerView?.visibility == View.VISIBLE && isFullscreen) {
            toggleFullscreen()
            return
        }
        finish()
    }

    override fun onDestroy() {
        try {
            player?.release()
            player = null
            playerView = null
            closeBtn = null
        } catch (_: Throwable) {}
        super.onDestroy()
    }

    companion object {
        /** 与 PadCss.LIVE_JS 里 readRect() 同一份口径（物理像素） */
        private const val RECT_JS = "(function(){try{var el=document.getElementById('videoArea')" +
            "||document.getElementById('playerWrap');if(!el)return null;var r=el.getBoundingClientRect();" +
            "if(!(r.width>40&&r.height>40))return null;var d=window.devicePixelRatio||1;" +
            "return {x:Math.round(r.left*d),y:Math.round(r.top*d),width:Math.round(r.width*d)," +
            "height:Math.round(r.height*d)};}catch(e){return null;}})()"
    }
}
