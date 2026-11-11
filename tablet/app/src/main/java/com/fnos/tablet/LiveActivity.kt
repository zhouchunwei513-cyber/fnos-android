package com.fnos.tablet

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PointF
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
class LiveActivity : LocalWebActivity(), TitlebarHost, MenuTarget {

    internal val bridge: LiveBridge by lazy { LiveBridge(this) }

    private lateinit var root: FrameLayout
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var closeBtn: TextView? = null
    private var sideBtn: TextView? = null
    private var isFullscreen = false
    private var embedTitle = ""

    /** v5.1.31-tablet17：当前 NAS 地址（菜单/快捷方式要用） */
    private var base = ""

    /** 窗口级全屏（自绘标题栏的 □ 按钮；与播放器全屏 isFullscreen 区分） */
    private var winFullscreen = false

    private var menu: MenuPopup? = null

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
        // v5.1.31-tablet17（用户任务 3）：PC 自绘标题栏的 ipcRenderer 替身。
        // 必须在页面脚本执行前挂好（本方法在 super.onCreate 的 loadUrl 之前被调用）。
        try { TitlebarInject.attach(w, this, this, false) } catch (_: Throwable) {}
        Log2.w(this, "live: ready url=${WebAssets.liveUrl()} base=${intent?.getStringExtra("base").orEmpty()}")
    }

    /**
     * v5.1.31-tablet17（用户任务 3）：直播页原来**没有标题栏** ——
     * 它只继承了 LocalWebActivity，没有实现 TitlebarHost、也没注入 titlebar-inject.js。
     * 现在与应用窗用同一份脚本、同一个 argv（--fnos-app-window）：
     * 汉堡（同一套 PC 菜单）+ − □ ✕ + 账号球 + 皮肤全部一致。
     */
    override fun onWebPageFinished(w: WebView?) {
        if (w == null) return
        // 诊断开关（仅调试用）：--ez notb true 启动可跳过标题栏注入，用于 A/B 对比布局
        if (intent?.getBooleanExtra("notb", false) == true) {
            Log2.w(this, "live: titlebar skipped (notb)")
            w.postDelayed({ try { w.evaluateJavascript(MEASURE_JS, null) } catch (_: Throwable) {} }, 900)
            return
        }
        TitlebarInject.inject(w, this, false) { ok ->
            Log2.w(this, "live: titlebar-inject ok=$ok")
            // 标题栏会让出顶部 34px，#videoArea 位置随之下移，重新贴合原生播放器
            w.postDelayed({ syncEmbedRect() }, 300)
            w.postDelayed({ syncEmbedRect() }, 700)
            // 几何埋点：核对标题栏让位后的页面宽度/播放区矩形（真机取证据用）
            w.postDelayed({ try { w.evaluateJavascript(MEASURE_JS, null) } catch (_: Throwable) {} }, 900)
        }
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
                if (playbackState == Player.STATE_READY) {
                    Log2.w(this@LiveActivity, "live: ready ${embedTitle}")
                    // 播放态几何埋点：确认原生播放器矩形与视频尺寸
                    syncEmbedRect()
                    try { web.evaluateJavascript(MEASURE_JS, null) } catch (_: Throwable) {}
                    web.postDelayed({
                        try { web.evaluateJavascript(MEASURE_JS, null) } catch (_: Throwable) {}
                    }, 900)
                }
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
        Log2.w(
            this,
            "live: rect x=${lp.leftMargin} y=${lp.topMargin} w=${lp.width} h=${lp.height}" +
                " root=${root.width}x${root.height} vid=${player?.videoSize?.width}x${player?.videoSize?.height}" +
                " dpr=${resources.displayMetrics.density}"
        )

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
        // 先拿 NAS 地址：菜单/账号球/标题栏脚本的 nasOrigin 都要用（super 会立刻 loadUrl）
        base = intent?.getStringExtra(Shortcuts.EXTRA_BASE)
            ?: intent?.getStringExtra("base").orEmpty()
        if (base.isBlank()) base = Settings.load(this).server
        if (base.isNotBlank() && !base.startsWith("http")) {
            base = try { Lines.normalize(base, Settings.load(this).line) } catch (_: Exception) { base }
        }
        TitlebarInject.nasOrigin = try {
            Uri.parse(base).let { u -> "${u.scheme}://${u.authority}" }
        } catch (_: Exception) { "" }
        super.onCreate(savedInstanceState)
        // v5.1.31-tablet17（用户任务 3 第 3 条）：窗口按钮语义与主窗/应用窗一致 ——
        // 默认是「窗口态」（系统栏可见），标题栏 □ 才是「页面全屏」（隐藏系统栏）。
        // 这里不再强制沉浸，否则第一个 □ 反而是退出全屏，语义反了。
        winFullscreen = false
        try { TitlebarInject.warmUp(this) } catch (_: Throwable) {}
        Log2.w(this, "live: onCreate base=$base nasOrigin=${TitlebarInject.nasOrigin}")
    }

    override fun onResume() {
        super.onResume()
        // 皮肤/标题栏配置可能在设置页改过 → 回到本页时推一次（与主窗/应用窗同款）
        TitlebarInject.pushTitlebarSettings(web, this)
        web.postDelayed({ syncEmbedRect() }, 260)
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

    // ---------------------------------------------------------- TitlebarHost

    /** ✕ = 关闭直播窗（播放器随 Activity 释放） */
    override fun tbClose() = finish()

    /** — = 退到后台：Activity 不销毁，ExoPlayer 继续出声（直播音频继续） */
    override fun tbMinimize() {
        moveTaskToBack(true)
        Log2.w(this, "live: minimize -> background (audio keeps playing)")
    }

    /** □ = 窗口全屏 / 还原（隐藏或恢复系统栏），与主窗、应用窗语义一致 */
    override fun tbToggleMaximize() {
        winFullscreen = !winFullscreen
        Ui.immersive(this, winFullscreen)
        TitlebarInject.emit(web, "window:fullscreen-changed", if (winFullscreen) "true" else "false")
        web.postDelayed({ syncEmbedRect() }, 260)
        Log2.w(this, "live: window fullscreen=$winFullscreen")
    }

    /** 账号球「添加账号」：直播窗没有连接页，关掉本窗交给主窗 */
    override fun tbBackToConnect() = finish()

    /** ☰ = 与主窗 / 应用窗同一份 PC 菜单（MenuPopup + MenuModel） */
    override fun tbPopupMenu(anchorJson: String?) {
        val d = resources.displayMetrics.density
        val p = try {
            val o = org.json.JSONObject(anchorJson ?: "null")
            PointF(
                (o.optDouble("left", 8.0) * d).toFloat(),
                (o.optDouble("bottom", 34.0) * d + 2f).toFloat()
            )
        } catch (_: Exception) { PointF(8f * d, 36f * d) }
        try {
            menu?.dismiss()
            menu = MenuPopup(this, p, this).also { it.show() }
        } catch (e: Throwable) {
            Log2.w(this, "live: menu failed ${e.message}")
        }
    }

    override fun tbIsFullscreen(): Boolean = winFullscreen

    // ------------------------------------------------------------- MenuTarget

    override val act: AppCompatActivity get() = this

    override fun menuWeb(): WebView? = web

    override fun menuBase(): String = base.ifBlank { Settings.load(this).server }

    override fun menuGoHome() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        finish()
    }

    /** 文件 → 锁定 FNOS：回主窗登录页并关掉直播窗 */
    override fun menuLock() {
        val i = Intent(this, MainActivity::class.java)
        i.putExtra("lock", true)
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        startActivity(i)
        finish()
    }

    override fun menuLoginOther() {
        val i = Intent(this, MainActivity::class.java)
        i.putExtra("lock", false)
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        startActivity(i)
        finish()
    }

    override fun menuConnectTo(href: String) {
        val i = Intent(this, MainActivity::class.java)
        i.putExtra("connectTo", href)
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        startActivity(i)
        finish()
    }

    override fun menuMinimize() {
        moveTaskToBack(true)
    }

    override fun menuHideAll() {
        moveTaskToBack(true)
    }

    override fun menuHideToTray() {
        moveTaskToBack(true)
        toast("已隐藏到后台（直播音频继续）")
    }

    override fun menuCloseWindow() = finish()

    override fun menuToggleFullscreen() = tbToggleMaximize()

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
        /** 几何埋点：把视口/body/#app/#videoArea 的真实尺寸写进 startup.log */
        private const val MEASURE_JS = "(function(){try{var d=document.documentElement," +
            "b=document.body,a=document.getElementById('app'),v=document.getElementById('videoArea');" +
            "var r=v?v.getBoundingClientRect():null;" +
            "var o={stage:'measure',iw:window.innerWidth,ih:window.innerHeight," +
            "dw:d.clientWidth,dh:d.clientHeight,bw:Math.round(b.getBoundingClientRect().width)," +
            "appw:a?Math.round(a.getBoundingClientRect().width):-1," +
            "vw:r?Math.round(r.width):-1,vh:r?Math.round(r.height):-1," +
            "vx:r?Math.round(r.left):-1,vy:r?Math.round(r.top):-1," +
            "zoom:(getComputedStyle(d).zoom||''),bh:getComputedStyle(d).getPropertyValue('--fnos-bar-h')," +
            "dpr:window.devicePixelRatio,sw:document.body.scrollWidth," +
            "swh:document.documentElement.scrollWidth," +
            "el:(function(){var ids=['sidebar','stage','playerWrap','videoRow','video','ctrlBar','epgPanel'],o={};" +
            "for(var i=0;i<ids.length;i++){var e=document.getElementById(ids[i]);if(!e){o[ids[i]]='-';continue;}" +
            "var b=e.getBoundingClientRect();o[ids[i]]=[Math.round(b.left),Math.round(b.top),Math.round(b.width),Math.round(b.height),getComputedStyle(e).display].join(',');}return o;})()};" +
            "if(window.fnosHost&&fnosHost.notify)fnosHost.notify('fnos:media-log',JSON.stringify(o));" +
            "}catch(e){}})()";
        /** 与 PadCss.LIVE_JS 里 readRect() 同一份口径（物理像素） */
        private const val RECT_JS = "(function(){try{var el=document.getElementById('videoArea')" +
            "||document.getElementById('playerWrap');if(!el)return null;var r=el.getBoundingClientRect();" +
            "if(!(r.width>40&&r.height>40))return null;var d=window.devicePixelRatio||1;" +
            "return {x:Math.round(r.left*d),y:Math.round(r.top*d),width:Math.round(r.width*d)," +
            "height:Math.round(r.height*d)};}catch(e){return null;}})()"
    }
}
