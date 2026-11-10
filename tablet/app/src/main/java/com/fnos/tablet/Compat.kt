package com.fnos.tablet

import android.content.Context
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * 降级兼容加载层：把 assets/compat.js 在【页面脚本之前】注入。
 * - WebView 83+ 用 addDocumentStartJavaScript（真正的最早时机）
 * - 老版本退回 onPageStarted 注入
 */
object Compat {
    private var js: String? = null

    fun script(ctx: Context): String {
        js?.let { return it }
        val s = try {
            ctx.assets.open("compat.js").bufferedReader().use { it.readText() }
        } catch (_: Exception) { "" }
        js = s
        return s
    }

    /** 在 WebView 创建时调用一次 */
    fun install(web: WebView, ctx: Context) {
        val code = script(ctx)
        if (code.isBlank()) return
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(web, code, setOf("*"))
                Log2.w(ctx, "compat: documentStart installed")
                return
            }
        } catch (e: Throwable) {
            Log2.w(ctx, "compat: documentStart failed ${e.message}")
        }
        // 兜底：页面开始时注入
        try { web.evaluateJavascript(code, null) } catch (_: Throwable) {}
        Log2.w(ctx, "compat: onPageStarted fallback")
    }
}
