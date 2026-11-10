package com.fnos.tablet

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader

/**
 * 壳内本地页面（PC 端 login.html / menu-popup.html / settings.html / help.html / about.html）的加载通道。
 *
 * 为什么不用 file:///android_asset/：
 *   targetSdk 34 上 WebSettings.allowFileAccess 默认 false，file:// 页面的
 *   子资源（login.css / login.js / assets/fnos-logo.png）会被直接拦掉，页面只剩白底；
 *   而且 file:// 源下 localStorage / fetch 受限。
 * WebViewAssetLoader 用虚拟源站 https://appassets.androidplatform.net/ 提供 assets，
 * 相对路径、localStorage、字体回退全都与 PC 端在 Electron 里 loadFile 的表现一致。
 */
object WebAssets {

    const val HOST = "appassets.androidplatform.net"

    /** 与 assets/web/ 目录对应 */
    const val BASE = "https://$HOST/assets/web/"

    fun loginUrl(): String = BASE + "login.html"
    fun menuUrl(): String = BASE + "menu-popup.html"

    /** v5.1.30：偏好设置页（PC 同款 settings.html） */
    fun settingsUrl(): String = BASE + "settings.html"

    /** v5.1.30：帮助页（PC 同款 help.html） */
    fun helpUrl(): String = BASE + "help.html"

    /** v5.1.30：关于页（PC 同款 about.html）；ver 走 query 传，页面脚本自己读 ?v= */
    fun aboutUrl(version: String): String = BASE + "about.html?v=" + android.net.Uri.encode(version)

    /**
     * v5.1.33：电视直播页（PC 原版 live.html 整页搬入 assets/web/）。
     * 页面里的 `<script src="vendor/hls.min.js">` 走相对路径 → assets/web/vendor/hls.min.js，
     * 由同一个 AssetsPathHandler 提供。
     */
    fun liveUrl(): String = BASE + "live.html"

    fun loader(ctx: Context): WebViewAssetLoader =
        WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx.applicationContext))
            .build()

    /** WebViewClient.shouldInterceptRequest 里调用；返回 null 表示交回默认加载 */
    fun intercept(l: WebViewAssetLoader?, req: WebResourceRequest?): WebResourceResponse? {
        if (l == null || req == null) return null
        return try { l.shouldInterceptRequest(req.url) } catch (_: Throwable) { null }
    }

    fun isLocal(url: String?): Boolean = url != null && url.startsWith(BASE)
}
