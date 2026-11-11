package com.fnos.tablet

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.PointF
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 独立应用窗（对应 PC 端的 app window / appview?anchor=）。
 * 标题栏由网页自绘（与 PC 端同一份 titlebar-inject.js，argv 带 --fnos-app-window），
 * 原生标题栏只在注入失败时兜底显示。
 *
 * 桌面快捷方式 → 本 Activity（Shortcuts.ACTION_OPEN_APP，extra 带 anchor/base），
 * 冷启动直接进应用窗，不经过主界面（等价 PC 快捷方式带 --app=<url>）。
 */
class AppWindowActivity : AppCompatActivity(), TitlebarHost, MenuTarget {

    private lateinit var web: WebView
    private lateinit var titlebar: View

    private var fullscreen = false
    private var anchorName = ""
    private var base = ""
    private var menu: MenuPopup? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_window)

        web = findViewById(R.id.web)
        titlebar = findViewById(R.id.titlebar)
        val tvTitle = findViewById<TextView>(R.id.tvWinTitle)

        val anchor = intent.getStringExtra(Shortcuts.EXTRA_ANCHOR)
            ?: intent.getStringExtra("anchor").orEmpty()
        val url = intent.getStringExtra(Shortcuts.EXTRA_URL)
            ?: intent.getStringExtra("url").orEmpty()
        // v5.1.34（Bug A）：快捷方式带过来的 FPK 真实入口（影视=/v、相册=/p）
        val path = intent.getStringExtra(Shortcuts.EXTRA_PATH)
            ?: intent.getStringExtra("path").orEmpty()
        base = intent.getStringExtra(Shortcuts.EXTRA_BASE)
            ?: intent.getStringExtra("base").orEmpty()
        if (base.isBlank()) base = Settings.load(this).server
        if (base.isNotBlank() && !base.startsWith("http")) {
            base = try { Lines.normalize(base, Settings.load(this).line) } catch (_: Exception) { base }
        }

        anchorName = intent.getStringExtra(Shortcuts.EXTRA_NAME)
            ?: anchor.ifBlank { "应用" }
        tvTitle.text = anchorName
        // 菜单「切换窗口」的数据源（等价 PC appWindows；不含主窗口）
        WindowRegistry.add(WindowRegistry.Entry(anchorName, intent))
        Log2.w(this, "appwin: anchor=$anchor name=$anchorName base=$base path=$path url=$url action=${intent.action}")

        // 皮肤：兜底标题栏也跟皮肤一致
        try {
            val skin = Skins.activeSkin(this)
            val h = Skins.applyTitlebar(this, titlebar, skin)
            val lp = titlebar.layoutParams
            lp.height = (h * resources.displayMetrics.density).toInt()
                .coerceAtLeast((34 * resources.displayMetrics.density).toInt())
            titlebar.layoutParams = lp
        } catch (_: Exception) {}

        findViewById<ImageButton>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            if (web.canGoBack()) web.goBack() else finish()
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithViewportMode()
            mediaPlaybackRequiresUserGesture = false
            userAgentString = NasUa.DESKTOP
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        web.setBackgroundColor(0xFF0B0D12.toInt())

        TitlebarInject.nasOrigin = try {
            Uri.parse(base).let { u -> "${u.scheme}://${u.authority}" }
        } catch (_: Exception) { "" }

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, u: String?) {
                super.onPageFinished(view, u)
                view?.let { Patch.inject(it) }
                view?.let {
                    TitlebarInject.inject(it, this@AppWindowActivity, false) { ok ->
                        if (ok) {
                            titlebar.visibility = View.GONE
                            Log2.w(this@AppWindowActivity, "appwin: titlebar-inject ok")
                        } else {
                            titlebar.visibility = View.VISIBLE
                            Log2.w(this@AppWindowActivity, "appwin: titlebar-inject failed, keep native bar")
                        }
                    }
                }
            }
        }
        TitlebarInject.attach(web, this, this, false)

        Ui.immersive(this, true)
        fullscreen = true

        Compat.install(web, this)
        loadTarget(anchor, path, url)
    }

    /**
     * v5.1.34（用户 Bug A）：应用窗要加载**应用真实页**，不是 /appview。
     *
     * 优先级（Apps.launchUrl → Apps.urlFor）：快捷方式带的 path → 清单缓存里的 path
     * → 第三方自带端口 url → 外壳微应用地址 → 工作台内置应用落工作台 → /app/<name>/ 回退
     * → 最后才是 /appview?anchor=（NAS 上 404，会进「兼容模式」，正常不应走到）。
     *
     * 清单缓存可能是空的（冷启动直达本 Activity，没经过主界面）——
     * 这时先在后台拉一次 FPK 清单（最多 2.5s）再加载，避免直接掉进兜底。
     */
    private fun loadTarget(anchor: String, path: String, url: String) {
        val e = Apps.resolve(this, anchor)
        // 「有可用信息」= 能拼出真实入口：path / 第三方端口 url / 清单里带 path 的条目。
        // 老版本缓存只有 anchor 没有 path，这种情况必须重拉一次清单（否则会掉到 /app/<name>/）。
        // 注意 path=="/" 是退化清单的产物（NAS 首页），不算可用信息——缓存里也可能留着它。
        fun usable(p: String) = p.trim().isNotEmpty() && p.trim() != "/"
        val haveInfo = usable(path) ||
            (url.startsWith("http") && url.indexOf("/appview") < 0) ||
            (e != null && (usable(e.path) || e.url.startsWith("http") || Apps.isShellApp(e)))
        if (haveInfo || base.isBlank()) {
            val target = Apps.launchUrl(this, base, anchor, path, url)
            Log2.w(this, "appwin: load target=$target haveInfo=$haveInfo")
            hintWorkbench(target)
            web.loadUrl(target)
            return
        }
        Log2.w(this, "appwin: 清单里没有可用的 path（anchor=$anchor），先拉一次 FPK 清单再加载")
        Thread {
            try { Apps.refreshBlocking(this, base, 2500, force = true) } catch (_: Throwable) {}
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val t = Apps.launchUrl(this, base, anchor, path, url)
                Log2.w(this, "appwin: load target(after refresh)=$t")
                hintWorkbench(t)
                web.loadUrl(t)
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * v5.1.34：文件管理 / 应用中心 / 系统设置 是飞牛工作台的内置组件，
     * NAS 上**没有**可直连的页面（实测 /app/<name>/ 返回 `invalid token`），
     * 这种情况落到工作台并说明原因，避免用户以为是「没打开」。
     */
    private fun hintWorkbench(target: String) {
        val root = base.trimEnd('/') + "/"
        if (target == root) {
            Toast.makeText(this, "$anchorName 是飞牛工作台内置应用，已打开工作台", Toast.LENGTH_LONG).show()
            Log2.w(this, "appwin: $anchorName 无独立页面，落到工作台 $root")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun WebSettings.loadWithViewportMode() {
        loadWithOverviewMode = true
        builtInZoomControls = false
        displayZoomControls = false
    }

    override fun onDestroy() {
        WindowRegistry.remove(anchorName)
        super.onDestroy()
    }

    /** 横竖屏（需求 4）：应用窗不重建，只做重新测量 + 通知页面重排 */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        try {
            web.requestLayout()
            titlebar.requestLayout()
            web.post {
                try {
                    web.evaluateJavascript(
                        "(function(){try{window.dispatchEvent(new Event('resize'));}catch(e){}" +
                            "try{window.dispatchEvent(new Event('orientationchange'));}catch(e){}})();",
                        null
                    )
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        Log2.w(this, "appwin: configChanged o=${newConfig.orientation}")
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else finish()
    }

    override fun onResume() {
        super.onResume()
        TitlebarInject.pushTitlebarSettings(web, this)
    }

    // ---------------------------------------------------------- TitlebarHost

    override fun tbClose() = finish()

    override fun tbMinimize() {
        moveTaskToBack(true)
    }

    override fun tbToggleMaximize() {
        fullscreen = !fullscreen
        Ui.immersive(this, fullscreen)
        TitlebarInject.emit(web, "window:fullscreen-changed", if (fullscreen) "true" else "false")
    }

    /** 应用窗没有「连接页」，回主窗由主窗负责 */
    override fun tbBackToConnect() = finish()

    /** 应用窗的 ☰：与 PC 同一份自绘菜单 */
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
            Log2.w(this, "appwin: menu failed ${e.message}")
        }
    }

    override fun tbIsFullscreen(): Boolean = fullscreen

    // ------------------------------------------------------------- MenuTarget

    override val act: AppCompatActivity get() = this

    override fun menuWeb(): WebView? = web

    override fun menuBase(): String = base.ifBlank { Settings.load(this).server }

    override fun menuGoHome() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        finish()
    }

    /** 文件 → 锁定 FNOS：回到主窗登录页（本端的「锁屏」），并关掉本应用窗 */
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
        // 「切换账号」在应用窗里点：回主窗，由主窗按新账号重连
        val i = Intent(this, MainActivity::class.java)
        i.putExtra("connectTo", href)
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        startActivity(i)
        finish()
    }

    override fun menuMinimize() {
        moveTaskToBack(true)
    }

    /** 文件 → 一键隐藏 / 呼出 */
    override fun menuHideAll() {
        moveTaskToBack(true)
    }

    /** 文件 → 隐藏到托盘（Android 无托盘：退到后台 + 提示） */
    override fun menuHideToTray() {
        moveTaskToBack(true)
        Toast.makeText(this, "已隐藏到后台", Toast.LENGTH_SHORT).show()
    }

    override fun menuCloseWindow() = finish()

    override fun menuToggleFullscreen() = tbToggleMaximize()
}
