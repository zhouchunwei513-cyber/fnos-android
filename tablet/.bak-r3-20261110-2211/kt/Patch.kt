package com.fnos.tablet

import android.content.Context
import android.webkit.WebView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 远程补丁加载器（方案 D）：
 *  - 从 patchUrl 拉 version.txt，与本地缓存版本不同才拉 patch.js
 *  - patch.js 是一段 JS，注入到每个 WebView 页面（桌面 / 应用窗），用于热修 UI 逻辑，
 *    不需要重新安装 APK（Kotlin 层仍需装包，但网页层可热更）
 */
object Patch {
    private val pool = Executors.newSingleThreadExecutor()
    @Volatile private var loadedVersion: String = ""
    @Volatile private var cachedJs: String? = null

    private fun cacheDir(ctx: Context) = File(ctx.filesDir, "patch").apply { mkdirs() }
    private fun verFile(ctx: Context) = File(cacheDir(ctx), "version.txt")
    private fun jsFile(ctx: Context) = File(cacheDir(ctx), "patch.js")

    /** 启动时调用：后台检查更新，成功后回调 */
    fun refresh(ctx: Context, patchUrl: String, onReady: (String) -> Unit = {}) {
        if (patchUrl.isBlank()) return
        pool.execute {
            try {
                val base = patchUrl.trimEnd('/')
                val ver = httpGet("$base/version.txt").trim()
                if (ver.isBlank()) return@execute
                val local = try { verFile(ctx).readText().trim() } catch (_: Exception) { "" }
                if (ver != local || cachedJs == null) {
                    val js = httpGet("$base/patch.js")
                    jsFile(ctx).writeText(js)
                    verFile(ctx).writeText(ver)
                    cachedJs = js
                    Log2.w(ctx, "patch: updated to $ver (${js.length} bytes)")
                } else {
                    cachedJs = try { jsFile(ctx).readText() } catch (_: Exception) { "" }
                    Log2.w(ctx, "patch: cached $ver")
                }
                loadedVersion = ver
                onReady(ver)
            } catch (e: Exception) {
                Log2.w(ctx, "patch: refresh failed ${e.message}")
            }
        }
    }

    /** 把补丁注入到页面 */
    fun inject(web: WebView) {
        val js = cachedJs ?: return
        if (js.isBlank()) return
        try { web.evaluateJavascript(js, null) } catch (_: Exception) {}
    }

    fun version(): String = loadedVersion

    private fun httpGet(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 12000
        c.setRequestProperty("User-Agent", "FNOS-Tablet/1.0")
        return try {
            if (c.responseCode >= 400) throw IllegalStateException("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }
}
