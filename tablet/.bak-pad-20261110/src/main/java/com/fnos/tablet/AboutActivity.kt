package com.fnos.tablet

import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast

/**
 * 关于页 —— 与 PC 端**同一份** assets/web/about.html。
 *
 * PC 的 about.html 依赖 preload 暴露的 `window.fnApi`：
 *   fnApi.checkUpdate()   → main.js app:check-update（联网查 GitHub 新版本）
 *   fnApi.closeAbout()    → app:about-close（关窗）
 * 平板没有在线更新通道（发版靠装 APK），所以这里给的是**最小实现**：
 *   checkUpdate  → 弹提示「平板版随 APK 发版」并让页面的 hint 走完
 *   closeAbout   → 关掉本页
 */
class AboutActivity : LocalWebActivity() {

    override fun pageUrl(): String = WebAssets.aboutUrl(appVersion())

    override fun preloadScript(): String = SHIM

    override fun dialogTitle(): String = "关于 FNOS"

    override fun onPrepareWeb(w: WebView) {
        w.addJavascriptInterface(AboutBridge(), "FnAbout")
    }

    inner class AboutBridge {
        @JavascriptInterface
        fun checkUpdate() {
            runOnUiThread {
                try {
                    Toast.makeText(
                        this@AboutActivity,
                        "平板版随 APK 一起发版，暂无在线更新通道；请安装最新版 APK。当前版本 ${appVersion()}",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun close() {
            runOnUiThread { finish() }
        }

        @JavascriptInterface
        fun openRelease() {
            runOnUiThread {
                try {
                    startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/zhouchunwei513-cyber/fnos-desktop")
                        )
                    )
                } catch (_: Exception) {}
            }
        }
    }

    companion object {
        /** PC about.html 读 window.fnApi；这里给最小替身，保证按钮不哑火 */
        val SHIM: String = """
(function () {
  try {
    if (window.fnApi) return;
    if (typeof FnAbout === 'undefined') return;
    window.fnApi = {
      checkUpdate: function () {
        try { FnAbout.checkUpdate(); } catch (e) {}
        return Promise.resolve({ ok: true });
      },
      closeAbout: function () { try { FnAbout.close(); } catch (e) {} },
      openRelease: function () { try { FnAbout.openRelease(); } catch (e) {} }
    };
  } catch (e) {}
})();
        """.trimIndent()
    }
}
