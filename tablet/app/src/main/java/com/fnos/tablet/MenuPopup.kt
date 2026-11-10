package com.fnos.tablet

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.graphics.PointF
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * PC 端自绘菜单（assets/web/menu-popup.html + menu-popup.js，与 PC 仓库同一份）。
 *
 * PC 是「无边框透明 BrowserWindow + preload 暴露 window.fnMenu」，Android 这里是
 * 「全屏透明 Dialog + 透明 WebView + addJavascriptInterface 暴露 window.fnMenu」：
 *   menu-popup.js → window.fnMenu.ready({w,h})     ← 面板量出尺寸（含 18px 阴影边距）
 *   menu-popup.js → window.fnMenu.invoke(id)       ← 点叶子项
 *   menu-popup.js → window.fnMenu.close()          ← 点空白 / Esc
 *   menu-popup.js → window.fnMenu.diag(tag,data)   ← 自证日志
 *   menu-popup.js → window.fnMenu.onData(cb)       ← 注册数据回调（原生 __fnMenuPush 推送）
 *   menu-popup.js → window.fnMenu.onRemeasure(cb)  ← 原生 __fnMenuRemeasure 触发重测
 *
 * 布局等价 PC 的「菜单窗左上角贴 ☰ 按钮左下角」：拿到 ready 尺寸后把 #root 平移到位
 * （PC 用 setBounds 移动窗口；Android 用 transform 平移面板，视觉结果一致）。
 */
