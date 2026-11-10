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
 * 而 requestPinShortcut 只能在主线程调 —— 不能在主线程做网络。
 * 所以设置页一打开就把列表里的图标全预取到 filesDir/icons/，
 * 用户点「创建快捷方式」时直接命中缓存，主线程零等待。
 */
object RemoteIcons {

    private const val MAX_PX = 256
    private val mem = ConcurrentHashMap<String, Bitmap>()
    private val pool = Executors.newFixedThreadPool(3)

    private fun dir(ctx: Context) = File(ctx.filesDir, "icons").apply { mkdirs() }

    private fun fileOf(ctx: Context, url: String): File {
        val key = Integer.toHexString(url.hashCode()) + "-" + url.length
        return File(dir(ctx), key + ".png")
    }

    /** 只读缓存（不联网）：内存 → 磁盘 → null。可以在主线程调。 */
    fun cached(ctx: Context, url: String): Bitmap? {
        if (url.isBlank()) return null
        mem[url]?.let { return it }
        val f = fileOf(ctx, url)
        if (!f.exists() || f.length() == 0L) return null
        val b = try { BitmapFactory.decodeFile(f.absolutePath) } catch (_: Throwable) { null }
        if (b != null) mem[url] = b
        return b
    }

    /** 联网取一张（阻塞）。已有缓存直接返回。 */
    fun fetch(ctx: Context, url: String, timeoutMs: Int = 3000): Bitmap? {
        cached(ctx, url)?.let { return it }
        if (url.isBlank()) return null
        var c: HttpURLConnection? = null
        return try {
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", NasUa.DESKTOP)
            }
            if (c.responseCode >= 400) return null
            val bytes = c.inputStream.use { it.readBytes() }
            val bmp = decodeScaled(bytes) ?: return null
            mem[url] = bmp
            try { fileOf(ctx, url).writeBytes(bytes) } catch (_: Exception) {}
            bmp
        } catch (e: Exception) {
            Log2.w(ctx, "icon: fetch failed ${e.message} $url")
            null
        } finally {
            try { c?.disconnect() } catch (_: Exception) {}
        }
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

    /** 后台预取（设置页展示列表时调用），每张最多 3s */
    fun warm(ctx: Context, urls: List<String>) {
        val appCtx = ctx.applicationContext
        for (u in urls.distinct().take(40)) {
            if (u.isBlank() || cached(appCtx, u) != null) continue
            pool.execute { try { fetch(appCtx, u, 3000) } catch (_: Throwable) {} }
        }
    }
}
