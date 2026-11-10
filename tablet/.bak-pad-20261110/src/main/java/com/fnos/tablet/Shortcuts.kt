package com.fnos.tablet

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
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
 *     限制（多数启动器 5 个），超出的通过下面的「钉到桌面」补齐。
 *  ② requestPinShortcut —— 菜单/设置里的「创建快捷方式」，
 *     用户确认后系统桌面直接生成图标，等价 PC 创建 .lnk。
 *
 * 点快捷方式 → 直接到 AppWindowActivity（带 anchor + base），不进主界面。
 *
 * v5.1.30 修复（用户反馈「只提示已请求，桌面没图标」）：
 *  · 旧代码 requestPinShortcut 传了 null 回调，且界面无条件 Toast「已请求」——
 *    启动器不支持 requestPin 时也会说成功。现在：
 *      a) 先 isRequestPinShortcutSupported()，不支持直接给出可执行的替代做法（长按 → 拖到桌面）；
 *      b) 支持则挂 PendingIntent 回调（PinShortcutReceiver 收到后 Toast「已添加到桌面」）；
 *      c) 用 requestPinShortcut 返回的 boolean 决定文案，不再无脑说「已请求」；
 *      d) syncDynamic 保留（长按图标一定能看到列表并可拖到桌面）；
 *      e) MIUI/HyperOS 兼容：ShortcutInfo 一定带 shortLabel/longLabel/icon/intent，
 *         且图标统一光栅化成位图（自适应图标 XML 在部分启动器上画不出来），
 *         PendingIntent 一律 FLAG_IMMUTABLE。
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

    /** 一次「钉到桌面」请求的结果：界面据此说话，而不是一律「已请求」 */
    data class PinResult(val ok: Boolean, val supported: Boolean, val msg: String)

    private const val ICON_SIZE = 192

    @Volatile
    private var iconCache: IconCompat? = null

    /**
     * 快捷方式图标：**统一光栅化成位图**。
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
        val icon = iconOf(ctx)
        return Apps.all(ctx).map { e ->
            ShortcutInfoCompat.Builder(ctx, "fn-app-" + e.id)
                .setShortLabel(e.name)
                .setLongLabel(e.name + " · 飞牛")
                .setIcon(icon)
                .setIntent(intentFor(ctx, e, base))
                .build()
        }
    }

    /**
     * 注册成长按图标可见的动态快捷方式。
     * 数量上限由系统给（常 5），超出的部分不报错、只在偏好设置页里可钉。
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

    // ------------------------------------------------------------------ 钉到桌面

    /** 钉一个应用图标到桌面 */
    fun requestPin(act: Activity, e: Apps.Entry, base: String): PinResult =
        pin(act, "fn-pin-" + e.id, e.name, e.name + " · 飞牛", intentFor(act, e, base))

    /** 钉主程序图标（等价 PC 主窗口快捷方式） */
    fun requestPinMain(act: Activity, base: String): PinResult =
        pin(act, "fn-pin-main", "飞牛平板", "飞牛平板 · 主程序", mainIntent(act, base))

    /** 钉电视直播图标 */
    fun requestPinLive(act: Activity, base: String): PinResult =
        pin(act, "fn-pin-live", "电视直播", "电视直播 · 飞牛", liveIntent(act, base))

    /** 统一入口：支持性判断 → 构造 ShortcutInfo（带回调 PendingIntent）→ 用返回的 boolean 说话 */
    private fun pin(
        act: Activity,
        id: String,
        shortLabel: String,
        longLabel: String,
        target: Intent
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
                .setIcon(iconOf(act))
                .setIntent(target)
                .build()
            // b) 回调 PendingIntent：用户点「添加」后启动器回送，PinShortcutReceiver 收到即 Toast
            val cb = callbackOf(act, id, shortLabel)
            // c) 返回 false = 桌面根本没受理这次请求（有的 ROM 静默拒绝），文案要跟着变
            //    androidx.core 1.9.0 的 requestPinShortcut 第三参是 IntentSender
            val ok = ShortcutManagerCompat.requestPinShortcut(act, info, cb.intentSender)
            Log2.w(act, "shortcut: pin $id ok=$ok")
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
