package com.fnos.tablet

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v5.1.20：启动步骤日志。闪退可能是 native 层（WebView/ROM），
 * 未捕获异常处理器抓不到，所以把每一步都落盘，事后看卡在哪。
 */
object Log2 {
    private const val FILE = "startup.log"
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    @Synchronized
    fun w(ctx: Context, msg: String) {
        try {
            val f = File(ctx.filesDir, FILE)
            if (f.exists() && f.length() > 256 * 1024) f.delete()
            f.appendText("${fmt.format(Date())} $msg\n")
        } catch (_: Exception) {}
    }

    fun tail(ctx: Context, lines: Int = 40): String = try {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) "" else f.readLines().takeLast(lines).joinToString("\n")
    } catch (_: Exception) { "" }

    fun clear(ctx: Context) { try { File(ctx.filesDir, FILE).delete() } catch (_: Exception) {} }
}
