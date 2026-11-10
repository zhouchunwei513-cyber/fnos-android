package com.fnos.tablet

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PointF
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 主窗口。
 *
 * 首屏 = PC 端登录页（assets/web/login.html，与 PC 仓库同一份 login.html/login.css/login.js），
 * 连上后切换到这个 Activity 里的桌面 WebView（NAS 自带网页桌面）。
 *
 * 与 v5.1.x 的区别（本次改造）：
 *   · 干掉了「Android 原生简易连接页」（EditText + 线路按钮 + 连接按钮），换成 PC 登录页；
 *   · 菜单从原生 AlertDialog 换成 PC 自绘菜单（menu-popup.html，走 MenuPopup）；
 *   · 新增桌面快捷方式（Shortcuts：动态快捷方式 + 钉到桌面）。
 * 原有能力全部保留：标题栏注入（TitlebarInject）、皮肤（Skins）、远程补丁（Patch）、
 * 崩溃兜底（App/Log2）、兼容层（Compat）、电视直播（LiveActivity）。
 */
class MainActivity : AppCompatActivity(), TitlebarHost, MenuTarget {

    private lateinit var web: WebView
    private lateinit var loginWeb: WebView
    private lateinit var titlebar: View
    private lateinit var tvTitle: TextView

    private var base: String = ""
    private var line: String = "lan"

    /** 沉浸式（＝PC 端的最大化）状态，供 win-fs-state 查询 */
    private var fullscreen = false

    /** true = 正在显示 PC 登录页；false = 桌面 */
    private var onLogin = true

