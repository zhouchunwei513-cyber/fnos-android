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
 *  ① setDynamicShortcuts —— 长按应用图标能直接看到「应用中心 / 文件管理 / 影视 / …」，
 *     与 PC 右键「发送到桌面」后快捷方式常驻一致；数量受 getMaxShortcutCountPerActivity()
 *     限制（多数启动器 5~15 个），超出的通过下面的「钉到桌面」补齐。
 *  ② requestPinShortcut —— 菜单/设置里的「创建快捷方式」，
 *     用户确认后系统桌面直接生成图标，等价 PC 创建 .lnk。
 *
 * 点快捷方式 → 直接到 AppWindowActivity（带 anchor + base），不进主界面。
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
    const val EXTRA_NAME = "name"
    const val EXTRA_SHORTCUT_ID = "shortcutId"

    /** PC create-desktop-shortcut 里电视直播的保留 ID */
    const val ID_LIVE = "__fnos.live__"

    /** 钉到桌面用的 id 前缀（与动态快捷方式的 fn-app- 区分） */
    private const val PIN_PREFIX = "fn-pin-"

    /** 一次「钉到桌面」请求的结果：界面据此说话，而不是一律「已请求」 */
    data class PinResult(val ok: Boolean, val supported: Boolean, val msg: String)

    private const val ICON_SIZE = 192

    @Volatile
    private var iconCache: IconCompat? = null

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

    /** 应用快捷方式的 Intent（冷启动直达应用窗） */
    fun intentFor(ctx: Context, e: Apps.Entry, base: String): Intent {
        val i = Intent(ctx, AppWindowActivity::class.java)
        i.action = ACTION_OPEN_APP
        i.putExtra(EXTRA_ANCHOR, e.anchor)
        i.putExtra(EXTRA_NAME, e.name)
        i.putExtra(EXTRA_BASE, base)
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
        i.putExtra(EXTRA_BASE, base)
        i.putExtra(EXTRA_NAME, "电视直播")
        i.data = Uri.parse("fnos-tablet://live")
        return i
    }

    fun shortcutsFor(ctx: Context, base: String): List<ShortcutInfoCompat> {
        return Apps.all(ctx).map { e ->
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

    /** 钉一个应用图标到桌面 */
    fun requestPin(act: Activity, e: Apps.Entry, base: String): PinResult =
        pin(act, PIN_PREFIX + e.id, e.name, e.name + " · 飞牛", intentFor(act, e, base), e.iconUrl)

    /** 钉主程序图标（等价 PC 主窗口快捷方式） */
    fun requestPinMain(act: Activity, base: String): PinResult =
        pin(act, PIN_PREFIX + "main", "飞牛平板", "飞牛平板 · 主程序", mainIntent(act, base), "")

    /** 钉电视直播图标 */
    fun requestPinLive(act: Activity, base: String): PinResult =
        pin(act, PIN_PREFIX + "live", "电视直播", "电视直播 · 飞牛", liveIntent(act, base), "")

    /** 统一入口：支持性判断 → 构造 ShortcutInfo（带回调 PendingIntent）→ 用返回的 boolean 说话 */
    private fun pin(
        act: Activity,
        id: String,
        shortLabel: String,
        longLabel: String,
        target: Intent,
        iconUrl: String
    ): PinResult {
        // a) 先问桌面：能不能一键加？不能就给出可执行的替代做法（说明是桌面限制）
        val supported = try {
            ShortcutManagerCompat.isRequestPinShortcutSupported(act)
        } catch (_: Throwable) { false }
        if (!supported) {
            Log2.w(act, "shortcut: pin unsupported (launcher 不支持 requestPinShortcut)")
            return PinResult(
                ok = false,
                supported = false,
                msg = "当前桌面不支持一键添加，请长按应用图标 → 把快捷方式拖到桌面" +
                    "（这是桌面启动器的限制，不是客户端问题；长按图标即可看到常用应用列表）"
            )
        }
        return try {
            // e) MIUI/HyperOS：shortLabel / longLabel / icon / intent 一个都不能少
            val info = ShortcutInfoCompat.Builder(act, id)
                .setShortLabel(shortLabel)
                .setLongLabel(longLabel)
                .setIcon(iconFor(act, iconUrl))
                .setIntent(target)
                .build()
            // b) 回调 PendingIntent：用户点「添加」后启动器回送，PinShortcutReceiver 收到即 Toast
            val cb = callbackOf(act, id, shortLabel)
            // c) 返回 false = 桌面根本没受理这次请求（有的 ROM 静默拒绝），文案要跟着变
            val ok = ShortcutManagerCompat.requestPinShortcut(act, info, cb.intentSender)
            Log2.w(act, "shortcut: pin $id ok=$ok icon=${iconUrl.isNotBlank()}")
            if (ok) {
                PinResult(true, true, "已添加到桌面：$shortLabel")
            } else {
                PinResult(
                    false,
                    true,
                    "桌面没有受理这次添加，请长按应用图标 → 把快捷方式拖到桌面"
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
}
