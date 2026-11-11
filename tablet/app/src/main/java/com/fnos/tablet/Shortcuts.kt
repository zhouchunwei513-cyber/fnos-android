package com.fnos.tablet

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * 桌面快捷方式（Android 上 PC `.lnk` 的等价物）。
 *
 * 两件事：
 *  ① setDynamicShortcuts —— 在支持 dynamic shortcuts 的启动器上，长按应用图标能看到应用列表。
 *     注意（v5.1.34 真机实测）：**MIUI Pad 5 Pro 长按图标什么菜单都不弹**，
 *     所以本机的唯一入口是 App 内的「应用宫格」（设置页 → 应用快捷方式创建 / AppsActivity），
 *     那里单击=打开应用、长按=操作菜单、以及「钉到桌面」。
 *  ② requestPinShortcut —— 宫格/设置里的「钉到桌面」，
 *     用户确认后系统桌面直接生成图标，等价 PC 创建 .lnk。
 *
 * 点快捷方式 → 直接到 AppWindowActivity（带 anchor/base/path），不进主界面。
 *
 * v5.1.32（对应用户第 5/6 项）：
 *  · 图标改由 FPK 图标管理器提供（GET /api/icons/<name>/256），与桌面/PC 端一致；
 *    取不到时回退应用自身图标（仍然光栅化成位图，MIUI 才画得出来）。
 *  · 新增 pinnedIds()：设置页据此把「已创建」的按钮持久置灰，
 *    不再点完 3 秒就恢复成「创建快捷方式」。
 */
object Shortcuts {

    /** 快捷方式 → AppWindowActivity 的自定义 action（与 Manifest intent-filter 对应） */
    const val ACTION_OPEN_APP = "com.fnos.tablet.action.OPEN_APP"

    /** 钉到桌面成功后启动器回送的回执（见 PinShortcutReceiver） */
    const val ACTION_PIN_RESULT = "com.fnos.tablet.action.PIN_RESULT"

    const val EXTRA_ANCHOR = "anchor"
    const val EXTRA_BASE = "base"
    const val EXTRA_URL = "url"
    /** v5.1.34（Bug A）：FPK 清单里的真实入口 path（影视=/v、相册=/p） */
    const val EXTRA_PATH = "path"
    const val EXTRA_NAME = "name"
    const val EXTRA_SHORTCUT_ID = "shortcutId"

    /** PC create-desktop-shortcut 里电视直播的保留 ID */
    const val ID_LIVE = "__fnos.live__"

    /** 钉到桌面用的 id 前缀（与动态快捷方式的 fn-app- 区分） */
    private const val PIN_PREFIX = "fn-pin-"

    /**
     * 「电视直播」钉到桌面时的 id 后缀。
     * 注意：列表里用的 [ID_LIVE]（__fnos.live__）是 PC 端的保留标识，
     * 与桌面快捷方式 id 不是一回事，批量钉时必须用这个后缀去核对落地结果。
     */
    private const val LIVE_PIN_SUFFIX = "live"
    private val LIVE_PIN_ID get() = PIN_PREFIX + LIVE_PIN_SUFFIX

    /** 一次「钉到桌面」请求的结果：界面据此说话，而不是一律「已请求」 */
    data class PinResult(val ok: Boolean, val supported: Boolean, val msg: String)

    private const val ICON_SIZE = 192

    @Volatile
    private var iconCache: IconCompat? = null

    /** 内置矢量图标（按 resId 缓存）：同样光栅化成位图，MIUI 才画得出来 */
    private val resIconCache = HashMap<Int, IconCompat>()

    /**
     * 兜底图标：应用自身图标，**统一光栅化成位图**。
     *
     * mipmap/ic_launcher 在 API 26+ 是 anydpi-v26 的自适应图标（XML + 两层 drawable），
     * MIUI / HyperOS 等启动器拿 AdaptiveIconDrawable 做快捷方式图标时会出现
     * 「图标空白」甚至「根本不落图标」，所以这里先画进 192×192 位图再交给 ShortcutInfo。
     */
    fun iconOf(ctx: Context): IconCompat {
        iconCache?.let { return it }
        synchronized(this) {
            iconCache?.let { return it }
            val ic = try {
                val d = ContextCompat.getDrawable(ctx, R.mipmap.ic_launcher)
                if (d != null) {
                    val bmp = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
                    val c = Canvas(bmp)
                    d.setBounds(0, 0, ICON_SIZE, ICON_SIZE)
                    d.draw(c)
                    IconCompat.createWithBitmap(bmp)
                } else null
            } catch (e: Throwable) {
                Log2.w(ctx, "shortcut: icon raster failed ${e.message}")
                null
            } ?: IconCompat.createWithResource(ctx, R.mipmap.ic_launcher_foreground)
            iconCache = ic
            return ic
        }
    }

