package com.fnos.tablet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 应用菜单的**数据**（等价 PC 端 main.js `buildMenuTemplate()` 序列化出来的
 * `{ items, theme, alpha }`），由 menu-popup.js 原样渲染。
 *
 * v5.2.0（本次改造）：菜单**逐项照抄 PC（Windows）模板**——
 *   文件 / 下载 / 视图 / 工具 / 设置 / 帮助 / 关于
 * label、顺序、分隔线、accelerator 文案全部与 PC 一致；
 * Android 端此前私自加的「重新连接」「切换服务器」「皮肤与标题栏…」「导出诊断日志」
 * 「📌 创建桌面快捷方式」全部从菜单删除（前两个按 PC 改回「切换账号」+「登录其它账号…」，
 * 后三个功能挪进偏好设置页 SettingsActivity）。
 *
 * 只有**实现**按 Android 语义映射（label 一个字都不改）：
 *   - 隐藏到托盘        → moveTaskToBack + Toast（Android 无托盘）
 *   - 一键隐藏 / 呼出    → moveTaskToBack（同上；PC 的全局快捷键在 Android 无对应能力，
 *                         但 label 里的「  (快捷键)」后缀与 PC 同源，仍然照抄）
 *   - 锁定 FNOS         → 有 appPasswordHash+Salf 才出现（与 PC hasAppPassword() 同一判据）
 *   - 用 MPV 嵌入播放…   → 用系统播放器打开当前网页里的视频 URL
 *   - 打开「本地网络」权限设置… → ACTION_APPLICATION_DETAILS_SETTINGS（本应用系统设置页）
 *   - 切换账号          → 复用连接历史（settings.json 的 history 数组）
 *
 * 下载：PC 的 buildDownloadsMenu() 由下载窗口管理器供数据，平板不接管下载任务，
 * 因此等价实现是「（暂无下载任务）」一条，点它进 NAS 的「下载」应用（见 task 说明）。
 */
object MenuModel {

    fun payload(ctx: Context): JSONObject {
        val items = JSONArray()
        items.put(dirMenu(ctx))
        items.put(downloadMenu())
        items.put(viewMenu())
        items.put(toolMenu())
        items.put(settingsMenu())
        items.put(helpMenu())
        items.put(aboutMenu())
        val o = JSONObject()
        o.put("items", items)
        o.put("theme", theme(ctx))
        o.put("alpha", alpha(ctx))
        return o
    }

    /** 跟随「透明界面」开关（PC：<=3 判为深色，其余玻璃） */
    private fun theme(ctx: Context): String {
        val raw = Settings.raw(ctx)
        val pct = if (raw.has("uiTransparentAlpha")) raw.optInt("uiTransparentAlpha", 62)
        else if (!raw.optBoolean("uiTransparent", true)) 0 else 62
        return if (pct <= 3) "dark" else "glass"
    }

    private fun alpha(ctx: Context): Double {
        val raw = Settings.raw(ctx)
        val pct = if (raw.has("uiTransparentAlpha")) raw.optInt("uiTransparentAlpha", 62)
        else if (!raw.optBoolean("uiTransparent", true)) 0 else 62
        val a = 1.0 - (pct.coerceIn(0, 100) / 100.0) * 0.6
        return if (a < 0.9) 0.9 else if (a > 1.0) 1.0 else a
    }

    // ------------------------------------------------------------------ 各列

    /** 文件（PC buildMenuTemplate 第一列，逐项对齐） */
    private fun dirMenu(ctx: Context): JSONObject {
        val sub = JSONArray()
        sub.put(leaf("file.home", "返回 FNOS 主页", "Alt+H"))
        sub.put(sep())
        sub.put(subItem("file.account", "切换账号", accountsSub(ctx)))
        sub.put(subItem("file.windows", "切换窗口", windowsSub()))
        sub.put(sep())
        // PC：...(hasAppPassword() ? [{ label: `锁定 FNOS${lockAcc ? `  (${lockAcc})` : ''}` }] : [])
        if (AppLock.hasPassword(ctx)) {
            val acc = AppLock.lockAccel(ctx)
            sub.put(leaf("file.lock", "锁定 FNOS" + if (acc.isNotBlank()) "  ($acc)" else ""))
        }
        val hideAcc = AppLock.hideAccel(ctx)
        sub.put(leaf("file.hide", "一键隐藏 / 呼出" + if (hideAcc.isNotBlank()) "  ($hideAcc)" else ""))
        sub.put(sep())
        sub.put(leaf("file.minimize", "最小化"))
        sub.put(leaf("file.tray", "隐藏到托盘"))
        sub.put(leaf("file.close", "关闭"))
        return subItem("m.file", "文件", sub)
    }

