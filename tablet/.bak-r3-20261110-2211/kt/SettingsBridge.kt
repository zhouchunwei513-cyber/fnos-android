package com.fnos.tablet

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * `window.fnosSettings` 的 Android 实现（PC 端 settings-preload.js 的等价物）。
 *
 * PC 侧是 Electron contextBridge + ipcMain.handle，这里是
 * addJavascriptInterface(FnosBridge) + SettingsBridge.SHIM（把 30 个具名方法映射过来）。
 * 每个方法的**返回结构逐一对齐** PC 主进程：
 *
 *   getSettings            ← settings:get             （整份界面/皮肤/播放器状态）
 *   setUIOptions           ← settings:set-ui-options  {ok}
 *   setShortcuts           ← settings:set-shortcuts   {ok}
 *   setUrlRewrites         ← settings:set-url-rewrites{ok}
 *   setAutoLock            ← settings:set-auto-lock   {ok,autoLockMinutes}
 *   setPassword            ← settings:set-password    {ok,error}
 *   setKeepLogin           ← settings:set-keep-login  {ok,keepLogin}
 *   setShortcutHideMode    ← settings:set-shortcut-hide-mode {ok,mode}
 *   setFpkApi              ← settings:set-fpk-api     {ok,fpkApi}
 *   testFpkApi             ← settings:test-fpk-api    {ok,error}
 *   iptvSetConfig          ← iptv:set-config          {ok,iptv}
 *   setVlc                 ← settings:set-vlc         {ok,mpv,vlc,warning}
 *   vlcRuntime             ← settings:vlc-runtime     {available,platform,…}
 *   pickMpvExe             ← settings:pick-mpv-exe    {ok,canceled|path}
 *   clearProgress          ← settings:clear-progress  {ok,cleared}
 *   clearCache             ← settings:clear-cache     {ok,bytes}
 *   getAutoStart           ← settings:get-autostart   {success,msg,data}
 *   setAutoStart/setAutoLaunch ← 同上 / set-auto-launch {success,msg}
 *   listAccounts           ← account:list             {success,msg,data[]}
 *   getActiveAccount       ← account:get-active       {success,msg,data}
 *   switchAccount          ← account:switch           {success,msg}
 *   removeAccount          ← account:remove           {success,msg}
 *   backToConnect          ← auth:back-to-connect     {ok}
 *   getInstalledApps       ← get-installed-apps       {success,msg,data[]}
 *   createDesktopShortcut  ← create-desktop-shortcut  {success,msg,data}
 *   listSkins              ← settings:list-skins      {ok,skins[],activeSkin}
 *   installSkin            ← settings:install-skin    {ok,name} / {ok,canceled}
 *   deleteSkin             ← settings:delete-skin     {ok,name,wasActive}
 *   previewTitleBar        ← settings:preview-titlebar{ok}
 *   close                  ← settings:close           —
 *
 * 注：@JavascriptInterface 的方法只能在 JavaBridge 线程里同步返回，
 * 所以「需要开系统文件选择器」的 installSkin 走「先返回 {__pending:true} + 事后回调
 * window.__fnosSettingsResolve(id, json)」；UI 类动作一律 post 到主线程。
 */
class SettingsBridge(private val host: SettingsWebActivity) {

    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun call(method: String, argsJson: String?): String {
        val out = try {
            dispatch(method, argsJson)
        } catch (e: Throwable) {
            Log2.w(host, "bridge: $method failed ${e.message}")
            JSONObject().put("ok", false).put("error", e.message ?: e.toString())
        }
        return out.toString()
    }

    // ------------------------------------------------------------------ 分发