class MenuPopup(
    private val act: AppCompatActivity,
    private val anchor: PointF,
    private val target: MenuTarget
) {
    private var dlg: Dialog? = null
    private var web: WebView? = null
    private val loader by lazy { WebAssets.loader(act) }
    private val main = Handler(Looper.getMainLooper())
    private var pageReady = false
    private var dismissed = false

    private fun density(): Float = act.resources.displayMetrics.density

    @SuppressLint("SetJavaScriptEnabled")
    fun show() {
        if (dlg != null) return
        val d = Dialog(act, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root = FrameLayout(act)
        root.setBackgroundColor(Color.TRANSPARENT)
        val w = WebView(act)
        w.setBackgroundColor(Color.TRANSPARENT)
        w.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        w.isVerticalScrollBarEnabled = false
        w.isHorizontalScrollBarEnabled = false
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = NasUa.DESKTOP
            allowFileAccess = true
            setSupportZoom(false)
        }
        root.addView(
            w,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        d.setContentView(root)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        d.setOnDismissListener { cleanup() }
        d.setOnShowListener {
            try {
                // v5.1.31 修复：decorView 的 click 监听会把"打开菜单的这一次点击"也吃掉 → 菜单闪一下就消失。
        // 改为标准的外部点击取消（点对话框外区域才关闭），菜单内部点击不受影响。
        d.setCanceledOnTouchOutside(true)
            } catch (_: Throwable) {}
        }

        // 面板宽度：内容自适应（最长条目文字 + 内边距），上限 42vw 横屏 / 78vw 竖屏。
        // 旧版把一级面板写死 34vw/66vw，文字只占一小截、右侧大片空白（用户反馈）。
        val menuCss = PadCss.menuCss(
            act.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        )

        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?) =
                WebAssets.intercept(loader, request) ?: super.shouldInterceptRequest(view, request)

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pageReady = true
                // 页面自带的 <style> 解析完再注入一次：PadCss.script 会把我们的 style 移到末尾，
                // 同优先级 !important 时我们赢（menu-popup.html 里那条 width:34vw 就是靠这个压掉的）。
                try { (view ?: w).evaluateJavascript(PadCss.script(menuCss), null) } catch (_: Throwable) {}
                pushData()
            }
        }
        w.addJavascriptInterface(Bridge(), "FnosMenu")
        // window.fnMenu 必须在 menu-popup.js 之前就绪（脚本末尾就调 window.fnMenu.onData）
        try {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(w, SHIM, setOf("*"))
            } else {
                w.evaluateJavascript(SHIM, null)
            }
        } catch (e: Throwable) {
            Log2.w(act, "menu: shim install failed ${e.message}")
            try { w.evaluateJavascript(SHIM, null) } catch (_: Throwable) {}
        }

        injectCss(w, PadCss.script(menuCss))

        web = w
        dlg = d
        try {
            d.show()
        } catch (e: Throwable) {
            Log2.w(act, "menu: show failed ${e.message}")
        }
        w.loadUrl(WebAssets.menuUrl())
        Log2.w(act, "menu: opening at ${anchor.x},${anchor.y}")
    }

    fun dismiss() {
        if (dismissed) return
        dismissed = true
        try { dlg?.dismiss() } catch (_: Throwable) {}
    }

    private fun cleanup() {
        try {
            web?.let {
                (it.parent as? ViewGroup)?.removeView(it)
                it.destroy()
            }
        } catch (_: Throwable) {}
        web = null
        dlg = null
    }

    /** 推数据（等价 PC win.webContents.send('fnmenu:data', payload)） */
    private fun pushData() {
        val w = web ?: return
        val json = try { MenuModel.payload(act).toString() } catch (e: Throwable) {
            Log2.w(act, "menu: payload failed ${e.message}"); return
        }
        try {
            w.evaluateJavascript("window.__fnMenuPush&&window.__fnMenuPush(${JSONObject.quote(json)})", null)
        } catch (_: Throwable) {}
    }

    /** 面板尺寸就绪 → 平移 #root 到 ☰ 下方（等价 PC setBounds(__menuPlace(w,h))） */
    /** 文档开始注入（优先 DOCUMENT_START_SCRIPT，老 WebView 退回 evaluateJavascript） */
    private fun injectCss(w: WebView, js: String) {
        try {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(w, js, setOf("*"))
                return
            }
        } catch (_: Throwable) {}
        try { w.evaluateJavascript(js, null) } catch (_: Throwable) {}
    }

    private fun placePanel(cssW: Int, cssH: Int) {
        val w = web ?: return
        val dm = act.resources.displayMetrics
        // 真机验收用：面板真实宽高（CSS px）+ 屏宽占比
        run {
            val ratio = if (dm.widthPixels > 0) (cssW * density() * 100f / dm.widthPixels) else 0f
            Log2.w(act, "menu: panel ${cssW}x${cssH} css, screen=${dm.widthPixels}x${dm.heightPixels} ratio=${String.format(java.util.Locale.US, "%.1f", ratio)}%")
        }
        val d = density()
        val pw = cssW * d
        val ph = cssH * d
        var x = anchor.x
        var y = anchor.y
        val maxX = dm.widthPixels - pw
        val maxY = dm.heightPixels - ph
        if (x > maxX) x = maxX
        if (y > maxY) y = maxY
        if (x < 0f) x = 0f
        if (y < 0f) y = 0f
        val cssX = (x / d)
        val cssY = (y / d)
        // 面板在页面内画在 (PAD,PAD) = (18,18)：整体反向补偿，使面板左上角正好落在锚点
        val js = "(function(){var r=document.getElementById('root');if(r){r.style.transform='translate(" +
            (cssX - 18f) + "px," + (cssY - 18f) + "px)';}})()"
        try { w.evaluateJavascript(js, null) } catch (_: Throwable) {}
    }

    private inner class Bridge {
        @JavascriptInterface
        fun call(method: String, argsJson: String?, reqId: String?) {
            val args = try { JSONObject(argsJson ?: "{}") } catch (_: Exception) { JSONObject() }
            when (method) {
                "ready" -> {
                    val cw = args.optInt("w", 0)
                    val ch = args.optInt("h", 0)
                    main.post { if (cw > 0 && ch > 0) placePanel(cw, ch) }
                }
                "invoke" -> {
                    val id = args.optString("id", "")
                    Log2.w(act, "menu: invoke $id")
                    main.post {
                        dismiss()
                        if (id.isNotBlank()) {
                            try { MenuActions.perform(target, id) } catch (e: Throwable) {
                                Log2.w(act, "menu: action $id failed ${e.message}")
                            }
                        }
                    }
                }
                "close" -> main.post { dismiss() }
                "diag" -> {
                    val tag = args.optString("tag", "menu.diag")
                    Log2.w(act, "menu-diag: $tag ${args.optJSONObject("data")?.toString()?.take(300) ?: ""}")
                }
                "remeasure" -> main.post {
                    try { web?.evaluateJavascript("window.__fnMenuRemeasure&&window.__fnMenuRemeasure()", null) } catch (_: Throwable) {}
                }
                else -> {}
            }
        }
    }

    companion object {
        /** Electron preload（menu-popup-preload.js）的 Android 替身 */
        val SHIM: String = """
(function(){
  try {
    if (window.__fnMenuBridge) return;
    window.__fnMenuBridge = true;
    var dcb = [], rcb = [];
    window.__fnMenuPush = function (json) {
      var d = null;
      try { d = JSON.parse(json); } catch (e) { d = null; }
      for (var i = 0; i < dcb.length; i++) { try { dcb[i](d); } catch (e) {} }
    };
    window.__fnMenuRemeasure = function () {
      for (var i = 0; i < rcb.length; i++) { try { rcb[i](); } catch (e) {} }
    };
    function call(m, a) { try { FnosMenu.call(String(m), JSON.stringify(a || {}), ''); } catch (e) {} }
    window.fnMenu = {
      onData: function (cb) { if (typeof cb === 'function') dcb.push(cb); },
      onRemeasure: function (cb) { if (typeof cb === 'function') rcb.push(cb); },
      ready: function (size) { call('ready', size); },
      invoke: function (id) { call('invoke', { id: id }); },
      close: function () { call('close', {}); },
      diag: function (tag, data) { call('diag', { tag: tag, data: data }); }
    };
  } catch (e) {}
})();
        """.trimIndent()
    }
}
