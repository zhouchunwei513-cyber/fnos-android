package com.fnos.tablet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * 钉到桌面的回执。
 *
 * ShortcutManagerCompat.requestPinShortcut(ctx, info, resultIntent) 的第三个参数就是这个
 * PendingIntent：用户在启动器弹出的「添加到主屏幕？」对话框点确定、快捷方式真的落盘之后，
 * 启动器会把它 send() 出来（部分启动器不回送，属正常现象）。
 *
 * 有回执 → Toast「已添加到桌面」，用户能立刻确认；没回执也不再谎报成功——
 * 成败以 requestPinShortcut 的返回值为准（见 Shortcuts.pin）。
 */
class PinShortcutReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val label = intent?.getStringExtra(Shortcuts.EXTRA_NAME).orEmpty()
        val id = intent?.getStringExtra(Shortcuts.EXTRA_SHORTCUT_ID).orEmpty()
        Log2.w(context, "shortcut: pinned callback label=$label id=$id")
        val msg = if (label.isBlank()) "已添加到桌面" else "已添加到桌面：$label"
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(context.applicationContext, msg, Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }
        // 钉完顺手把动态快捷方式刷一遍：长按图标里的列表也保持最新
        try {
            val base = Settings.load(context).server
            Shortcuts.syncDynamic(context.applicationContext, base)
        } catch (_: Throwable) {}
    }
}
