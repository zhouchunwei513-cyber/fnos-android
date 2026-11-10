package com.fnos.tablet

import android.content.Context
import android.os.Build
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 原生诊断上报：把 App 自己的状态/日志/崩溃 POST 到 NAS 8898。
 * ——Termux 读不到 App 私有目录（Scoped Storage），但 App 自己可以主动上报。
 */
object Diag {
    private const val ENDPOINT = "http://100.103.38.13:8898/report"
    private val pool = Executors.newSingleThreadExecutor()

    fun report(ctx: Context, kind: String, extra: String = "") {
        pool.execute {
            try {
                val body = StringBuilder()
                body.append("{\"kind\":\"").append(kind).append("\",")
                body.append("\"ts\":").append(System.currentTimeMillis()).append(",")
                body.append("\"device\":\"").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\",")
                body.append("\"android\":\"").append(Build.VERSION.RELEASE).append("/").append(Build.VERSION.SDK_INT).append("\",")
                body.append("\"pkg\":\"").append(ctx.packageName).append("\",")
                body.append("\"native\":true,")
                body.append("\"extra\":\"").append(esc(extra)).append("\",")
                body.append("\"startup\":\"").append(esc(Log2.tail(ctx, 60))).append("\",")
                body.append("\"crash\":\"").append(esc(readCrash(ctx))).append("\"}")
                httpPost(body.toString())
            } catch (_: Throwable) {}
        }
    }

    fun reportNow(ctx: Context, kind: String, extra: String = "") {
        try {
            val body = "{\"kind\":\"$kind\",\"ts\":${System.currentTimeMillis()},\"pkg\":\"${ctx.packageName}\",\"native\":true,\"extra\":\"${esc(extra)}\",\"startup\":\"${esc(Log2.tail(ctx, 60))}\"}"
            httpPost(body)
        } catch (_: Throwable) {}
    }

    private fun readCrash(ctx: Context): String = try {
        val f = File(ctx.filesDir, App.CRASH_FILE)
        if (f.exists()) f.readText().take(4000) else ""
    } catch (_: Throwable) { "" }

    private fun esc(s: String): String = s
        .replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "").replace("\t", " ")

    private fun httpPost(body: String) {
        val c = URL(ENDPOINT).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 6000
        c.readTimeout = 6000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        try { c.responseCode } catch (_: Throwable) {}
        c.disconnect()
    }
}