    /** 优先用 FPK 图标，取不到回退应用图标 */
    fun iconFor(ctx: Context, url: String): IconCompat =
        RemoteIcons.iconOf(ctx, url) ?: iconOf(ctx)

    /**
     * v5.1.31-tablet17（用户任务 2）：内置图标 → 位图。
     *
     * 电视直播没有 FPK 图标（它不在 NAS 应用清单里），原来 requestPinLive 传 iconUrl=""
     * 会退回应用自身的 launcher 图标 —— 但 MIUI 落快捷方式时对「非位图 / 缺图标」的
     * ShortcutInfo 一律静默拒绝。这里把 res/drawable/ic_live 光栅化成 192×192 位图，
     * 与 FPK 图标走完全相同的路径。
     */
    fun iconFromRes(ctx: Context, resId: Int): IconCompat {
        synchronized(this) {
            resIconCache[resId]?.let { return it }
            val ic = try {
                val d = ContextCompat.getDrawable(ctx, resId)
                if (d != null) {
                    val bmp = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
                    val c = Canvas(bmp)
                    d.setBounds(0, 0, ICON_SIZE, ICON_SIZE)
                    d.draw(c)
                    IconCompat.createWithBitmap(bmp)
                } else null
            } catch (e: Throwable) {
                Log2.w(ctx, "shortcut: res icon raster failed ${e.message}")
                null
            } ?: IconCompat.createWithResource(ctx, resId)
            resIconCache[resId] = ic
            return ic
        }
    }

    /** 应用快捷方式的 Intent（冷启动直达应用窗，带 FPK 的 path） */
    fun intentFor(ctx: Context, e: Apps.Entry, base: String): Intent {
        val i = Intent(ctx, AppWindowActivity::class.java)
        i.action = ACTION_OPEN_APP
        i.putExtra(EXTRA_ANCHOR, e.anchor)
        i.putExtra(EXTRA_NAME, e.name)
        i.putExtra(EXTRA_BASE, base)
        // v5.1.34（Bug A）：快捷方式把 FPK 的 path 一起带走，打开时用 base + path。
        // 实测 trim.media → /v（影视真实页）；base + "/appview?anchor=" 在 NAS 上是 404。
        i.putExtra(EXTRA_PATH, e.path)
        if (e.url.isNotBlank()) i.putExtra(EXTRA_URL, e.url)
        i.data = Uri.parse("fnos-tablet://app/" + Uri.encode(e.id))
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return i
    }

    /** 主程序自身的快捷方式（等价 PC 主窗口快捷方式） */
    fun mainIntent(ctx: Context, base: String): Intent {
        val i = Intent(ctx, MainActivity::class.java)
        i.action = ACTION_OPEN_APP
        i.putExtra(EXTRA_BASE, base)
        i.data = Uri.parse("fnos-tablet://main")
        return i
    }

    /** 电视直播的快捷方式（PC create-desktop-shortcut 的 `__fnos.live__` 分支） */
    fun liveIntent(ctx: Context, base: String): Intent {
        val i = Intent(ctx, LiveActivity::class.java)
        // v5.1.31-tablet17（用户任务 2，真机实测 2026-11-11）：
        // 原来这里没设 action → ShortcutManagerCompat.requestPinShortcut 直接抛
        // IllegalArgumentException("intent's action must be set")，
        // 页面显示「创建失败：intent's action must be set」。
        // 必须像 intentFor/mainIntent 一样显式带 action（+ NEW_TASK），桌面才能钉。
        i.action = ACTION_OPEN_APP
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        i.putExtra(EXTRA_BASE, base)
        i.putExtra(EXTRA_NAME, "电视直播")
        i.data = Uri.parse("fnos-tablet://live")
        return i
    }