    private var loginScreen: LoginScreen? = null
    private var menu: MenuPopup? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setup()
        } catch (e: Throwable) {
            // v5.1.18：任何初始化异常都落盘 + 就地展示，避免「点开就闪退」看不出原因
            val sw = java.io.StringWriter(); e.printStackTrace(java.io.PrintWriter(sw))
            try { java.io.File(filesDir, App.CRASH_FILE).writeText("onCreate 异常：\n" + sw.toString()) } catch (_: Exception) {}
            val tv = TextView(this).apply {
                text = "启动失败：\n" + (e.message ?: e.toString()) + "\n\n---- 启动步骤 ----\n" + Log2.tail(this@MainActivity, 30)
                setTextColor(0xFFFF6B6B.toInt()); setPadding(48, 96, 48, 48); textSize = 15f
            }
            setContentView(tv)
        }
        showLastCrashIfAny()
    }

    private fun setup() {
        Log2.w(this, "setup: begin")
        setContentView(R.layout.activity_main)
        Log2.w(this, "setup: layout inflated")

        web = findViewById(R.id.web)
        loginWeb = findViewById(R.id.loginWeb)
        titlebar = findViewById(R.id.titlebar)
        tvTitle = findViewById(R.id.tvTitle)

        val st = Settings.load(this)
        Log2.w(this, "setup: settings loaded server=${st.server} line=${st.line} skin=${st.activeSkin}")
        line = st.line

        Patch.refresh(this, st.patchUrl)
        Log2.w(this, "setup: patch refresh queued (${st.patchUrl})")
        TitlebarInject.warmUp(this)
        Log2.w(this, "setup: titlebar asset warmed up")
        configureWeb(web)
        Compat.install(web, this)
        Log2.w(this, "setup: after configureWeb")

        findViewById<android.widget.ImageButton>(R.id.btnReload).setOnClickListener { if (!onLogin) web.reload() }
        findViewById<android.widget.ImageButton>(R.id.btnHome).setOnClickListener {
            if (!onLogin && base.isNotEmpty()) web.loadUrl(Lines.desktopUrl(base))
        }
        findViewById<android.widget.ImageButton>(R.id.btnSettings).setOnClickListener {
            // v5.1.30：标题栏的齿轮也进 PC 同款偏好设置页（原生 SettingsActivity 已不从菜单进入）
            startActivity(Intent(this, SettingsWebActivity::class.java))
        }
        findViewById<android.widget.ImageButton>(R.id.btnMenu).setOnClickListener { showMenuAt(findViewById(R.id.btnMenu)) }

        Log2.w(this, "setup: before applySkin")
        applySkin()

        // 首屏：PC 同款登录页（不自动连桌面，与「登录页作为首屏」的要求保持一致）
        showLoginPage()

        // 桌面快捷方式：把常见应用注册成长按图标可见的动态快捷方式
        try { Shortcuts.syncDynamic(this, st.server) } catch (_: Throwable) {}
        Log2.w(this, "setup: done")
        try { Diag.report(this, "startup", "server=" + Settings.load(this).server) } catch (_: Throwable) {}

        // 冷启动来自「主程序」桌面快捷方式（Shortcuts.mainIntent：OPEN_APP + base）→ 直接进该 NAS 的桌面
        handleShortcutIntent(intent)
        // v5.1.30：偏好设置页里的「切换账号」/「登录其它账号…」会带 connectTo 冷启动本窗
        intent?.getStringExtra("connectTo")?.takeIf { it.isNotBlank() }?.let { menuConnectTo(it) }
        // v5.1.31：真机自检入口（SettingsWebActivity 未导出，adb shell 不能直接 am start）：
        //   adb shell am start -n com.fnos.tablet/.MainActivity --ez openSettings true
        val cold: Intent? = intent
        if (cold != null && cold.getBooleanExtra("openSettings", false)) {
            cold.removeExtra("openSettings")
            startActivity(Intent(this, SettingsWebActivity::class.java))
        }
    }

    // ------------------------------------------------------------------ 登录页

    private fun showLoginPage() {
        onLogin = true
        titlebar.visibility = View.GONE
        web.visibility = View.GONE
        loginWeb.visibility = View.VISIBLE
        Ui.immersive(this, true)
        if (loginScreen == null) {
            val ls = LoginScreen(this, loginWeb) { parsed -> onConnected(parsed) }
            ls.onBackToConnect = { showLoginPage() }
            loginScreen = ls
            ls.start()
        } else {
            // 回到登录页时刷新一下（历史/上次地址可能已变化）
            loginWeb.visibility = View.VISIBLE
            loginScreen?.reload()
        }
        Log2.w(this, "ui: login page")
    }

    /** 登录页 connect() 成功后的回调：切桌面 */
    private fun onConnected(parsed: ServerAddr.Parsed) {
        base = parsed.origin
        line = Settings.load(this).line
        onLogin = false
        loginWeb.visibility = View.GONE
        web.visibility = View.VISIBLE
        titlebar.visibility = View.GONE
        Ui.immersive(this, true)
        fullscreen = true
        tvTitle.text = "飞牛 · $base"
        TitlebarInject.nasOrigin = parsed.origin
        val u = Lines.desktopUrl(base)
        Log2.w(this, "doConnect: loadUrl $u")
        web.loadUrl(u)
        // 登录后补拉 app-center 清单，让快捷方式用上真实应用名/入口
        Apps.refresh(this, base)
        Shortcuts.syncDynamic(this, base)
    }

    private fun showLastCrashIfAny() {
        try {
            val f = java.io.File(filesDir, App.CRASH_FILE)
            if (!f.exists()) return
            val txt = f.readText()
            // 原生诊断上报：Termux/adb 读不到 App 私有目录，改由 App 自己把崩溃摘要发出去
            try { Diag.reportNow(this, "crash", txt.take(600)) } catch (_: Throwable) {}
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("上次崩溃记录")
                .setMessage((txt + "\n\n---- 启动步骤 ----\n" + Log2.tail(this@MainActivity, 30)).take(4000))
                .setPositiveButton("复制并关闭") { _, _ ->
                    try {
                        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", txt))
                        Toast.makeText(this, "已复制，请发回给开发者", Toast.LENGTH_LONG).show()
                    } catch (_: Exception) {}
                    f.delete()
                }
                .setNegativeButton("删除") { _, _ -> f.delete() }
                .show()
        } catch (_: Exception) {}
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWeb(w: WebView) {
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = NasUa.DESKTOP
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        w.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectAppInterceptor(view)
                view?.let { Patch.inject(it) }
                // PC 端自绘标题栏（同一份 titlebar-inject.js）：注入成功后隐藏原生标题栏
                view?.let {
                    TitlebarInject.inject(it, this@MainActivity, true) { ok ->
                        if (ok) {
                            titlebar.visibility = View.GONE
                            Log2.w(this@MainActivity, "setup: titlebar-inject ok")
                        } else {
                            titlebar.visibility = View.VISIBLE
                            Log2.w(this@MainActivity, "setup: titlebar-inject failed, keep native bar")
                        }
                    }
                }
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url?.toString() ?: return false
                return handleAppUrl(u)
            }
        }
        w.addJavascriptInterface(AppBridge(this), "FNOSApp")
        // window.fnosHost：titlebar-inject.js 的 ipcRenderer 替身
        TitlebarInject.attach(w, this, this, true)
    }

    /** 拦截 appview 链接，交给独立的 AppWindowActivity（对应 PC 端的应用窗） */
    private fun handleAppUrl(u: String): Boolean {
        if (u.contains("/appview") || u.contains("/app/")) {
            val anchor = Uri.parse(u).getQueryParameter("anchor")
                ?: u.substringAfter("/app/", "").substringBefore("?").ifBlank { null }
            if (anchor != null) {
                openApp(anchor, u)
                return true
            }
        }
        return false
    }

    private fun injectAppInterceptor(w: WebView?) {
        if (w == null || base.isEmpty()) return
        // 与 PC 端一致：网页内点击应用时由壳接管，开独立窗口
        val js = """
            (function(){
              if (window.__fnosTabletHooked) return; window.__fnosTabletHooked = true;
              document.addEventListener('click', function(e){
                var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
                if (!a) return;
                var href = a.getAttribute('href') || '';
                if (href.indexOf('appview') >= 0 || href.indexOf('/app/') >= 0) {
                  e.preventDefault();
                  try { FNOSApp.open(href); } catch (err) {}
                }
              }, true);
            })();
        """.trimIndent()
        w.evaluateJavascript(js, null)
    }

    fun openApp(anchor: String, url: String) {
        val i = Intent(this, AppWindowActivity::class.java)
        i.putExtra("anchor", anchor)
        i.putExtra("url", url)
        i.putExtra("base", base)
        startActivity(i)
    }

    // -------------------------------------------------------------------- 菜单

    /** 原生标题栏 ☰：锚点取按钮左下角（等价 PC __menuAnchorFrom） */
    private fun showMenuAt(v: View) {
        val loc = IntArray(2)
        try { v.getLocationOnScreen(loc) } catch (_: Exception) {}
        val anchor = PointF(loc[0].toFloat(), (loc[1] + v.height).toFloat() + 2f)
        showMenu(anchor)
    }

    private fun showMenu(anchor: PointF) {
        try {
            menu?.dismiss()
            menu = MenuPopup(this, anchor, this).also { it.show() }
        } catch (e: Throwable) {
            Log2.w(this, "menu: open failed ${e.message}")
        }
    }

    /** 应用皮肤到自绘标题栏（与 PC 端一致：背景图 + 高度） */
    private fun applySkin() {
        try {
            val skin = Skins.activeSkin(this)
            val h = Skins.applyTitlebar(this, titlebar, skin)
            val lp = titlebar.layoutParams
            lp.height = (h * resources.displayMetrics.density).toInt().coerceAtLeast((34 * resources.displayMetrics.density).toInt())
            titlebar.layoutParams = lp
        } catch (_: Exception) {}
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onBackPressed() {
        if (onLogin) { super.onBackPressed(); return }
        if (web.canGoBack()) web.goBack()
        else showLoginPage()
    }

    override fun onResume() {
        super.onResume()
        // 皮肤/标题栏设置在设置页里改过 → 与 PC 端同一通道推给页面
        if (!onLogin) TitlebarInject.pushTitlebarSettings(web, this)
    }

    // ---------------------------------------------------------- TitlebarHost
    // 语义对齐 PC 主进程：close=关窗、minimize=退到后台、maximize=全屏切换

    override fun tbClose() = finish()

    override fun tbMinimize() {
        moveTaskToBack(true)
    }

    override fun tbToggleMaximize() {
        fullscreen = !fullscreen
        Ui.immersive(this, fullscreen)
        TitlebarInject.emit(web, "window:fullscreen-changed", if (fullscreen) "true" else "false")
    }

    override fun tbBackToConnect() = showLoginPage()

    /** 网页自绘标题栏的 ☰（titlebar-inject.js → app-popup-menu），锚点用页面给的 CSS 矩形 */
    override fun tbPopupMenu(anchorJson: String?) {
        val d = resources.displayMetrics.density
        val p = try {
            val o = org.json.JSONObject(anchorJson ?: "null")
            PointF(
                (o.optDouble("left", 8.0) * d).toFloat(),
                (o.optDouble("bottom", 34.0) * d + 2f).toFloat()
            )
        } catch (_: Exception) { PointF(8f * d, 36f * d) }
        showMenu(p)
    }

    override fun tbIsFullscreen(): Boolean = fullscreen

    // ------------------------------------------------------------- MenuTarget

    override val act: AppCompatActivity get() = this

    override fun menuWeb(): WebView? = web

    override fun menuBase(): String = if (base.isNotBlank()) base else Settings.load(this).server

    override fun menuGoHome() {
        if (base.isBlank()) { showLoginPage(); return }
        web.loadUrl(Lines.desktopUrl(base))
    }

    /**
     * 文件 → 锁定 FNOS（PC：仅在有启动密码时出现，锁定后只留锁屏窗）。
     * Android 映射：隐藏桌面内容 + 回到登录页（登录页是本端的「锁屏」）。
     * 解锁＝重新走一次 NAS 登录。应用窗里的「锁定」也会带 lock=true 回到这里。
     */
    override fun menuLock() {
        if (!AppLock.hasPassword(this)) {
            // 与 PC lockApp() 一致：未设置密码时引导去设置页（v5.1.30 起是 PC 同款偏好设置页）
            startActivity(Intent(this, SettingsWebActivity::class.java))
            return
        }
        showLoginPage()
        toast("已锁定 FNOS")
    }

    override fun menuLoginOther() = showLoginPage()

    override fun menuConnectTo(href: String) {
        if (href.isBlank()) { showLoginPage(); return }
        try {
            val parsed = ServerAddr.normalize(href)
            titlebar.visibility = View.GONE
            onConnected(parsed)
            History.remember(this, parsed)
        } catch (e: Exception) {
            toast(e.message ?: "地址无效")
        }
    }

    override fun menuMinimize() {
        moveTaskToBack(true)
    }

    /** 文件 → 一键隐藏 / 呼出（PC 是隐藏全部窗口到托盘；Android 退到后台） */
    override fun menuHideAll() {
        moveTaskToBack(true)
    }

    /** 文件 → 隐藏到托盘（Android 无托盘：退到后台 + 提示） */
    override fun menuHideToTray() {
        moveTaskToBack(true)
        toast("已隐藏到后台")
    }

    override fun menuCloseWindow() = finish()

    override fun menuToggleFullscreen() = tbToggleMaximize()

    /** 被应用窗的「锁定 FNOS」/「切换账号」带回主窗时处理 */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        setIntent(intent)
        if (intent.getBooleanExtra("lock", false)) {
            showLoginPage()
            toast("已锁定 FNOS")
            return
        }
        val c = intent.getStringExtra("connectTo")
        if (!c.isNullOrBlank()) { menuConnectTo(c); return }
        // v5.1.30：偏好设置页 → 账号 →「添加账号」= PC 的 auth:back-to-connect
        if (intent.getBooleanExtra("backToConnect", false)) { showLoginPage(); return }
        // v5.1.31：自检入口（热启动同样生效）
        if (intent.getBooleanExtra("openSettings", false)) {
            intent.removeExtra("openSettings")
            startActivity(Intent(this, SettingsWebActivity::class.java))
            return
        }
        // 桌面「主程序」快捷方式重新点一次（singleTask）：复用本窗并回到对应 NAS
        handleShortcutIntent(intent)
    }

    /**
     * Shortcuts.mainIntent() 的 intent：action=com.fnos.tablet.action.OPEN_APP + base。
     * 冷启动与热启动都走这里；没有 base 就保持登录页。
     */
    private fun handleShortcutIntent(i: Intent?) {
        try {
            if (i == null || i.action != Shortcuts.ACTION_OPEN_APP) return
            val b = i.getStringExtra(Shortcuts.EXTRA_BASE).orEmpty()
            if (b.isBlank()) return
            Log2.w(this, "intent: OPEN_APP base=$b")
            menuConnectTo(b)
        } catch (_: Throwable) {}
    }

    /** JS 桥：网页内点击应用 → 打开独立窗口 */
    inner class AppBridge(private val act: MainActivity) {
        @android.webkit.JavascriptInterface
        fun open(href: String) {
            runOnUiThread {
                val full = if (href.startsWith("http")) href else base + (if (href.startsWith("/")) href else "/$href")
                act.openApp(anchorOf(full) ?: "", full)
            }
        }

        private fun anchorOf(u: String): String? =
            Uri.parse(u).getQueryParameter("anchor") ?: u.substringAfter("/app/", "").substringBefore("?").ifBlank { null }
    }
}
