package com.fnos.tablet

import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * 「本地 PC 网页」宿主基类：偏好设置页 / 帮助 / 关于 三个页面共用。
 *
 * 与 PC 端的对应关系：
 *   Electron BrowserWindow + loadFile(dist/settings.html) + preload(contextBridge)
 *   ↓
 *   Android WebView + WebViewAssetLoader(https://appassets.androidplatform.net/assets/web/)
 *   + addJavascriptInterface(FnosBridge) + addDocumentStartJavaScript(窗口桥的替身)
 *
 * 两个必须处理的差异：
 *  1) 页面脚本（settings.js 等）在解析末尾就执行初始化，所以 window.fnos* 桥必须在
 *     **文档开始** 前装好 —— 走 WebViewCompat.addDocumentStartJavaScript（老 WebView 退回
 *     evaluateJavascript，与 Compat.install 同一套降级策略）。
 *  2) settings.js 里用了 alert()/confirm()（删除皮肤、清播放记录、账号移除都要确认）。
 *     WebView 默认 **静默吞掉** 这两个对话框，confirm() 直接返回 false，
 *     于是「点确认没反应」。这里用原生 AlertDialog 接管。
 *
 * v5.1.32 平板改造（对应需求 2/3/4）：
 *  · 消闪屏：三处对齐才不闪 —— ① window 背景色；② WebView 背景色；③ 页面 body 背景。
 *    以前只有 ② 是深色，① 还是主题的 #0B0D12、③ 有的是 #000，首帧就会看到颜色跳一下。
 *    现在 ① 在 setContentView 之前就设成 pageBackgroundColor()，并且 WebView 首帧
 *    完成（onPageCommitVisible / onPageFinished / 900ms 兜底）之前 alpha=0，
 *    完成后 120ms 淡入 —— 任何情况下用户都看不到"白一下/黑一下"。
 *  · 平板适配：padCss() 由子类选 PadCss.HELP / SETTINGS / ABOUT，文档开始注入。
 *  · 横竖屏：Manifest 已声明 configChanges（不重建），这里再补 onConfigurationChanged
 *    里重算布局 + 重注入 CSS，并保存/恢复 WebView 状态，避免极端情况下重建丢页面。
 */
abstract class LocalWebActivity : AppCompatActivity() {

    protected lateinit var web: WebView
    private val loader: WebViewAssetLoader by lazy { WebAssets.loader(this) }

    /** 要加载的本地页面地址（WebAssets.settingsUrl / helpUrl / aboutUrl…） */
    protected abstract fun pageUrl(): String

    /** 页面脚本执行【之前】注入的 JS（window.fnosSettings / window.fnApi 的替身）；空串=不注入 */
    protected open fun preloadScript(): String = ""

    /** 额外的 WebView 初始化（addJavascriptInterface 等） */
    protected open fun onPrepareWeb(w: WebView) {}

    /** JS 对话框里的标题（默认取页面标题） */
    protected open fun dialogTitle(): String = "FNOS"

    /** WebView 底色（页面 CSS 若为透明，露出的就是它）。必须与页面 body 背景一致 */
    protected open fun pageBackgroundColor(): Int = 0xFF0B0D12.toInt()

    /**
     * 布局视口宽度（CSS px）。0 = 用设备默认宽度。
     *
     * PC 的偏好设置窗固定 780×860，settings.css 的 .settings-card max-width:726px 就是按
     * 「780 - 左右各 27 留白」算出来的。
     *
     * v5.1.32：平板端一律返回 0（设备视口）+ PadCss 放大字号/放宽卡片，
     * 不再把 780 硬搬过来整体缩放 —— 那样字是大了，但排版比例仍是 PC 窗口的，
     * 横屏两侧空一大片，旋转也不会重排。
     */
    protected open fun layoutViewportWidth(): Int = 0

    /** 平板适配 CSS（PadCss.HELP / SETTINGS / ABOUT）；返回空串表示不注入 */
    protected open fun padCss(): String = ""

    /** 应用版本号（help.html 页脚 / about.html 版本行都用 ?v= 传进来） */
    protected fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (_: Exception) { "" }

    private var revealed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bg = pageBackgroundColor()
        // ① window 背景：必须在 setContentView 之前，否则第一帧露的是主题的 windowBackground
        try {
            window.setBackgroundDrawable(ColorDrawable(bg))
            window.statusBarColor = bg
            window.navigationBarColor = bg
        } catch (_: Throwable) {}

        val w = buildWebView()
        web = w
        setContentView(w)
        onPrepareWeb(w)
        val js = preloadScript()
        if (js.isNotBlank()) documentStart(w, js)
        val pad = padCss()
        if (pad.isNotBlank()) documentStart(w, PadCss.script(pad))

        val restored = try { savedInstanceState != null && w.restoreState(savedInstanceState) != null } catch (_: Throwable) { false }
        if (!restored) w.loadUrl(pageUrl())
        if (pad.isNotBlank()) w.postDelayed({ safeEval(PadCss.script(pad)) }, 260)