    fun shortcutsFor(ctx: Context, base: String): List<ShortcutInfoCompat> {
        // v5.1.34（用户 Bug B，真机实测）：MIUI Pad 5 Pro 长按应用图标**不弹**
        // dynamic shortcuts（用户截图 /tmp/h1.png：什么菜单都没有），所以「长按图标 →
        // 选快捷方式 → 拖到桌面」这条兜底在本机根本不成立，文案已全部删除。
        // 这里仍注册动态快捷方式（其它启动器可用），并把「设为常用」的应用排在最前。
        val fav = try { Apps.favorites(ctx).toSet() } catch (_: Throwable) { emptySet() }
        val ordered = if (fav.isEmpty()) Apps.all(ctx)
        else Apps.all(ctx).sortedBy { if (fav.contains(it.id)) 0 else 1 }
        return ordered.map { e ->
            ShortcutInfoCompat.Builder(ctx, "fn-app-" + e.id)
                .setShortLabel(e.name)
                .setLongLabel(e.name + " · 飞牛")
                .setIcon(iconFor(ctx, e.iconUrl))
                .setIntent(intentFor(ctx, e, base))
                .build()
        }
    }

    /**
     * 注册成长按图标可见的动态快捷方式。
     * 数量上限由系统给（常 5~15），超出的部分不报错、只在偏好设置页里可钉。
     */
    fun syncDynamic(ctx: Context, base: String) {
        try {
            val all = shortcutsFor(ctx, base)
            if (all.isEmpty()) return
            val max = try {
                ShortcutManagerCompat.getMaxShortcutCountPerActivity(ctx)
            } catch (_: Throwable) { 5 }
            val list = if (max > 0 && all.size > max) all.take(max) else all
            val ok = ShortcutManagerCompat.setDynamicShortcuts(ctx, list)
            Log2.w(ctx, "shortcut: dynamic ${list.size}/${all.size} (max=$max) ok=$ok")
        } catch (e: Throwable) {
            Log2.w(ctx, "shortcut: syncDynamic failed ${e.message}")
        }
    }

    /**
     * 已钉到桌面的快捷方式 id 集合（"fn-pin-appid"）。
     * 设置页用它把按钮持久显示成「✓ 已创建」并禁用。
     */
    fun pinnedIds(ctx: Context): Set<String> {
        return try {
            if (Build.VERSION.SDK_INT < 25) emptySet()
            else {
                val sm = ctx.getSystemService(ShortcutManager::class.java)
                sm?.pinnedShortcuts?.map { it.id }?.toSet() ?: emptySet()
            }
        } catch (e: Throwable) {
            Log2.w(ctx, "shortcut: pinnedIds failed ${e.message}")
            emptySet()
        }
    }

    /** 与 SettingsBridge 的 appId 对应的钉入 id */
    fun pinIdOf(appId: String): String = PIN_PREFIX + appId

    // ------------------------------------------------------------------ 钉到桌面

    /**
     * v5.1.33（用户任务 1）：先问清楚"这台机器的桌面到底支不支持一键添加"。
     * 设置页把结果直接显示出来，省得用户点了半天不知道是被谁拦下的。
     */
    data class Support(
        val supported: Boolean,
        val launcher: String,
        val pinned: Int,
        val max: Int,
        val sdk: Int,
        val opMode: Int = -1
    )

    /**
     * v5.1.33（用户任务 1 · 真机根因，2026-11-11 实测确认）：
     *
     * MIUI/HyperOS 桌面落快捷方式前会调
     *   Utilities.hasAddShortcutPermission() → AppOpsManagerUtils.noteOpNoThrow(OP_INSTALL_SHORTCUT, uid, pkg)
     * 该 op 在 MIUI 上是自定义编号 **10017**（AOSP 上是 63），第三方应用默认 MODE_IGNORED，
     * 被拒时桌面只打一行日志「add shortcut failed, <pkg> has no permission」，
     * 于是 requestPinShortcut 返回 true 却永远不出图标（也没有 PinIc）。
     *
     * 这条不是普通权限，Android 没有给第三方应用修改它的 API，
     * 只能由用户在「设置 → 应用管理 → 飞牛平板 → 权限 → 其它权限 → 桌面快捷方式」里打开。
     */
    const val MIUI_OP_INSTALL_SHORTCUT = 10017

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** 读「桌面快捷方式」app-op 的当前 mode：0=允许 1=忽略 2=拒绝 3=默认 -1=读不到 */
    fun shortcutOpMode(ctx: Context): Int {
        val am = try {
            ctx.getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager
        } catch (_: Throwable) { null } ?: return -1
        val uid = ctx.applicationInfo.uid
        val pkg = ctx.packageName
        for (name in arrayOf("checkOpNoThrow", "unsafeCheckOpNoThrow", "checkOp")) {
            try {
                val m = am.javaClass.getMethod(
                    name,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java
                )
                return (m.invoke(am, MIUI_OP_INSTALL_SHORTCUT, uid, pkg) as? Int) ?: -1
            } catch (_: Throwable) {}
        }
        return -1
    }

