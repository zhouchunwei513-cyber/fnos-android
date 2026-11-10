package com.fnos.tablet

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 皮肤系统（对齐 PC 端）：皮肤包 = .fskin.zip，内含 manifest.json + 背景资源。
 * manifest：
 * { "name":"...", "titlebar": { "height":34,
 *     "background": { "type":"image", "src":"bg.jpg" },
 *     "content": { "iconDark": false } } }
 *
 * v5.1.30：偏好设置页换成 PC 同款 settings.html 后，皮肤的「列出 / 安装 / 删除 / 选用」
 * 全部经由 SkinsBridge（SettingsBridge.listSkins / installSkin / deleteSkin）走这里，
 * 返回结构与 PC 主进程 settings:list-skins / install-skin / delete-skin 一一对齐。
 */
object Skins {

    data class Skin(val dir: File, val name: String, val heightDp: Int, val bgFile: File?, val iconDark: Boolean)

    fun skinsDir(ctx: Context): File = File(ctx.filesDir, "skins").apply { mkdirs() }

    fun list(ctx: Context): List<Skin> =
        (skinsDir(ctx).listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .mapNotNull { parse(it) }
            .sortedBy { it.name }

    /** 皮肤目录名（= settings.json 的 activeSkin）= PC 端的 skins/<name> 目录名 */
    fun names(ctx: Context): List<String> =
        (skinsDir(ctx).listFiles() ?: emptyArray())
            .filter { it.isDirectory && File(it, "manifest.json").exists() }
            .map { it.name }
            .sorted()

    /** 按名取目录；拒绝路径穿越（PC __safeSkinName 的等价实现） */
    fun dirOf(ctx: Context, name: String): File? {
        val safe = name.trim()
        if (safe.isEmpty() || safe == "." || safe.contains("..")) return null
        if (safe.contains('/') || safe.contains('\\')) return null
        val d = File(skinsDir(ctx), safe)
        return if (d.isDirectory) d else null
    }

    /** 皮肤 manifest.json 原文（settings.html 用它渲染名称/版本） */
    fun manifestOf(ctx: Context, name: String): JSONObject? {
        return try {
            val d = dirOf(ctx, name) ?: return null
            val f = File(d, "manifest.json")
            if (f.exists()) JSONObject(f.readText()) else null
        } catch (_: Exception) { null }
    }

    /** preview.png → data:image/png;base64,…（与 PC settings:list-skins 同一做法） */
    fun previewDataUrl(ctx: Context, name: String): String {
        return try {
            val d = dirOf(ctx, name) ?: return ""
            val f = File(d, "preview.png")
            if (!f.exists() || f.length() <= 0L || f.length() > 2L * 1024 * 1024) return ""
            "data:image/png;base64," +
                android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
        } catch (_: Exception) { "" }
    }

    fun parse(dir: File): Skin? {
        val mf = File(dir, "manifest.json")
        if (!mf.exists()) return null
        return try {
            val o = JSONObject(mf.readText())
            val tb = o.optJSONObject("titlebar")
            val bg = tb?.optJSONObject("background")
            val src = bg?.optString("src").orEmpty()
            val h = tb?.optInt("height", 34) ?: 34
            val iconDark = tb?.optJSONObject("content")?.optBoolean("iconDark", false) ?: false
            Skin(dir, o.optString("name", dir.name), h.coerceIn(20, 80), if (src.isNotBlank()) File(dir, src) else null, iconDark)
        } catch (_: Exception) { null }
    }

    fun activeSkin(ctx: Context): Skin? {
        val st = Settings.load(ctx)
        if (st.activeSkin.isBlank()) return null
        val dir = File(skinsDir(ctx), st.activeSkin)
        return if (dir.isDirectory) parse(dir) else null
    }

    fun select(ctx: Context, name: String) {
        val st = Settings.load(ctx)
        Settings.save(ctx, st.copy(activeSkin = name))
    }

    /**
     * 删除已安装皮肤（PC settings:delete-skin 的等价实现）：
     * 删掉 skins/<name>/；若删的正是当前激活皮肤，自动回落默认（activeSkin=''）。
     * 返回 wasActive 供调用方回话。
     */
    fun delete(ctx: Context, name: String): Pair<Boolean, Boolean> {
        val dir = dirOf(ctx, name) ?: return Pair(false, false)
        val wasActive = Settings.load(ctx).activeSkin.trim() == name.trim()
        val gone = try { dir.deleteRecursively() } catch (_: Exception) { false }
        if (!gone) return Pair(false, wasActive)
        if (wasActive) select(ctx, "")
        Log2.w(ctx, "skin: delete $name wasActive=$wasActive")
        return Pair(true, wasActive)
    }

    /** 从 SAF 选中的 zip 安装皮肤，返回皮肤名（失败返回 null） */
    fun installFromZip(ctx: Context, uri: Uri): String? {
        val root = skinsDir(ctx)
        val tmp = File(root, "_tmp_${System.currentTimeMillis()}")
        return try {
            tmp.mkdirs()
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    var e = zip.nextEntry
                    while (e != null) {
                        val n = e.name.replace('\\', '/')
                        if (!e.isDirectory && !n.contains("..")) {
                            val f = File(tmp, n)
                            f.parentFile?.mkdirs()
                            f.outputStream().use { zip.copyTo(it) }
                        }
                        zip.closeEntry()
                        e = zip.nextEntry
                    }
                }
            }
            // manifest 可能在根目录，也可能在唯一子目录里
            var manifest = File(tmp, "manifest.json")
            if (!manifest.exists()) {
                val subs = tmp.listFiles()?.filter { it.isDirectory } ?: emptyList()
                if (subs.size == 1) manifest = File(subs[0], "manifest.json")
            }
            if (!manifest.exists()) { tmp.deleteRecursively(); return null }
            val name = JSONObject(manifest.readText()).optString("name", "皮肤${System.currentTimeMillis() % 1000}")
            val srcDir = manifest.parentFile!!
            val target = File(root, name)
            if (target.exists()) target.deleteRecursively()
            if (srcDir == tmp) {
                tmp.renameTo(target)
            } else {
                srcDir.copyRecursively(target, overwrite = true)
                tmp.deleteRecursively()
            }
            select(ctx, name)
            name
        } catch (e: Exception) {
            tmp.deleteRecursively(); null
        }
    }

    /** 把皮肤应用到标题栏；返回标题栏高度 dp */
    fun applyTitlebar(ctx: Context, bar: View, skin: Skin?): Int {
        return if (skin?.bgFile?.exists() == true) {
            val bmp = BitmapFactory.decodeFile(skin.bgFile.absolutePath)
            if (bmp != null) {
                val d = BitmapDrawable(ctx.resources, bmp)
                d.gravity = Gravity.FILL
                bar.background = d
                skin.heightDp
            } else { bar.setBackgroundColor(0xFF14121B.toInt()); 48 }
        } else { bar.setBackgroundColor(0xFF14121B.toInt()); 48 }
    }
}
