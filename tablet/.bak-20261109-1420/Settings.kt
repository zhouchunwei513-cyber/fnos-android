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
        val lastUser: String = ""
    )

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun load(ctx: Context): State {
        return try {
            val f = file(ctx)
            if (!f.exists()) State() else {
                val o = JSONObject(f.readText())
                State(
                    server = o.optString("server", State().server),
                    line = o.optString("line", "lan"),
                    activeSkin = o.optString("activeSkin", ""),
                    lastUser = o.optString("lastUser", "")
                )
            }
        } catch (_: Exception) { State() }
    }

    fun save(ctx: Context, s: State) {
        try {
            val o = JSONObject()
            o.put("server", s.server)
            o.put("line", s.line)
            o.put("activeSkin", s.activeSkin)
            o.put("lastUser", s.lastUser)
            file(ctx).writeText(o.toString(2))
        } catch (_: Exception) {}
    }
}
