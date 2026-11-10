package com.fnos.tablet

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var connectPanel: View
    private lateinit var titlebar: View
    private lateinit var etServer: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvTitle: TextView

    private var base: String = ""
    private var line: String = "lan"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setup()
        } catch (e: Throwable) {
            // v5.1.18：任何初始化异常都落盘 + 就地展示，避免"点开就闪退"看不出原因
            val sw = java.io.StringWriter(); e.printStackTrace(java.io.PrintWriter(sw))
            try { java.io.File(filesDir, App.CRASH_FILE).writeText("onCreate 异常：\n" + sw.toString()) } catch (_: Exception) {}
            val tv = android.widget.TextView(this).apply {
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
        connectPanel = findViewById(R.id.connectPanel)
        titlebar = findViewById(R.id.titlebar)
        etServer = findViewById(R.id.etServer)
        tvStatus = findViewById(R.id.tvStatus)
        tvTitle = findViewById(R.id.tvTitle)

        val st = Settings.load(this)
        Log2.w(this, "setup: settings loaded server=${st.server} line=${st.line} skin=${st.activeSkin}")
        etServer.setText(st.server)
        line = st.line

        Log2.w(this, "setup: before configureWeb")
        configureWeb(web)
        Log2.w(this, "setup: after configureWeb")

        findViewById<Button>(R.id.btnConnect).setOnClickListener { doConnect() }
        findViewById<Button>(R.id.btnLan).setOnClickListener { line = "lan"; etServer.hint = "192.168.31.101:5666" }
        findViewById<Button>(R.id.btnIpv6).setOnClickListener { line = "ipv6"; etServer.hint = "[fe80::1]:5666" }
        findViewById<Button>(R.id.btnFrp).setOnClickListener { line = "frp"; etServer.hint = "ddns.example.com:5666" }
        findViewById<Button>(R.id.btnFnid).setOnClickListener { line = "fnid"; etServer.hint = "FNID（如 atp2025）" }

        findViewById<ImageButton>(R.id.btnReload).setOnClickListener { if (base.isNotEmpty()) web.reload() }
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        Log2.w(this, "setup: before applySkin")
        applySkin()
        Log2.w(this, "setup: done")
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener { showMenu() }

        // 上次已连过则直接进桌面
        if (st.server.isNotBlank()) {
            etServer.setText(st.server)
            doConnect()
        }
    }

    private fun showLastCrashIfAny() {
        try {
            val f = java.io.File(filesDir, App.CRASH_FILE)
            if (!f.exists()) return
            val txt = f.readText()
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
            userAgentString = userAgentString + " FNOS-Tablet/1.0"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        w.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                tvStatus.text = "加载中…"
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                tvStatus.text = ""
                injectAppInterceptor(view)
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url?.toString() ?: return false
                return handleAppUrl(u)
            }
        }
        w.addJavascriptInterface(AppBridge(this), "FNOSApp")
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

    private fun doConnect() {
        Log2.w(this, "doConnect: input=${etServer.text} line=$line")
        val input = etServer.text.toString()
        if (input.isBlank()) { toast("请填写服务器地址"); return }
        val st = Settings.load(this)
        base = Lines.normalize(input, line)
        Settings.save(this, st.copy(server = input, line = line))
        tvStatus.text = "正在连接 $base …"
        connectPanel.visibility = View.GONE
        web.visibility = View.VISIBLE
        tvTitle.text = "飞牛 · $base"
        val u = Lines.desktopUrl(base)
        Log2.w(this, "doConnect: loadUrl $u")
        web.loadUrl(u)
    }

    private fun showMenu() {
        val items = arrayOf("电视直播", "重新连接", "切换服务器", "刷新", "关于")
        AlertDialog.Builder(this)
            .setTitle("菜单")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> {
                        val i2 = Intent(this, LiveActivity::class.java)
                        i2.putExtra("base", if (base.isNotEmpty()) base else Settings.load(this).server)
                        startActivity(i2)
                    }
                    1 -> if (base.isNotEmpty()) web.reload()
                    2 -> { web.visibility = View.GONE; connectPanel.visibility = View.VISIBLE }
                    3 -> web.reload()
                    4 -> AlertDialog.Builder(this).setTitle("飞牛平板")
                        .setMessage("小米平板 5 Pro 适配版\n与 PC 端功能对齐：桌面 / 应用窗 / 设置 / 直播").setPositiveButton("好", null).show()
                }
            }.show()
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

    private fun showSettings() {
        val st = Settings.load(this)
        AlertDialog.Builder(this)
            .setTitle("设置")
            .setMessage("服务器：${st.server}\n线路：${st.line}\n皮肤：${if (st.activeSkin.isBlank()) "无" else st.activeSkin}")
            .setPositiveButton("关闭", null)
            .setNeutralButton("清空服务器") { _, _ ->
                Settings.save(this, st.copy(server = ""))
                connectPanel.visibility = View.VISIBLE
                web.visibility = View.GONE
            }.show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onBackPressed() {
        if (web.visibility == View.VISIBLE && web.canGoBack()) web.goBack()
        else if (web.visibility == View.VISIBLE) { connectPanel.visibility = View.VISIBLE; web.visibility = View.GONE }
        else super.onBackPressed()
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
