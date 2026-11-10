package com.fnos.tablet

import java.net.URI

/** 极简 M3U 解析（与 PC 端 parseM3uChannels 对齐：支持 #EXTINF 分组/台标/名称） */
object M3u {
    data class Channel(val name: String, val group: String, val logo: String, val url: String)

    fun parse(text: String, baseUrl: String): List<Channel> {
        val out = ArrayList<Channel>()
        var pendingName = ""
        var pendingGroup = ""
        var pendingLogo = ""
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                // #EXTINF:-1 tvg-logo="..." group-title="央视",CCTV1
                val attrs = Regex("([a-zA-Z-]+)=\"([^\"]*)\"").findAll(line)
                var logo = ""; var group = ""
                for (m in attrs) {
                    when (m.groupValues[1].lowercase()) {
                        "tvg-logo" -> logo = m.groupValues[2]
                        "group-title" -> group = m.groupValues[2]
                    }
                }
                pendingLogo = logo
                pendingGroup = group
                pendingName = line.substringAfterLast(",", "").trim()
            } else if (!line.startsWith("#")) {
                val abs = absolutize(line, baseUrl)
                if (abs.isNotEmpty()) {
                    out.add(Channel(pendingName.ifBlank { "频道${out.size + 1}" }, pendingGroup, pendingLogo, abs))
                }
                pendingName = ""; pendingGroup = ""; pendingLogo = ""
            }
        }
        return out
    }

    private fun absolutize(u: String, baseUrl: String): String {
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        return try { URI(baseUrl).resolve(u).toString() } catch (_: Exception) { u }
    }

    /** XTE 直播基地址：把 <scheme>://host[:port] 换成 :34500 */
    fun xteBase(serverBase: String): String {
        return try {
            val uri = URI(serverBase)
            val scheme = uri.scheme ?: "http"
            val host = uri.host ?: return serverBase
            "$scheme://$host:34500"
        } catch (_: Exception) { serverBase }
    }
}
