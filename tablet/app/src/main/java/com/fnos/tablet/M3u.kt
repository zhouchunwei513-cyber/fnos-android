package com.fnos.tablet

import java.net.URI

/**
 * 极简 M3U 解析（与 PC 端 `parseM3uChannels` 对齐：支持 #EXTINF 分组/台标/名称，
 * 以及 v1.23.0 的 EPG/回看元数据 catchup / catchup-source / tvg-id）。
 *
 * 电视直播页（assets/web/live.html，PC 原版）直接读这里给出的
 * name / group / logo / url / tvgId / catchup / catchupSource。
 */
object M3u {
    data class Channel(
        val name: String,
        val group: String,
        val logo: String,
        val url: String,
        val tvgId: String = "",
        val catchup: String = "",
        val catchupSource: String = ""
    )

    fun parse(text: String, baseUrl: String): List<Channel> {
        val out = ArrayList<Channel>()
        var pendingName = ""
        var pendingGroup = ""
        var pendingLogo = ""
        var pendingId = ""
        var pendingCatchup = ""
        var pendingCatchupSource = ""

        fun reset() {
            pendingName = ""; pendingGroup = ""; pendingLogo = ""
            pendingId = ""; pendingCatchup = ""; pendingCatchupSource = ""
        }

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                // #EXTINF:-1 tvg-id="cctv1" tvg-logo="..." group-title="央视",CCTV1
                val attrs = Regex("([a-zA-Z0-9-]+)=\"([^\"]*)\"").findAll(line)
                for (m in attrs) {
                    val k = m.groupValues[1].lowercase()
                    val v = m.groupValues[2]
                    when (k) {
                        "tvg-logo" -> pendingLogo = v
                        "group-title" -> pendingGroup = v
                        "tvg-id" -> pendingId = v
                        "catchup" -> pendingCatchup = v
                        "catchup-source" -> pendingCatchupSource = v
                    }
                }
                pendingName = line.substringAfterLast(",", "").trim()
            } else if (!line.startsWith("#")) {
                val abs = absolutize(line, baseUrl)
                if (abs.isNotEmpty()) {
                    out.add(
                        Channel(
                            name = pendingName.ifBlank { "频道${out.size + 1}" },
                            group = pendingGroup.ifBlank { "未分组" },
                            logo = pendingLogo,
                            url = abs,
                            tvgId = pendingId,
                            catchup = pendingCatchup,
                            catchupSource = pendingCatchupSource
                        )
                    )
                }
                reset()
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
