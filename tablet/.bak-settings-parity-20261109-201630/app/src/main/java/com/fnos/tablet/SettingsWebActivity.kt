package com.fnos.tablet

import android.net.Uri
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject

/**
 * 偏好设置页 —— 与 PC 端**同一份** assets/web/settings.html（+ settings.css / settings.js / lock.css）。
 *
 * PC 是 `BrowserWindow.loadFile('settings.html')` + `settings-preload.js`；
 * 这里是 WebView + WebViewAssetLoader + `window.fnosSettings`（SettingsBridge）。
 * 页面本身不做任何 Android 特化，所以 PC 上「界面/标题栏/皮肤/播放器/账号/快捷方式/维护」
 * 七块设置的分组、文案、交互与 PC 完全一致。
 *
 * 入口：菜单「设置 → 偏好设置…」（MenuActions 的 set.prefs）。
 * 原来的原生 SettingsActivity 保留但不再从菜单进入（诊断导出等功能还在里面）。
 */
class SettingsWebActivity : LocalWebActivity() {

    private val bridge: SettingsBridge by lazy { SettingsBridge(this) }

    /** 皮肤包选择器（对应 PC settings:install-skin 里的 dialog.showOpenDialog） */
    private val pickSkin = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) {
            bridge.resolve("installSkin", JSONObject().put("ok", false).put("canceled", true))
            return@registerForActivityResult
        }
        val name = Skins.installFromZip(this, uri)
        val r = JSONObject()
        if (name != null) {
            r.put("ok", true).put("name", name)
            Log2.w(this, "settings: skin installed=$name")
        } else {
            r.put("ok", false).put("error", "安装失败：皮肤包里没有 manifest.json")
        }
        bridge.resolve("installSkin", r)
    }

    override fun pageUrl(): String = WebAssets.settingsUrl()

    override fun preloadScript(): String = SettingsBridge.SHIM

    override fun dialogTitle(): String = "FNOS 设置"

    override fun onPrepareWeb(w: WebView) {
        w.addJavascriptInterface(bridge, SettingsBridge.IFACE)
        // 顺手刷一遍长按图标的动态快捷方式（应用清单可能刚更新过）
        try { Shortcuts.syncDynamic(this, Settings.load(this).server) } catch (_: Throwable) {}
        Log2.w(this, "settings: bridge ready")
    }

    /** SettingsBridge.installSkin 选文件时回调 */
    internal fun pickSkinZip() {
        try {
            pickSkin.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        } catch (e: Throwable) {
            Log2.w(this, "settings: pickSkinZip failed ${e.message}")
            bridge.resolve(
                "installSkin",
                JSONObject().put("ok", false).put("error", "打不开文件选择器：${e.message}")
            )
        }
    }

    /** clearCache：只清 WebView 缓存，不动 Cookie / localStorage（登录态必须留着） */
    internal fun clearWebCache() {
        try { web.clearCache(true) } catch (_: Throwable) {}
        Log2.w(this, "settings: web cache cleared")
    }
}
