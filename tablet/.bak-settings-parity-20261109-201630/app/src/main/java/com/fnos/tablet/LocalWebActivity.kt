package com.fnos.tablet

import android.content.Intent
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

    /** 应用版本号（help.html 页脚 / about.html 版本行都用 ?v= 传进来） */
    protected fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (_: Exception) { "" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val w = buildWebView()
        web = w
        setContentView(w)
        onPrepareWeb(w)
        val js = preloadScript()
        if (js.isNotBlank()) documentStart(w, js)
        w.loadUrl(pageUrl())
        Log2.w(this, "localweb: load ${pageUrl()}")
    }

    private fun buildWebView(): WebView {
        val w = WebView(this)
        w.setBackgroundColor(0xFF0B0D12.toInt())
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // PC 这些页面是按 780px 宽的窗口设计的（settings-card max-width:726px）。
            // 这里按设备 CSS 宽度排版（而不是 980px 缩放），平板上观感与 PC 窗口最接近。
            useWideViewPort = false
            loadWithOverviewMode = false
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = true
            // 允许双指缩放（不显示 +/- 按钮）：长文档窗口里偶尔要放大看细节
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            // PC 端是桌面版页面（固定宽度卡片 + 玻璃拟态），这里保持桌面 UA 避免 NAS 侧降级
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
        runOnUiThread {
            try { web.evaluateJavascript(js, null) } catch (e: Throwable) {
                Log2.w(this, "localweb: evalJs failed ${e.message}")
            }
        }
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