    /**
     * 「切换账号」子菜单（PC getAccounts()）：
     *   无账号 → 单条「（暂无已登录账号）」禁用；
     *   有账号 → 「● 当前 /   其它」列表 + 分隔线 + 「登录其它账号…」
     */
    private fun accountsSub(ctx: Context): JSONArray {
        val out = JSONArray()
        val list = History.toList(ctx)
        if (list.isEmpty()) {
            out.put(leaf("", "（暂无已登录账号）", "", false))
            return out
        }
        val curOrigin = History.lastOrigin(ctx)
        val curServer = History.lastServer(ctx)
        for (h in list) {
            val origin = h.optString("origin")
            val serverInput = h.optString("serverInput")
            val href = h.optString("href").ifBlank { origin.ifBlank { serverInput } }
            val isActive = (curOrigin.isNotBlank() && origin == curOrigin) ||
                (curOrigin.isBlank() && curServer.isNotBlank() && serverInput == curServer)
            val rawLabel = h.optString("label").ifBlank { origin.ifBlank { serverInput } }
            val label = if (rawLabel.length > 36) rawLabel.take(36) + "…" else rawLabel
            out.put(leaf("account.use|$href", (if (isActive) "● " else "  ") + label))
        }
        out.put(sep())
        out.put(leaf("account.other", "登录其它账号…"))
        return out
    }

    /**
     * 「切换窗口」子菜单（PC appWindows 列表，不含主窗）：为空时单条「（暂无后台运行的程序）」禁用。
     * PC 的标题超 36 字符截断加省略号，这里照抄。
     */
    private fun windowsSub(): JSONArray {
        val out = JSONArray()
        val wins = WindowRegistry.list()
        if (wins.isEmpty()) {
            out.put(leaf("", "（暂无后台运行的程序）", "", false))
        } else {
            for (w in wins) {
                val label = if (w.name.length > 36) w.name.take(36) + "…" else w.name
                out.put(leaf("win.goto|" + w.name, label))
            }
        }
        return out
    }

    /**
     * 下载：PC 是 buildDownloadsMenu()（有任务列任务名+进度，无任务「（暂无正在进行的下载）」）。
     * 平板不接管下载任务 → 单条「（暂无下载任务）」，点进 NAS「下载」应用。
     */
    private fun downloadMenu(): JSONObject {
        val sub = JSONArray()
        sub.put(leaf("app|download", "（暂无下载任务）"))
        return subItem("m.download", "下载", sub)
    }

    private fun viewMenu(): JSONObject {
        val sub = JSONArray()
        sub.put(leaf("view.back", "返回上一页", "Alt+Left"))
        sub.put(leaf("view.forward", "前进下一页", "Alt+Right"))
        sub.put(sep())
        sub.put(leaf("view.reload", "刷新", "F5"))
        sub.put(leaf("view.hardReload", "强制刷新", "Ctrl+F5"))
        sub.put(sep())
        sub.put(leaf("view.zoomReset", "重置缩放", "Ctrl+0"))
        sub.put(leaf("view.zoomIn", "放大", "Ctrl+="))
        sub.put(leaf("view.zoomOut", "缩小", "Ctrl+-"))
        sub.put(sep())
        sub.put(leaf("view.fullscreen", "全屏", "F11"))
        return subItem("m.view", "视图", sub)
    }

