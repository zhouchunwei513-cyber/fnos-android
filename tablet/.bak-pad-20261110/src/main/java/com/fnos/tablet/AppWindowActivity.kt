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
        Log2.w(this, "appwin: anchor=$anchor name=$anchorName base=$base url=$url action=${intent.action}")

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

        val target = when {
            url.startsWith("http") -> url
            anchor.isNotBlank() && base.isNotBlank() -> Lines.appviewUrl(base, anchor)
            else -> base
        }
        Compat.install(web, this)
        if (target.isNotBlank()) web.loadUrl(target) else Log2.w(this, "appwin: 没有可加载的地址")
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