        // ③ 兜底：页面再慢也不让用户干等黑屏
        w.postDelayed({ reveal() }, 900)
        Log2.w(this, "localweb: load ${pageUrl()} restored=$restored bg=${Integer.toHexString(bg)}")
    }

    private fun reveal() {
        if (revealed) return
        revealed = true
        try {
            web.animate().alpha(1f).setDuration(120).start()
        } catch (_: Throwable) {
            try { web.alpha = 1f } catch (_: Throwable) {}
        }
    }

    private fun buildWebView(): WebView {
        val w = WebView(this)
        // ② WebView 自身底色：首帧渲染前/页面透明处露出的就是它
        w.setBackgroundColor(pageBackgroundColor())
        // 首帧完成前不显示：彻底消除"先白后黑"
        w.alpha = 0f
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            val vp = layoutViewportWidth()
            useWideViewPort = vp > 0
            loadWithOverviewMode = vp > 0
            // 字号钉死 100%：系统「显示大小/字体大小/无障碍」不参与
            textZoom = 100
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            userAgentString = NasUa.DESKTOP
        }
        w.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        w.isVerticalScrollBarEnabled = true
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ) = WebAssets.intercept(loader, request) ?: super.shouldInterceptRequest(view, request)

            /** 页面里的外链（开源仓库等）交给系统浏览器，别把本地壳顶掉 */
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val u = request?.url?.toString() ?: return false
                if (WebAssets.isLocal(u)) return false
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                    true
                } catch (_: Exception) { false }
            }

            /** 首帧像素已上屏：可以淡入了 */
            override fun onPageCommitVisible(view: WebView?, url: String?) {
                reveal()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // 页面可能把 <style> 清掉，补一次；再兜底淡入
                val pad = padCss()
                if (pad.isNotBlank()) safeEval(PadCss.script(pad))
                reveal()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                // 加载失败也要显示（否则一直 alpha=0 像卡死）
                if (request?.isForMainFrame == true) reveal()
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                AlertDialog.Builder(this@LocalWebActivity)
                    .setTitle(dialogTitle())
                    .setMessage(message ?: "")
                    .setCancelable(false)
                    .setPositiveButton("好") { _, _ -> try { result?.confirm() } catch (_: Throwable) {} }
                    .setOnCancelListener { try { result?.cancel() } catch (_: Throwable) {} }
                    .show()
                return true
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                AlertDialog.Builder(this@LocalWebActivity)
                    .setTitle(dialogTitle())
                    .setMessage(message ?: "")
                    .setCancelable(false)
                    .setPositiveButton("确定") { _, _ -> try { result?.confirm() } catch (_: Throwable) {} }
                    .setNegativeButton("取消") { _, _ -> try { result?.cancel() } catch (_: Throwable) {} }
                    .setOnCancelListener { try { result?.cancel() } catch (_: Throwable) {} }
                    .show()
                return true
            }

            override fun onConsoleMessage(m: android.webkit.ConsoleMessage?): Boolean {
                if (m != null) Log2.w(this@LocalWebActivity, "webconsole(${m.messageLevel()}): ${m.message()}")
                return true
            }
        }
        return w
    }

    /** 文档开始注入（与 MenuPopup / Compat 同一套降级） */
    protected fun documentStart(w: WebView, js: String) {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(w, js, setOf("*"))
                Log2.w(this, "localweb: documentStart installed (${js.length} bytes)")
                return
            }
        } catch (e: Throwable) {
            Log2.w(this, "localweb: documentStart failed ${e.message}")
        }
        try { w.evaluateJavascript(js, null) } catch (_: Throwable) {}
        Log2.w(this, "localweb: evaluateJavascript fallback")
    }

    /** 主线程执行一段 JS（桥回调里用；SettingsBridge 直接调） */
    internal fun evalJs(js: String) {
        runOnUiThread { safeEval(js) }
    }

    private fun safeEval(js: String) {
        try { web.evaluateJavascript(js, null) } catch (e: Throwable) {
            Log2.w(this, "localweb: evalJs failed ${e.message}")
        }
    }

    /**
     * 横竖屏：Manifest 已声明 configChanges（Activity 不重建、WebView 不重载），
     * 这里只做两件事 —— 让 WebView 重新测量 + 重新注入平板 CSS（双列/单列切换）。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        try {
            web.requestLayout()
            web.post {
                safeEval("window.dispatchEvent(new Event('resize'));")
                safeEval("try{window.dispatchEvent(new Event('orientationchange'));}catch(e){}")
                val pad = padCss()
                if (pad.isNotBlank()) safeEval(PadCss.script(pad))
            }
        } catch (_: Throwable) {}
        Log2.w(
            this,
            "localweb: configChanged o=${newConfig.orientation} w=${newConfig.screenWidthDp} h=${newConfig.screenHeightDp}"
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 页面状态（滚动位置/表单）随 Activity 一起存，极端重建时不用重载
        try { web.saveState(outState) } catch (_: Throwable) {}
    }

    @Deprecated("兼容旧 API")
    override fun onBackPressed() {
        finish()
    }

    override fun onDestroy() {
        try {
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            web.destroy()
        } catch (_: Throwable) {}
        super.onDestroy()
    }
}
