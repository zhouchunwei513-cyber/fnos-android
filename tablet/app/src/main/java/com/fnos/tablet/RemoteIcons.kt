package com.fnos.tablet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.drawable.IconCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * FPK 图标（http://<nas>:18080/api/icons/<name>/256）的本地缓存。
 *
 * 为什么要落盘：快捷方式图标必须是一个已经解码好的 Bitmap 交给 ShortcutInfo，
 * 而 requestPinShortcut 只能在主线程调 —— 不能在主线程做网络，
 * 所以设置页一打开就把列表里的图标全预取到 filesDir/icons/，
 * 用户点「创建快捷方式」时直接命中缓存，主线程零等待。
 *
 * v5.1.33（用户第 2 项）：设置页的 `<img src="http://<nas>:18080/...">` 被页面 CSP
 * `img-src 'self' data: blob:` 拦住（webconsole 实测报 Refused to load）。
 * 修法是**原生代取**：这里把拿到的原始字节缓存两份（内存 bytes + 磁盘），
 * 再按需经 [dataUri] 转成 `data:image/png;base64,...` 交给页面。
 * 页面只认 data:（CSP 白名单里有），PC 页面本身的 CSP 不用动。
 */
object RemoteIcons {

    private const val MAX_PX = 256
    private val mem = ConcurrentHashMap<String, Bitmap>()
    private val bytesMem = ConcurrentHashMap<String, ByteArray>()
    private val b64Mem = ConcurrentHashMap<String, String>()
    private val pool = Executors.newFixedThreadPool(3)

    private fun dir(ctx: Context) = File(ctx.filesDir, "icons").apply { mkdirs() }

    private fun fileOf(ctx: Context, url: String): File {
        val key = Integer.toHexString(url.hashCode()) + "-" + url.length
        return File(dir(ctx), key + ".png")
    }

    /** 只读缓存（不联网）：内存 → 磁盘 → null。**可以在主线程调用**。 */
    fun cached(ctx: Context, url: String): Bitmap? {
        if (url.isBlank()) return null
        mem[url]?.let { return it }
        val f = fileOf(ctx, url)
        if (!f.exists() || f.length() == 0L) return null
        val b = try { BitmapFactory.decodeFile(f.absolutePath) } catch (_: Throwable) { null }
        if (b != null) mem[url] = b
        return b
    }

    /**
     * 原始字节：内存 → 磁盘 →（allowNetwork 时）联网。
     * data URI 需要**原始 PNG 字节**，不能拿解码后的 Bitmap 重新压（会变大且失真）。
     */
    fun bytes(ctx: Context, url: String, allowNetwork: Boolean): ByteArray? {
        if (url.isBlank()) return null
        bytesMem[url]?.let { return it }
        val f = fileOf(ctx, url)
        if (f.exists() && f.length() > 0L) {
            val b = try { f.readBytes() } catch (_: Exception) { null }
            if (b != null && b.isNotEmpty()) {
                bytesMem[url] = b
                return b
            }
        }
        return if (allowNetwork) download(ctx, url) else null
    }

    /** 联网取一张的原始字节并落盘 */
    private fun download(ctx: Context, url: String, timeoutMs: Int = 3000): ByteArray? {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", NasUa.DESKTOP)
            }
            if (c.responseCode >= 400) return null
            val bytes = c.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) return null
            bytesMem[url] = bytes
            try { fileOf(ctx, url).writeBytes(bytes) } catch (_: Exception) {}
            bytes
        } catch (e: Exception) {
            Log2.w(ctx, "icon: fetch failed ${e.message} $url")
            null
        } finally {
            try { c?.disconnect() } catch (_: Exception) {}
        }
    }

    /** 联网取一张（阻塞，已缓存直接返回）。 */
    fun fetch(ctx: Context, url: String, timeoutMs: Int = 3000): Bitmap? {
        cached(ctx, url)?.let { return it }
        if (url.isBlank()) return null
        val bytes = download(ctx, url, timeoutMs) ?: return null
        val bmp = decodeScaled(bytes) ?: return null
        mem[url] = bmp
        return bmp
    }

    private fun decodeScaled(bytes: ByteArray): Bitmap? = try {
        val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt)
        var sample = 1
        while (opt.outWidth / sample > MAX_PX || opt.outHeight / sample > MAX_PX) sample *= 2
        BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    } catch (_: Throwable) { null }

    fun iconOf(ctx: Context, url: String): IconCompat? {
        val b = cached(ctx, url) ?: return null
        return try { IconCompat.createWithBitmap(b) } catch (_: Throwable) { null }
    }

    /**
     * 页面用：`data:image/png;base64,...`。
     * wait=false 只吃缓存（主线程安全）；wait=true 缓存没有就联网（仅限 JavaBridge 线程）。
     */
    fun dataUri(ctx: Context, url: String, wait: Boolean): String? {
        if (url.isBlank()) return null
        b64Mem[url]?.let { return it }
        val bytes = bytes(ctx, url, wait) ?: return null
        val s = try {
            "data:image/png;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (_: Throwable) { return null }
        b64Mem[url] = s
        return s
    }

    /** 后台预取（设置页展示列表时调用），每张最长 3s */
    fun warm(ctx: Context, urls: List<String>) {
        val appCtx = ctx.applicationContext
        for (u in urls.distinct()) {
            if (u.isBlank() || bytes(appCtx, u, false) != null) continue
            pool.execute { try { fetch(appCtx, u, 3000) } catch (_: Throwable) {} }
        }
    }
}
