package com.fnos.tablet

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * v5.1.18锛氬穿婧冨厹搴曘€傚钩鏉挎嬁涓嶅埌 adb logcat锛屾墍浠ユ妸鏈崟鑾峰紓甯歌惤鐩橈紝
 * 涓嬫鍚姩鏃剁敱 MainActivity 寮圭獥灞曠ず锛岀敤鎴峰彲鐩存帴澶嶅埗鍙戝洖銆? */
/** 鍏ㄥ眬 Application 涓婁笅鏂囷紙缁欐病甯?Context 鐨勭函閫昏緫灞傜敤锛歋erverAddr / Apps / 蹇嵎鏂瑰紡锛?*/
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
                    appendLine("鏃堕棿: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
                    appendLine("绾跨▼: ${t?.name}")
                    appendLine("璁惧: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                    appendLine("鐗堟湰: ${packageName}")
                    appendLine("---- 鍫嗘爤 ----")
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