    private fun toolMenu(): JSONObject {
        val sub = JSONArray()
        sub.put(leaf("tool.live", "📺 电视直播（原生播放器）"))
        sub.put(sep())
        sub.put(leaf("tool.mpv", "🎬 用 MPV 嵌入播放当前视频（兼容 HEVC/4K/MKV）", "Ctrl+Shift+P"))
        sub.put(sep())
        sub.put(leaf("tool.copyLink", "🔗 复制当前窗口链接地址", "Ctrl+Shift+C"))
        sub.put(leaf("tool.openBrowser", "🌐 用默认浏览器打开此窗口", "Ctrl+Shift+O"))
        return subItem("m.tool", "工具", sub)
    }

    private fun settingsMenu(): JSONObject {
        val sub = JSONArray()
        sub.put(leaf("set.prefs", "偏好设置…", "Ctrl+,"))
        return subItem("m.settings", "设置", sub)
    }

    private fun helpMenu(): JSONObject {
        val sub = JSONArray()
        sub.put(leaf("help.netprefs", "打开「本地网络」权限设置…"))
        sub.put(sep())
        sub.put(leaf("help.steps", "操作步骤帮助"))
        sub.put(sep())
        sub.put(leaf("help.uninstall", "卸载 FNOS 客户端…"))
        return subItem("m.help", "帮助", sub)
    }

    private fun aboutMenu(): JSONObject {
        val sub = JSONArray()
        sub.put(leaf("about.app", "关于 FNOS"))
        return subItem("m.about", "关于", sub)
    }

    // --------------------------------------------------------------- item 构造

    private fun leaf(id: String, label: String, accel: String = "", enabled: Boolean = true): JSONObject =
        JSONObject().apply {
            put("t", "normal")
            put("id", id)
            put("label", label)
            if (accel.isNotEmpty()) put("accel", accel)
            put("enabled", enabled)
        }

    private fun subItem(id: String, label: String, items: JSONArray): JSONObject =
        JSONObject().apply {
            put("t", "sub")
            put("id", id)
            put("label", label)
            put("items", items)
        }

    private fun sep(): JSONObject = JSONObject().apply { put("t", "sep") }
}

/**
 * 应用锁 / 全局快捷键的判据与文案（与 PC 完全同源）：
 *   hasAppPassword() = settings.json 里同时有 appPasswordHash 与 appPasswordSalt
 *   快捷键文案   = settings.shortcuts.lockApp / hideAll（PC 默认 Ctrl+Alt+L / Ctrl+Alt+H）
 * Android 没有 Electron globalShortcut，快捷键只作 label 文案保留，行为由菜单项承担。
 */
object AppLock {

    /** PC DEFAULT_SHORTCUTS */
    private const val DEF_LOCK = "Ctrl+Alt+L"
    private const val DEF_HIDE = "Ctrl+Alt+H"

    fun hasPassword(ctx: Context): Boolean {
        val s = Settings.raw(ctx)
        return s.optString("appPasswordHash").isNotBlank() && s.optString("appPasswordSalt").isNotBlank()
    }

    private fun accel(ctx: Context, key: String, def: String): String {
        val sc = Settings.raw(ctx).optJSONObject("shortcuts") ?: return def
        // PC: { ...DEFAULT_SHORTCUTS, ...(raw.shortcuts || {}) } —— 显式空串表示用户清空
        return if (sc.has(key)) sc.optString(key, def) else def
    }

    fun lockAccel(ctx: Context): String = accel(ctx, "lockApp", DEF_LOCK)

    fun hideAccel(ctx: Context): String = accel(ctx, "hideAll", DEF_HIDE)
}

/** 打开中的应用窗登记表（菜单「切换窗口」用，等价 PC appWindows 列表，不含主窗口） */
object WindowRegistry {

    /** 一个后台运行的程序窗口；intent 用于「切换窗口」把它重新拉到前台 */
    data class Entry(val name: String, val intent: android.content.Intent)

    private val entries = java.util.Collections.synchronizedList(ArrayList<Entry>())

    fun add(e: Entry) {
        if (e.name.isBlank()) return
        remove(e.name)
        entries.add(e)
    }

    fun remove(name: String) { synchronized(entries) { entries.removeAll { it.name == name } } }

    fun clear() { entries.clear() }

    fun list(): List<Entry> = synchronized(entries) { ArrayList(entries) }

    fun find(name: String): Entry? = synchronized(entries) { entries.firstOrNull { it.name == name } }

    fun names(): List<String> = list().map { it.name }
}
