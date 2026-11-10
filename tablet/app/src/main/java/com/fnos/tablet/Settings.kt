package com.fnos.tablet

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 与 PC 端 settings.json 尽量保持同构：server / line / skin / corners。
 * 存于 filesDir/settings.json，便于后续与 PC 端互导。
 */
object Settings {
    private const val FILE = "settings.json"

    data class State(
        val server: String = "192.168.31.101:5666",
        val line: String = "lan",        // lan | ipv6 | frp | fnid
        val activeSkin: String = "",
        val lastUser: String = "",
        val patchUrl: String = "http://100.103.38.13:8899"
    )

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    /**
     * 原始 settings.json（保留 PC 端全部字段：titleBarMaterial / barCorner / winCorner / …）。
     * 标题栏与皮肤的配置完全复用 PC 端键名，PC 的 settings.json 可以整份拿过来直接用。
     */
    fun raw(ctx: Context): JSONObject = try {
        val f = file(ctx)
        if (f.exists()) JSONObject(f.readText()) else JSONObject()
    } catch (_: Exception) { JSONObject() }

    fun load(ctx: Context): State {
        return try {
            val f = file(ctx)
            if (!f.exists()) State() else {
                val o = JSONObject(f.readText())
                State(
                    server = o.optString("server", State().server),
                    line = o.optString("line", "lan"),
                    activeSkin = o.optString("activeSkin", ""),
                    lastUser = o.optString("lastUser", ""),
                    patchUrl = o.optString("patchUrl", "http://100.103.38.13:8899")
                )
            }
        } catch (_: Exception) { State() }
    }

    fun save(ctx: Context, s: State) {
        try {
            // 合并写入：settings.json 里还有 PC 端的标题栏/皮肤字段（titleBarMaterial、barCorner…），
            // 整份覆盖会把它们抹掉，所以只在原对象上更新本类负责的字段。
            val o = raw(ctx)
            o.put("server", s.server)
            o.put("line", s.line)
            o.put("activeSkin", s.activeSkin)
            o.put("lastUser", s.lastUser)
            o.put("patchUrl", s.patchUrl)
            file(ctx).writeText(o.toString(2))
        } catch (_: Exception) {}
    }

    /**
     * 整份写回。
     * 偏好设置页（PC 同款 settings.html）里的 setUIOptions / setShortcuts / setUrlRewrites /
     * setFpkApi / iptv 配置都落到这里——键名与 PC settings.json 完全一致，
     * 因此 PC 端导出的设置文件可以直接拷进平板。
     */
    fun write(ctx: Context, o: JSONObject) {
        try {
            file(ctx).writeText(o.toString(2))
        } catch (e: Exception) {
            Log2.w(ctx, "settings: write failed ${e.message}")
        }
    }

    /**
     * 把 patch 里的键合并进 settings.json（值为 JSONObject.NULL 时删除该键）。
     * 与 PC `saveSettings({...})` 的语义一致：只动传进来的键，其余原样保留。
     */
    fun merge(ctx: Context, patch: JSONObject) {
        try {
            val o = raw(ctx)
            val keys = patch.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = patch.opt(k)
                if (v == null || v === JSONObject.NULL) o.remove(k) else o.put(k, v)
            }
            write(ctx, o)
        } catch (e: Exception) {
            Log2.w(ctx, "settings: merge failed ${e.message}")
        }
    }
}
