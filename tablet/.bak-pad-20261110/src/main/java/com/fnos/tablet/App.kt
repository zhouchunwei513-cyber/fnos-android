package com.fnos.tablet

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * v5.1.18：崩溃兜底。平板拿不到 adb logcat，所以把未捕获异常落盘，
 * 下次启动时由 MainActivity 弹窗展示，用户可直接复制发回。
 */
/** 全局 Application 上下文（给没带 Context 的纯逻辑层用：ServerAddr / Apps / 快捷方式） */
object AppCtx {
    @Volatile
    var ctx: android.content.Context? = null
        private set

    fun bind(c: android.content.Context) { ctx = c.applicationContext }
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCtx.bind(this)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val text = buildString {
                    appendLine("时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
                    appendLine("线程: ${t?.name}")
                    appendLine("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                    appendLine("版本: ${packageName}")
                    appendLine("---- 堆栈 ----")
                    append(sw.toString())
                }
                File(filesDir, CRASH_FILE).writeText(text)
                try { Diag.reportNow(this@App, "crash", text.take(600)) } catch (_: Throwable) {}
                try {
                    val ext = getExternalFilesDir(null)
                    if (ext != null) File(ext, CRASH_FILE).writeText(text)
                } catch (_: Exception) {}
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    companion object {
        const val CRASH_FILE = "crash_last.txt"
        fun crashFile(app: Application) = File(app.filesDir, CRASH_FILE)
    }
}