    /** 是否允许创建桌面快捷方式（读不到时按"允许"处理，不误导用户） */
    fun canAddShortcut(ctx: Context): Boolean {
        val m = shortcutOpMode(ctx)
        return m == -1 || m == 0
    }

    /** 跳到 MIUI 的权限编辑页（拿不到就走应用详情页），返回是否成功拉起 */
    fun openShortcutPermSettings(ctx: Context): Boolean {
        val pkg = ctx.packageName
        val label = try {
            ctx.applicationInfo.loadLabel(ctx.packageManager).toString()
        } catch (_: Throwable) { pkg }
        val list = listOf(
            Intent("miui.intent.action.APP_PERM_EDITOR")
                .setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                )
                .putExtra("extra_package_name", pkg)
                .putExtra("extra_package_label", label),
            Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_package_name", pkg),
            Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + pkg)
            )
        )
        for (i in list) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                val r = ctx.packageManager.resolveActivity(i, 0)
                if (r == null) continue
                ctx.startActivity(i)
                Log2.w(ctx, "shortcut: openShortcutPermSettings -> ${i.component ?: i.action}")
                return true
            } catch (_: Throwable) {}
        }
        Log2.w(ctx, "shortcut: openShortcutPermSettings failed")
        return false
    }

    /** 当前默认桌面（HOME）包名 */
    fun launcherPkg(ctx: Context): String = try {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        ctx.packageManager.resolveActivity(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName ?: "?"
    } catch (_: Throwable) { "?" }

    /** 系统里所有 HOME 应用（有的 ROM 装了多个桌面，默认那个才管用） */
    fun launchers(ctx: Context): List<String> = try {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        ctx.packageManager.queryIntentActivities(i, 0).mapNotNull { it.activityInfo?.packageName }.distinct()
    } catch (_: Throwable) { emptyList() }

    fun support(ctx: Context): Support {
        val sup = try { ShortcutManagerCompat.isRequestPinShortcutSupported(ctx) } catch (_: Throwable) { false }
        val pinned = try { pinnedIds(ctx).size } catch (_: Throwable) { -1 }
        val max = try { ShortcutManagerCompat.getMaxShortcutCountPerActivity(ctx) } catch (_: Throwable) { -1 }
        return Support(sup, launcherPkg(ctx), pinned, max, Build.VERSION.SDK_INT, shortcutOpMode(ctx))
    }

    /** 钉一个应用图标到桌面（notify=false 时只写日志、不逐条 Toast —— 批量钉用） */
    fun requestPin(act: Activity, e: Apps.Entry, base: String, notify: Boolean = true): PinResult =
        pin(act, PIN_PREFIX + e.id, e.name, e.name + " · 飞牛", intentFor(act, e, base), e.iconUrl, notify = notify)

    /** 钉主程序图标（等价 PC 主窗口快捷方式） */
    fun requestPinMain(act: Activity, base: String): PinResult =
        pin(act, PIN_PREFIX + "main", "飞牛平板", "飞牛平板 · 主程序", mainIntent(act, base), "")

    /**
     * 钉电视直播图标（v5.1.31-tablet17：显式内置图标）。
     * 原来 iconUrl="" → 只能退回应用图标；现在用 res/drawable/ic_live 的位图，
     * 与 FPK 图标同一条路径（MIUI 只认位图）。
     */
    fun requestPinLive(act: Activity, base: String, notify: Boolean = true): PinResult =
        pin(
            act, LIVE_PIN_ID, "电视直播", "电视直播 · 飞牛", liveIntent(act, base), "",
            icon = iconFromRes(act, R.drawable.ic_live), notify = notify
        )

    /** 统一入口：支持性判断 → 构造 ShortcutInfo（带回调 PendingIntent）→ 用返回的 boolean 说话 */
    private fun pin(
        act: Activity,
        id: String,
        shortLabel: String,
        longLabel: String,
        target: Intent,
        iconUrl: String,
        icon: IconCompat? = null,
        notify: Boolean = true
    ): PinResult {
        // 显式传入的图标（内置矢量/位图）优先；否则按 iconUrl 取 FPK 图标、再回退应用图标。
        val iconCompat = icon ?: iconFor(act, iconUrl)
        // a) 先问桌面：能不能一键加？不能就给出可执行的替代做法（说明是桌面限制）
        val sup = support(act)
        // v5.1.33（用户任务 1 第 1 步）：这几个值必须留证据，别猜。
        Log2.w(
            act,
            "shortcut: pin.diag id=$id supported=${sup.supported} launcher=${sup.launcher} " +
                "launchers=${launchers(act)} pinnedBefore=${sup.pinned} max=${sup.max} sdk=${sup.sdk} " +
                "opMode=${sup.opMode} " +
                "fg=${act.hasWindowFocus()} finishing=${act.isFinishing} " +
                "thread=${Thread.currentThread().name} iconType=${iconCompat.type}"
        )
        if (!sup.supported) {
            Log2.w(act, "shortcut: pin unsupported (launcher=${sup.launcher} 不支持 requestPinShortcut)")
            return PinResult(
                ok = false,
                supported = false,
                msg = "当前桌面（${sup.launcher}）不支持一键添加快捷方式，请改用「应用宫格 → 打开」直接进入应用"
            )
        }
        return try {
            // e) MIUI/HyperOS：shortLabel / longLabel / icon / intent 一个都不能少
            val info = ShortcutInfoCompat.Builder(act, id)
                .setShortLabel(shortLabel)
                .setLongLabel(longLabel)
                .setIcon(iconCompat)
                .setIntent(target)
                .build()
            // b) 回调 PendingIntent：用户点「添加」后启动器回送，PinShortcutReceiver 收到即 Toast
            val cb = callbackOf(act, id, shortLabel)
            // c) 返回 false = 桌面根本没受理这次请求（有的 ROM 静默拒绝），文案要跟着变
            val ok = ShortcutManagerCompat.requestPinShortcut(act, info, cb.intentSender)
            Log2.w(act, "shortcut: pin.request id=$id accepted=$ok launcher=${sup.launcher}")
            if (ok) {
                // d) v5.1.33：MIUI 会出现「返回 true 但桌面没图标」（用户取消 / 静默丢弃）。
                //    2.5s 后回读 pinnedShortcuts 复核，把真实结果写进日志，别只报"已请求"。
                main.postDelayed({
                    try {
                        val after = pinnedIds(act)
                        val landed = after.contains(id)
                        Log2.w(act, "shortcut: pin.verify id=$id landed=$landed pinned=${after.size}")
                        // 批量钉时 notify=false：41 个应用连弹 41 个 Toast 既难看又会被系统限流，
                        // 汇总结果由页面统一显示。
                        if (!notify) return@postDelayed
                        if (landed) {
                            // v5.1.31-tablet17（用户任务 1）：结果用 Toast 明示，别只写日志
                            try {
                                android.widget.Toast.makeText(
                                    act.applicationContext,
                                    "✓ 已钉到桌面：$shortLabel",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            } catch (_: Throwable) {}
                        } else {
                            try {
                                val blocked = !canAddShortcut(act)
                                android.widget.Toast.makeText(
                                    act.applicationContext,
                                    if (blocked)
                                        "MIUI 桌面未允许本应用创建快捷方式：请在「权限 → 其它权限 → 桌面快捷方式」里允许后重试"
                                    else
                                        "桌面未确认添加：请确认系统的「添加」提示，或先开启「桌面快捷方式」权限再试",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            } catch (_: Throwable) {}
                        }
                    } catch (_: Throwable) {}
                }, 2500)
                PinResult(true, true, "已添加到桌面：$shortLabel")
            } else {
                Log2.w(act, "shortcut: pin.rejected id=$id （桌面未受理，未落图标）")
                PinResult(
                    false,
                    true,
                    "桌面没有受理这次添加：请在「设置 → 应用 → 飞牛平板 → 其它权限 → 桌面快捷方式」允许后重试"
                )
            }
        } catch (e: Throwable) {
            Log2.w(act, "shortcut: pin $id failed ${e.message}")
            PinResult(false, true, "创建失败：${e.message ?: e}")
        }
    }

    /** 回调广播的 PendingIntent（必须是显式的 + FLAG_IMMUTABLE） */
    private fun callbackOf(ctx: Context, id: String, label: String): PendingIntent {
        val i = Intent(ctx, PinShortcutReceiver::class.java).apply {
            action = ACTION_PIN_RESULT
            putExtra(EXTRA_NAME, label)
            putExtra(EXTRA_SHORTCUT_ID, id)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(ctx, 1000 + (id.hashCode() and 0x3FFF), i, flags)
    }

    // ------------------------------------------------------ 批量钉到桌面 / 清理

    /**
     * 批量钉桌面的汇总。
     * 三项之和 = total：ok（本次成功落桌面）+ exists（之前就钉过，跳过）+ fail（本次失败）。
     */
    data class BatchResult(
        val total: Int,
        val ok: Int,
        val exists: Int,
        val fail: Int,
        val failures: List<Pair<String, String>> = emptyList()
    )

    /** 清理结果 */
    data class ClearResult(
        val dynamicRemoved: Int,
        val pinnedDisabled: Int,
        val pinnedIds: List<String>,
        val msg: String
    )

    /** 批量钉是否在进行（防止连点重入） */
    @Volatile
    var pinAllRunning: Boolean = false
        private set

    /**
     * 设置页「全部钉到桌面」。
     *
     * 实现要点（逐条对应需求）：
     *  ① 顺序遍历 FPK 全部应用 + 「电视直播」，逐个 requestPinShortcut；
     *  ② 全程在主线程用 postDelayed 每 400ms 走一个，不 for 循环连发（MIUI 会节流/丢弃）；
     *  ③ 已钉过的（pinnedShortcuts 里已有 fn-pin-<id>）直接跳过，计入「已存在」；
     *  ④ 每个失败都写 Log2.w，并把原因带回汇总；
     *  ⑤ 全部发完后等 1.5s 再回读 pinnedShortcuts —— MIUI 是异步落图标，
     *     只有回读才是真实结果（requestPinShortcut 返回 true 不代表落地）。
     *
     * 进度/结束都通过回调返回，由 SettingsBridge 推送成
     * window.__fnosPinAllProgress / window.__fnosPinAllDone。
     */
    fun pinAll(
        act: Activity,
        base: String,
        onProgress: (done: Int, total: Int, ok: Int, exists: Int, fail: Int, current: String) -> Unit,
        onDone: (BatchResult) -> Unit
    ) {
        if (pinAllRunning) {
            Log2.w(act, "shortcut: pinAll.skip already running")
            onDone(BatchResult(0, 0, 0, 1, listOf("批量钉桌面" to "上一轮还没结束，请稍候")))
            return
        }
        // 桌面不支持就一次都别试 —— 41 次失败没意义
        val sup = support(act)
        if (!sup.supported) {
            Log2.w(act, "shortcut: pinAll.abort supported=false launcher=${sup.launcher}")
            onDone(
                BatchResult(
                    0, 0, 0, 1,
                    listOf(
                        "桌面（${sup.launcher}）" to
                            "不支持 requestPinShortcut（isRequestPinShortcutSupported=false）"
                    )
                )
            )
            return
        }
        val queue = ArrayList<Pair<String, Apps.Entry?>>()   // null = 电视直播
        val seen = HashSet<String>()
        for (e in Apps.all(act)) {
            if (e.id.isBlank() || !seen.add(e.id)) continue
            queue.add(e.id to e)
        }
        queue.add(ID_LIVE to null)
        val total = queue.size
        val pinnedAtStart = pinnedIds(act).filter { it.startsWith(PIN_PREFIX) }.toSet()
        pinAllRunning = true
        Log2.w(
            act,
            "shortcut: pinAll.start total=$total pinnedBefore=${pinnedAtStart.size} " +
                "opMode=${sup.opMode} launcher=${sup.launcher}"
        )

        var i = 0
        var okN = 0
        var existsN = 0
        var failN = 0
        val attempted = ArrayList<Pair<String, String>>()   // 名称 → pinId
        val failures = ArrayList<Pair<String, String>>()    // 名称 → 原因

        fun finish() {
            val after = pinnedIds(act)
            okN = 0
            failN = 0
            val blocked = !canAddShortcut(act)
            for ((label, pinId) in attempted) {
                if (after.contains(pinId)) {
                    okN++
                } else {
                    failN++
                    val reason =
                        if (blocked) "MIUI「桌面快捷方式」权限未开启（AppOps $MIUI_OP_INSTALL_SHORTCUT）"
                        else "桌面未落图标（请求已受理但未确认，或用户在系统弹窗里取消）"
                    failures.add(label to reason)
                    Log2.w(act, "shortcut: pinAll.fail id=$pinId name=$label reason=$reason")
                }
            }
            pinAllRunning = false
            val r = BatchResult(total, okN, existsN, failN, failures)
            Log2.w(
                act,
                "shortcut: pinAll.done total=$total ok=$okN exists=$existsN fail=$failN " +
                    "pinnedAfter=${after.size} blocked=$blocked"
            )
            if (failures.isNotEmpty()) {
                Log2.w(
                    act,
                    "shortcut: pinAll.failures " +
                        failures.joinToString("; ") { it.first + "=>" + it.second }
                )
            }
            onDone(r)
        }

        fun step() {
            if (i >= total) {
                // MIUI 落图标是异步的：给它 1.5s，再回读真实结果
                main.postDelayed({ finish() }, 1500)
                return
            }
            val (id, entry) = queue[i]
            val label = entry?.name ?: "电视直播"
            val pinId = if (entry == null) LIVE_PIN_ID else PIN_PREFIX + id
            if (pinnedAtStart.contains(pinId)) {
                existsN++
                Log2.w(act, "shortcut: pinAll.exists id=$pinId name=$label （已钉，跳过）")
            } else {
                val r = try {
                    if (entry == null) requestPinLive(act, base, notify = false)
                    else requestPin(act, entry, base, notify = false)
                } catch (e: Throwable) {
                    PinResult(false, true, "创建失败：${e.message ?: e}")
                }
                attempted.add(label to pinId)
                if (r.ok) okN++ else {
                    failN++
                    failures.add(label to r.msg)
                    Log2.w(act, "shortcut: pinAll.reject id=$pinId name=$label msg=${r.msg}")
                }
            }
            i++
            onProgress(i, total, okN, existsN, failN, label)
            main.postDelayed({ step() }, 400)
        }
        step()
    }

    /**
     * 设置页「清理全部快捷方式」。
     *
     * Android 没有「删除桌面图标」的 API（快捷方式一旦钉住，只能由启动器/用户移除），
     * 所以这里做两件能做的：
     *  ① removeAllDynamicShortcuts() —— 清掉长按图标可见的动态快捷方式；
     *  ② disableShortcuts() —— 把已钉的快捷方式置灰并给出禁用文案，
     *     用户长按桌面灰图标即可移除（设置页的确认对话框里已说明）。
     */
    fun clearAll(ctx: Context): ClearResult {
        val sm = try { ctx.getSystemService(ShortcutManager::class.java) } catch (_: Throwable) { null }
        var dyn = 0
        try {
            dyn = sm?.dynamicShortcuts?.size ?: 0
            sm?.removeAllDynamicShortcuts()
        } catch (e: Throwable) {
            Log2.w(ctx, "shortcut: clearAll.removeDynamic failed ${e.message}")
        }
        val pinned = pinnedIds(ctx).toList()
        var disabled = 0
        if (pinned.isNotEmpty()) {
            try {
                sm?.disableShortcuts(pinned, "已清理：长按此图标可从桌面移除")
                disabled = pinned.size
            } catch (e: Throwable) {
                Log2.w(ctx, "shortcut: clearAll.disable failed ${e.message}")
            }
        }
        val msg = if (pinned.isEmpty()) {
            "已清理动态快捷方式 $dyn 个；桌面当前没有已钉图标"
        } else {
            "已清理动态快捷方式 $dyn 个；$disabled 个桌面图标已置灰" +
                "（Android 不允许应用直接删桌面图标，请长按灰图标移除）"
        }
        Log2.w(ctx, "shortcut: clearAll.done dynamic=$dyn pinned=$disabled ids=${pinned}")
        return ClearResult(dyn, disabled, pinned, msg)
    }
}