    private fun dispatch(method: String, argsJson: String?): JSONObject {
        val env = try { JSONObject(argsJson ?: "{}") } catch (_: Exception) { JSONObject() }
        val a = env.optJSONArray("a") ?: JSONArray()
        fun obj(i: Int): JSONObject = a.optJSONObject(i) ?: JSONObject()
        fun str(i: Int): String = if (a.isNull(i)) "" else a.optString(i, "")
        fun bool(i: Int): Boolean = a.optBoolean(i, false)

        return when (method) {
            // ---------------------------------------------------------- 读设置
            "getSettings" -> dump()

            // ---------------------------------------------------------- 写设置
            "setUIOptions" -> setUiOptions(obj(0))
            "setShortcuts" -> {
                val sc = JSONObject()
                    .put("lockApp", obj(0).optString("lockApp", "").trim())
                    .put("hideAll", obj(0).optString("hideAll", "").trim())
                Settings.merge(host, JSONObject().put("shortcuts", sc))
                Log2.w(host, "bridge: setShortcuts $sc")
                ok()
            }
            "setUrlRewrites" -> {
                val list = if (a.length() > 0) a.optJSONArray(0) ?: JSONArray() else JSONArray()
                val clean = JSONArray()
                for (i in 0 until list.length()) {
                    val r = list.optJSONObject(i) ?: continue
                    val m = r.optString("match", "").trim()
                    val rep = r.optString("replace", "").trim()
                    if (m.isEmpty() || rep.isEmpty()) continue
                    if (!isHttpUrl(rep)) {
                        return JSONObject().put("ok", false).put("error", "右侧地址无效：$rep")
                    }
                    clean.put(JSONObject().put("match", m).put("replace", rep))
                }
                Settings.merge(host, JSONObject().put("urlRewrites", clean))
                ok()
            }
            "setAutoLock" -> {
                val m = clampInt(a.opt(0), 0, 240, 0)
                Settings.merge(host, JSONObject().put("autoLockMinutes", m))
                JSONObject().put("ok", true).put("autoLockMinutes", m)
            }
            "setPassword" -> setPassword(obj(0).optString("oldPassword", ""), obj(0).optString("newPassword", ""))
            "setKeepLogin" -> {
                val on = bool(0)
                Settings.merge(host, JSONObject().put("keepLogin", on))
                JSONObject().put("ok", true).put("keepLogin", on)
            }
            "setShortcutHideMode" -> {
                val mode = if (str(0) == "minimize") "minimize" else "tray"
                Settings.merge(host, JSONObject().put("shortcutHideMode", mode))
                JSONObject().put("ok", true).put("mode", mode)
            }
            "setFpkApi" -> {
                val cur = Settings.raw(host).optJSONObject("fpkApi") ?: JSONObject()
                val p = obj(0)
                val keys = p.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = p.opt(k)
                    if (v != null && v !== JSONObject.NULL) cur.put(k, v)
                }
                Settings.merge(host, JSONObject().put("fpkApi", cur))
                JSONObject().put("ok", true).put("fpkApi", cur)
            }
            "testFpkApi" -> testFpk()
            "iptvSetConfig" -> {
                val cur = Settings.raw(host).optJSONObject("iptv") ?: JSONObject()
                val p = obj(0)
                val keys = p.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = p.opt(k)
                    if (v != null && v !== JSONObject.NULL) cur.put(k, v)
                }
                Settings.merge(host, JSONObject().put("iptv", cur))
                JSONObject().put("ok", true).put("iptv", cur)
            }
            "setVlc" -> {
                val cur = Settings.raw(host).optJSONObject("mpv") ?: JSONObject()
                val p = obj(0)
                val keys = p.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = p.opt(k)
                    if (v != null && v !== JSONObject.NULL) cur.put(k, v)
                }
                Settings.merge(host, JSONObject().put("mpv", cur))
                val m = mpvSettings(Settings.raw(host))
                JSONObject().put("ok", true).put("mpv", m).put("vlc", m).put("warning", "")
            }
            "vlcRuntime" -> {
                val m = mpvSettings(Settings.raw(host))
                JSONObject()
                    .put("available", false)
                    .put("reason", "Android 端用系统播放器（ExoPlayer）接管，无需外部 mpv 内核")
                    .put("version", "")
                    .put("source", "")
                    .put("platform", "android")
                    .put("mpv", false)
                    .put("gpu", JSONObject())
                    .put("settings", m)
                    .put("custom", false)
                    .put("customMissing", false)
                    .put("hwDecode", m.optString("hwDecode", "auto"))
                    .put("cacheLevel", m.optString("cacheLevel", "smooth"))
                    .put("alwaysUse", m.optBoolean("alwaysUse", false))
                    .put("subStyle", m.optJSONObject("subStyle") ?: JSONObject())
                    .put("exePath", m.optString("exePath", ""))
                    .put("resume", m.optBoolean("resume", true))
                    .put("progressCount", 0)
            }
            "pickMpvExe" -> JSONObject().put("ok", false)
                .put("error", "Android 端固定使用系统播放器，不支持指定 mpv 内核")

            // ---------------------------------------------------------- 维护
            "clearProgress" -> {
                // Android 端播放进度由系统播放器自己管，这里没有本地进度库
                JSONObject().put("ok", true).put("cleared", 0)
            }
            "clearCache" -> {
                main.post {
                    try { host.clearWebCache() } catch (_: Throwable) {}
                }
                JSONObject().put("ok", true).put("bytes", 0)
            }

            // ---------------------------------------------------------- 自启动
            "getAutoStart" -> {
                val on = Settings.raw(host).optBoolean("autoLaunch", false)
                JSONObject().put("success", true).put("msg", "").put("data", on)
            }
            "setAutoStart" -> {
                val on = bool(0)
                Settings.merge(host, JSONObject().put("autoLaunch", on))
                JSONObject().put("ok", true).put("enabled", on)
            }
            "setAutoLaunch" -> {
                val on = bool(0)
                Settings.merge(host, JSONObject().put("autoLaunch", on))
                JSONObject().put("success", true)
                    .put("msg", if (on) "已开启开机自启" else "已关闭开机自启")
            }

            // ---------------------------------------------------------- 账号
            "listAccounts" -> accounts()
            "getActiveAccount" -> {
                val all = accounts().optJSONArray("data") ?: JSONArray()
                var active: JSONObject? = null
                for (i in 0 until all.length()) {
                    val o = all.optJSONObject(i) ?: continue
                    if (o.optBoolean("isActive")) { active = o; break }
                }
                JSONObject()
                    .put("success", true).put("msg", "")
                    .put("data", active ?: JSONObject.NULL)
            }
            "switchAccount" -> {
                val origin = str(0)
                History.toList(host).firstOrNull {
                    it.optString("origin") == origin || it.optString("href") == origin
                }?.optString("href")?.let { href ->
                    main.post { handOff(href) }
                }
                JSONObject().put("success", true).put("msg", "")
            }
            "removeAccount" -> {
                History.remove(host, str(0))
                JSONObject().put("success", true).put("msg", "")
            }
            "backToConnect" -> {
                main.post {
                    try {
                        val i = Intent(host, MainActivity::class.java)
                        i.putExtra("backToConnect", true)
                        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        host.startActivity(i)
                    } catch (_: Throwable) {}
                    host.finish()
                }
                ok()
            }

            // ---------------------------------------------------------- 应用 / 快捷方式
            "getInstalledApps" -> installedApps()
            "createDesktopShortcut" -> createShortcut(obj(0))

            // ---------------------------------------------------------- 皮肤
            "listSkins" -> listSkins()
            "installSkin" -> {
                main.post {
                    try { host.pickSkinZip() } catch (e: Throwable) {
                        resolve("installSkin", JSONObject().put("ok", false).put("error", "打开文件选择器失败：${e.message}"))
                    }
                }
                JSONObject().put("__pending", true)
            }
            "deleteSkin" -> {
                val name = str(0)
                val (gone, wasActive) = Skins.delete(host, name)
                if (!gone) JSONObject().put("ok", false).put("error", "皮肤不存在")
                else JSONObject().put("ok", true).put("name", name).put("wasActive", wasActive)
            }

            // ---------------------------------------------------------- 杂项
            "previewTitleBar" -> ok()
            "setAccentColor" -> {
                val c = str(0).ifBlank { "#4F6EF7" }
                Settings.merge(host, JSONObject().put("themeColor", c))
                ok()
            }
            "close" -> {
                main.post { try { host.finish() } catch (_: Throwable) {} }
                ok()
            }

            else -> JSONObject().put("ok", false).put("error", "未实现的方法：$method")
        }
    }

    // ---------------------------------------------------------------- 设置快照

    /** 等价 PC `ipcMain.handle('settings:get')` 的返回结构（字段名一个不改） */
    private fun dump(): JSONObject {
        val raw = Settings.raw(host)

        val defLock = "Ctrl+Alt+L"
        val defHide = "Ctrl+Alt+H"
        val scRaw = raw.optJSONObject("shortcuts")
        val shortcuts = JSONObject()
        shortcuts.put(
            "lockApp",
            if (scRaw != null && scRaw.has("lockApp")) scRaw.optString("lockApp", defLock) else defLock
        )
        shortcuts.put(
            "hideAll",
            if (scRaw != null && scRaw.has("hideAll")) scRaw.optString("hideAll", defHide) else defHide
        )

        val alphaPct = when {
            raw.has("uiTransparentAlpha") && !raw.isNull("uiTransparentAlpha") ->
                clampInt(raw.opt("uiTransparentAlpha"), 0, 100, UI_ALPHA_DEFAULT)
            raw.has("uiTransparent") && !raw.optBoolean("uiTransparent", true) -> 0
            else -> UI_ALPHA_DEFAULT
        }

        val tbColor = raw.optString("titleBarColor", "").ifBlank { "#101426" }
        val iptv = raw.optJSONObject("iptv") ?: JSONObject()
        val fpk = raw.optJSONObject("fpkApi") ?: JSONObject()

        val o = JSONObject()
        o.put("hasPassword", AppLock.hasPassword(host))
        o.put("shortcuts", shortcuts)
        o.put("urlRewrites", raw.optJSONArray("urlRewrites") ?: JSONArray())
        o.put("autoHideMenuBar", raw.optBoolean("autoHideMenuBar", false))
        o.put("keepLogin", !raw.has("keepLogin") || raw.optBoolean("keepLogin", true))
        o.put("titleBarAutoHide", raw.optBoolean("titleBarAutoHide", false))
        o.put("titleBarOpacity", clampInt(raw.opt("titleBarOpacity"), 0, 100, 96))
        o.put("titleBarColor", tbColor)
        o.put("titleBarMaterial", if (raw.optString("titleBarMaterial", "solid") == "frosted") "frosted" else "solid")
        o.put("titleBarBlur", clampInt(raw.opt("titleBarBlur"), 0, 40, 20))
        o.put("titleBarFrostColor", raw.optString("titleBarFrostColor", "").ifBlank { "#ffffff" })
        o.put("titleBarFrostAlpha", clampInt(raw.opt("titleBarFrostAlpha"), 2, 35, 10))
        o.put(
            "titleBarSolidColor",
            raw.optString("titleBarSolidColor", "").ifBlank { tbColor }
        )
        o.put("titleBarSolidAlpha", clampInt(raw.opt("titleBarSolidAlpha"), 90, 100, 100))
        o.put("titleBarDivider", if (raw.has("titleBarDivider")) raw.optBoolean("titleBarDivider", true) else true)
        o.put("titleBarHighlight", clampInt(raw.opt("titleBarHighlight"), 0, 100, 55))
        o.put("themeColor", raw.optString("themeColor", "").ifBlank { "#4F6EF7" })
        o.put("activeSkin", Settings.load(host).activeSkin)
        o.put("hideAccountBall", raw.optBoolean("hideAccountBall", false))
        o.put("uiTransparent", alphaPct > 0)
        o.put("uiTransparentAlpha", alphaPct)
        o.put("barCorner", cornerNorm(raw.optString("barCorner", "")))
        o.put("winCorner", cornerNorm(raw.optString("winCorner", "")))
        o.put("ballPos", JSONObject.NULL)
        o.put("ballAppearanceMode", if (raw.optString("ballAppearanceMode") == "custom") "custom" else "follow")
        o.put("ballMaterial", if (raw.optString("ballMaterial") == "frosted") "frosted" else "solid")
        o.put("ballSolidColor", raw.optString("ballSolidColor", "").ifBlank { "#101426" })
        o.put("ballSolidAlpha", clampInt(raw.opt("ballSolidAlpha"), 40, 100, 100))
        o.put("ballFrostColor", raw.optString("ballFrostColor", "").ifBlank { "#FFFFFF" })
        o.put("ballFrostAlpha", clampInt(raw.opt("ballFrostAlpha"), 2, 60, 14))
        o.put("autoLockMinutes", clampInt(raw.opt("autoLockMinutes"), 0, 240, 0))
        o.put("shortcutHideMode", if (raw.optString("shortcutHideMode") == "minimize") "minimize" else "tray")
        o.put("fpkBaseUrl", "")
        val iptvOut = JSONObject()
        iptvOut.put("iptvBaseUrl", iptv.optString("iptvBaseUrl", ""))
        iptvOut.put("iptvLine", iptv.optString("iptvLine", "inner").ifBlank { "inner" })
        iptvOut.put("iptvEpgUrl", iptv.optString("iptvEpgUrl", ""))
        iptvOut.put("iptvCacheSeconds", clampInt(iptv.opt("iptvCacheSeconds"), 0, 120, 30))
        o.put("iptv", iptvOut)
        o.put("version", versionName())
        val fpkOut = JSONObject()
        val nasHost = try { java.net.URL(currentBase()).host } catch (_: Exception) { "" }
        fpkOut.put("enabled", true)
        fpkOut.put("host", fpk.optString("host", "").ifBlank { nasHost })
        fpkOut.put("port", if (fpk.optInt("port", 0) > 0) fpk.optInt("port", 18080) else 18080)
        fpkOut.put("appsSource", Apps.sourceOf(host))
        o.put("fpkApi", fpkOut)
        o.put("mpv", mpvSettings(raw))
        return o
    }

    private fun setUiOptions(opts: JSONObject): JSONObject {
        val keys = listOf(
            "autoHideMenuBar", "themeColor", "titleBarAutoHide", "titleBarOpacity", "titleBarColor",
            "titleBarMaterial", "titleBarBlur", "titleBarHighlight", "titleBarFrostColor",
            "titleBarFrostAlpha", "titleBarSolidColor", "titleBarSolidAlpha", "titleBarDivider",
            "hideAccountBall", "ballAppearanceMode", "ballMaterial", "ballSolidColor",
            "ballSolidAlpha", "ballFrostColor", "ballFrostAlpha", "uiTransparent",
            "uiTransparentAlpha", "barCorner", "winCorner"
        )
        val patch = JSONObject()
        val sb = StringBuilder()
        for (k in keys) {
            if (opts.has(k) && !opts.isNull(k)) {
                patch.put(k, opts.opt(k))
                sb.append(k).append(' ')
            }
        }
        // 透明界面：settings.js 传的是百分比，老的布尔字段跟着一起维护
        if (patch.has("uiTransparentAlpha")) {
            patch.put("uiTransparent", clampInt(patch.opt("uiTransparentAlpha"), 0, 100, UI_ALPHA_DEFAULT) > 0)
        }
        if (patch.length() > 0) Settings.merge(host, patch)
        // 皮肤：PC v3.0.36 语义 —— 显式传了 activeSkin（含空串）才改，没传就保留
        if (opts.has("activeSkin")) {
            val name = opts.optString("activeSkin", "").trim()
            Skins.select(host, name)
            sb.append("activeSkin=").append(name)
        }
        Log2.w(host, "bridge: setUIOptions [$sb]")
        return ok()
    }

    /** 启动密码：Android 用它决定菜单里是否出现「锁定 FNOS」+ 自动锁定提示 */
    private fun setPassword(oldPwd: String, newPwd: String): JSONObject {
        val raw = Settings.raw(host)
        val hasOld = raw.optString("appPasswordHash", "").isNotBlank()
        if (hasOld) {
            val salt = raw.optString("appPasswordSalt", "")
            if (hashPwd(salt, oldPwd) != raw.optString("appPasswordHash", "")) {
                return JSONObject().put("ok", false).put("error", "启动密码不正确")
            }
        }
        if (newPwd.isNotEmpty() && newPwd.length < 4) {
            return JSONObject().put("ok", false).put("error", "新密码至少 4 位")
        }
        if (newPwd.isEmpty()) {
            raw.remove("appPasswordHash")
            raw.remove("appPasswordSalt")
            Log2.w(host, "bridge: setPassword cleared")
        } else {
            val salt = hexOf(randomBytes(16))
            raw.put("appPasswordSalt", salt)
            raw.put("appPasswordHash", hashPwd(salt, newPwd))
            Log2.w(host, "bridge: setPassword updated")
        }
        Settings.write(host, raw)
        return ok()
    }

    // ------------------------------------------------------------------ 账号

    private fun accounts(): JSONObject {
        val activeOrigin = History.lastOrigin(host)
        val data = JSONArray()
        val seen = HashSet<String>()
        for (h in History.toList(host)) {
            val origin = h.optString("origin", "")
            val href = h.optString("href", "")
            val id = origin.ifBlank { href }
            if (id.isBlank() || !seen.add(id)) continue
            data.put(
                JSONObject()
                    .put("id", id)
                    .put("label", h.optString("label", "").ifBlank { id })
                    .put("origin", origin.ifBlank { href })
                    .put("href", href)
                    .put("isActive", origin.isNotBlank() && origin == activeOrigin)
            )
        }
        return JSONObject().put("success", true).put("msg", "").put("data", data)
    }

    /** 把当前设置页收起，回主窗按新地址重连（等价 PC 的 account:switch） */
    private fun handOff(href: String) {
        try {
            val i = Intent(host, MainActivity::class.java)
            i.putExtra("connectTo", href)
            i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            host.startActivity(i)
        } catch (_: Throwable) {}
        host.finish()
    }

    // ------------------------------------------------------- 应用 / 快捷方式

    /**
     * 测试 FPK 图标管理器（设置页「测试连接」按钮）：
     * GET {base}/api/apps → 返回应用数量。以前这里直接谎报"不需要 FPK"，现在真打一次。
     */
    private fun testFpk(): JSONObject {
        return try {
            val fb = Apps.fpkBase(host, currentBase())
            if (fb.isBlank()) return JSONObject().put("ok", false).put("error", "没有可用的 NAS 地址")
            val c = (java.net.URL("$fb/api/apps").openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 5000
                setRequestProperty("User-Agent", NasUa.DESKTOP)
            }
            val code = c.responseCode
            if (code >= 400) {
                JSONObject().put("ok", false).put("error", "HTTP $code（$fb/api/apps）")
            } else {
                val text = c.inputStream.bufferedReader().use { it.readText() }
                val n = JSONObject(text).optJSONArray("apps")?.length() ?: 0
                Log2.w(host, "bridge: testFpk ok n=$n base=$fb")
                JSONObject().put("ok", true).put("apps", n).put("base", fb)
                    .put("msg", "FPK 图标管理器可用：$n 个应用（$fb）")
            }
        } catch (e: java.lang.Exception) {
            JSONObject().put("ok", false).put("error", "连接失败：${e.message}")
        }
    }

    private fun installedApps(): JSONObject {
        val base = currentBase()
        // 用户要求（第 5 项）：强制以 FPK 图标管理器为准；FPK 不通才回退 app-center / 内置清单。
        // refreshBlocking 内部按 60s 节流，设置页 10s 轮询不会反复打 NAS。
        Apps.refreshBlocking(host, base, 3500)
        val pinned = Shortcuts.pinnedIds(host)
        val data = JSONArray()
        val seen = HashSet<String>()
        val icons = ArrayList<String>()
        for (e in Apps.all(host)) {
            if (e.id.isBlank() || !seen.add(e.id)) continue
            if (e.iconUrl.isNotBlank()) icons.add(e.iconUrl)
            data.put(
                JSONObject()
                    .put("appId", e.id)
                    .put("appName", e.name)
                    .put("anchor", e.anchor)
                    // Android 端没有「桌面 .lnk 图标文件」这一层：iconPath 留空，
                    // 真实图标走 iconUrl（FPK /api/icons/<name>/256），由页面 <img> 直接显示
                    .put("iconPath", "")
                    .put("iconUrl", e.iconUrl)
                    .put("source", e.source.ifBlank { Apps.sourceOf(host) })
                    .put("pinned", pinned.contains(Shortcuts.pinIdOf(e.id)))
                    .put("nasAddress", base)
            )
        }
        // 电视直播（PC 端同款保留 ID：create-desktop-shortcut 走独立分支）
        data.put(
            JSONObject()
                .put("appId", Shortcuts.ID_LIVE)
                .put("appName", "电视直播")
                .put("anchor", "live")
                .put("iconPath", "")
                .put("iconUrl", "")
                .put("source", "builtin")
                .put("pinned", pinned.contains(Shortcuts.pinIdOf(Shortcuts.ID_LIVE)))
                .put("nasAddress", base)
        )
        // 后台预取图标，用户点「创建快捷方式」时主线程直接命中缓存
        try { RemoteIcons.warm(host, icons) } catch (_: Throwable) {}
        Log2.w(host, "bridge: installedApps n=${data.length()} source=${Apps.sourceOf(host)} pinned=${pinned.size}")
        return JSONObject().put("success", true).put("msg", "").put("data", data)
            .put("source", Apps.sourceOf(host))
    }

    /**
     * 等价 PC `create-desktop-shortcut`：返回 {success, msg, data}。
     * 成败以 Shortcuts.requestPin* 的真实结果为准（不支持 / 被拒绝都会带出可执行的建议文案）。
     */
    private fun createShortcut(p: JSONObject): JSONObject {
        val appId = p.optString("appId", "").trim()
        val appName = p.optString("appName", "").trim()
        if (appId.isEmpty() || appName.isEmpty()) {
            return JSONObject().put("success", false).put("msg", "缺少 appId 或 appName")
                .put("data", JSONObject.NULL)
        }
        val base = currentBase()
        // 图标先取到本地（当前在 JavaBridge 线程，可以联网）；主线程 pin 时才能同步拿到 Bitmap
        val entry = if (appId == Shortcuts.ID_LIVE) null else (Apps.find(host, appId) ?: Apps.Entry(appId, appName, appId))
        if (entry != null && entry.iconUrl.isNotBlank()) {
            try { RemoteIcons.fetch(host, entry.iconUrl, 2500) } catch (_: Throwable) {}
        }
        var r: Shortcuts.PinResult? = null
        val latch = CountDownLatch(1)
        main.post {
            r = try {
                if (appId == Shortcuts.ID_LIVE) {
                    Shortcuts.requestPinLive(host, base)
                } else {
                    Shortcuts.requestPin(host, entry ?: Apps.Entry(appId, appName, appId), base)
                }
            } catch (t: Throwable) {
                Shortcuts.PinResult(false, false, "创建失败：${t.message ?: t}")
            } finally {
                latch.countDown()
            }
        }
        try { latch.await(6, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        val res = r ?: Shortcuts.PinResult(false, false, "创建超时，请稍后重试")
        Log2.w(host, "bridge: createDesktopShortcut $appId ok=${res.ok} supported=${res.supported} msg=${res.msg}")
        val out = JSONObject().put("success", res.ok).put("msg", res.msg)
        out.put("data", if (res.ok) JSONObject().put("path", "shortcut://$appId") else JSONObject.NULL)
        return out
    }

    // ------------------------------------------------------------------ 皮肤

    private fun listSkins(): JSONObject {
        val arr = JSONArray()
        for (name in Skins.names(host)) {
            val mf = Skins.manifestOf(host, name) ?: continue
            arr.put(
                JSONObject()
                    .put("name", name)
                    .put("manifest", mf)
                    .put("preview", Skins.previewDataUrl(host, name))
            )
        }
        return JSONObject().put("ok", true).put("skins", arr)
            .put("activeSkin", Settings.load(host).activeSkin)
    }

    /** installSkin 的异步回执（SettingsWebActivity 选完 zip 后调用） */
    internal fun resolve(method: String, value: JSONObject) {
        host.evalJs(
            "window.__fnosSettingsResolve&&window.__fnosSettingsResolve(" +
                JSONObject.quote(method) + "," + JSONObject.quote(value.toString()) + ")"
        )
    }

    // ---------------------------------------------------------------- 小工具

    private fun ok(): JSONObject = JSONObject().put("ok", true)

    private fun versionName(): String = try {
        host.packageManager.getPackageInfo(host.packageName, 0).versionName ?: ""
    } catch (_: Exception) { "" }

    private fun currentBase(): String {
        val raw = Settings.raw(host)
        val origin = raw.optString("origin", "").trim()
        if (origin.isNotBlank()) return origin.trimEnd('/')
        val st = Settings.load(host)
        return try { Lines.normalize(st.server, st.line) } catch (_: Exception) { st.server }
    }

    private fun isHttpUrl(s: String): Boolean =
        s.startsWith("http://", true) || s.startsWith("https://", true)

    private fun cornerNorm(v: String): String = when (v) {
        "round-small", "round-large", "half-small", "half-large", "square" -> v
        "round" -> "round-large"
        "half" -> "half-large"
        else -> "round-large"
    }

    /** 与 PC getMpvSettings() 同构（Android 只是记着这些值，播放走系统播放器） */
    private fun mpvSettings(raw: JSONObject): JSONObject {
        val v = raw.optJSONObject("mpv") ?: JSONObject()
        var hw = v.optString("hwDecode", "auto")
        if (hw !in listOf("auto", "auto-safe", "d3d11va", "dxva2", "no")) hw = "auto"
        if (hw == "auto-safe") hw = "d3d11va"
        val cache = v.optString("cacheLevel", "smooth")
            .let { if (it in listOf("standard", "smooth", "unlimited")) it else "smooth" }
        val subIn = v.optJSONObject("subStyle") ?: JSONObject()
        val sub = JSONObject()
        sub.put("pos", numOrNull(subIn.opt("pos")))
        sub.put("size", numOrNull(subIn.opt("size")))
        sub.put("scale", numOrNull(subIn.opt("scale")))
        val o = JSONObject()
        o.put("enabled", if (v.has("enabled")) v.optBoolean("enabled", true) else true)
        o.put("alwaysUse", v.optBoolean("alwaysUse", false))
        o.put("hwDecode", hw)
        o.put("cacheLevel", cache)
        o.put("coreMode", if (v.optString("coreMode") == "custom") "custom" else "builtin")
        o.put("exePath", v.optString("exePath", "").trim())
        o.put("extraArgs", v.optString("extraArgs", ""))
        o.put("configMode", if (v.optString("configMode") == "own") "own" else "builtin")
        val perf = v.opt("perfPreset")
        if (perf is Boolean) o.put("perfPreset", perf) else o.put("perfPreset", JSONObject.NULL)
        o.put("subStyle", sub)
        o.put("resume", if (v.has("resume")) v.optBoolean("resume", true) else true)
        o.put("autoNext", if (raw.has("mpvAutoNext")) raw.optBoolean("mpvAutoNext", true) else true)
        return o
    }

    private fun numOrNull(v: Any?): Any {
        val d = when (v) {
            is Number -> v.toDouble()
            is String -> v.trim().toDoubleOrNull()
            else -> null
        }
        return if (d == null || d.isNaN() || d.isInfinite()) JSONObject.NULL else d
    }

    private fun clampInt(v: Any?, min: Int, max: Int, dflt: Int): Int {
        val d = when (v) {
            is Number -> v.toDouble()
            is String -> v.trim().toDoubleOrNull() ?: return dflt
            else -> return dflt
        }
        if (d.isNaN() || d.isInfinite()) return dflt
        return Math.round(d).toInt().coerceIn(min, max)
    }

    private fun randomBytes(n: Int): ByteArray {
        val b = ByteArray(n)
        try { java.security.SecureRandom().nextBytes(b) } catch (_: Exception) {
            val t = System.nanoTime().toString()
            for (i in b.indices) b[i] = t[i % t.length].code.toByte()
        }
        return b
    }

    private fun hexOf(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append(String.format("%02x", x))
        return sb.toString()
    }

    private fun hashPwd(salt: String, pwd: String): String = try {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        hexOf(md.digest((salt + pwd).toByteArray(Charsets.UTF_8)))
    } catch (_: Exception) { "" }

    companion object {
        /** addJavascriptInterface 暴露的名字（对应 PC preload 的桥对象） */
        const val IFACE = "FnosBridge"

        /** PC 端「透明界面」默认百分比（main.js UI_ALPHA_DEFAULT） */
        const val UI_ALPHA_DEFAULT = 62

        /**
         * Electron settings-preload.js 的 Android 替身。
         * 必须在 settings.js 之前执行（见 LocalWebActivity.documentStart）：
         * settings.js 是一个立即执行的 IIFE，末尾就开始调用 fnosSettings.getSettings()。
         */
        val SHIM: String = """
(function () {
  try {
    if (window.__fnosSettingsShim) return;
    window.__fnosSettingsShim = true;
    if (typeof FnosBridge === 'undefined') return;
    var waiting = {};
    function invoke(m, args) {
      try {
        return JSON.parse(FnosBridge.call(String(m), JSON.stringify({ a: args || [] })));
      } catch (e) {
        return { ok: false, error: '桥调用异常：' + String((e && e.message) || e) };
      }
    }
    function call(m) {
      return function () {
        return Promise.resolve(invoke(m, Array.prototype.slice.call(arguments)));
      };
    }
    function pending(m) {
      return function () {
        return new Promise(function (resolve) {
          waiting[m] = resolve;
          var r = invoke(m, Array.prototype.slice.call(arguments));
          if (r && r.__pending) return;
          delete waiting[m];
          resolve(r);
        });
      };
    }
    window.__fnosSettingsResolve = function (m, json) {
      var fn = waiting[m];
      if (!fn) return;
      delete waiting[m];
      var v = null;
      try { v = (typeof json === 'string') ? JSON.parse(json) : json; } catch (e) { v = null; }
      try { fn(v); } catch (e) {}
    };
    window.fnosSettings = {
      getSettings: call('getSettings'),
      setUIOptions: call('setUIOptions'),
      setShortcuts: call('setShortcuts'),
      setUrlRewrites: call('setUrlRewrites'),
      setAutoLock: call('setAutoLock'),
      setPassword: call('setPassword'),
      setKeepLogin: call('setKeepLogin'),
      setShortcutHideMode: call('setShortcutHideMode'),
      setFpkApi: call('setFpkApi'),
      testFpkApi: call('testFpkApi'),
      iptvSetConfig: call('iptvSetConfig'),
      setVlc: call('setVlc'),
      vlcRuntime: call('vlcRuntime'),
      pickMpvExe: call('pickMpvExe'),
      clearProgress: call('clearProgress'),
      clearCache: call('clearCache'),
      getAutoStart: call('getAutoStart'),
      setAutoStart: call('setAutoStart'),
      setAutoLaunch: call('setAutoLaunch'),
      listAccounts: call('listAccounts'),
      getActiveAccount: call('getActiveAccount'),
      switchAccount: call('switchAccount'),
      removeAccount: call('removeAccount'),
      backToConnect: call('backToConnect'),
      getInstalledApps: call('getInstalledApps'),
      createDesktopShortcut: call('createDesktopShortcut'),
      listSkins: call('listSkins'),
      installSkin: pending('installSkin'),
      deleteSkin: call('deleteSkin'),
      previewTitleBar: call('previewTitleBar'),
      setAccentColor: call('setAccentColor'),
      close: call('close')
    };
  } catch (e) {}
})();
        """.trimIndent()
    }
}
