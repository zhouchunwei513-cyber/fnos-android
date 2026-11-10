package com.fnos.tablet

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 服务器地址解析 / 探测 —— 逐行对齐 PC 端 main.js：
 *   - `normalizeServer(input)`（v5.1.x，含 IPv6 补方括号、443/8443 按 https、默认端口 5666）
 *   - `__resolveServerScheme(input)`（裸地址先探测 http/https，明文打 HTTPS 端口 400 时自动改走 https）
 *   - `__probeOnce(u, timeout)`
 *
 * 目标不是「差不多」：登录页与 PC 是同一份 login.js，history 条目的字段（serverInput /
 * origin / href / label / isFnId / fnId / partition）必须与 PC 端 settings.json 同构，
 * 否则登录页的历史下拉、去重、删除都不能与 PC 行为一致。
 */
object ServerAddr {

    /** 与 PC 端 normalizeServer 的返回体一一对应 */
    data class Parsed(
        val serverInput: String,   // 用户原始输入（去空格）
        val origin: String,        // scheme://host[:port]
        val href: String,          // 完整地址（FN ID 时含路径）
        val isFnId: Boolean = false,
        val fnId: String = "",
        val label: String = ""     // 历史下拉的标题（PC 端 label 语义）
    )

    /** 与 PC 端 normalizeServer 完全同构；失败抛 IllegalArgumentException（文案也照抄） */
    fun normalize(input: String): Parsed {
        val raw = (input ?: "").trim()
        if (raw.isEmpty()) throw IllegalArgumentException("服务器地址为空")

        // 显式 scheme：原样保留
        if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(raw)) {
            val u = try { URL(raw) } catch (_: Exception) { throw IllegalArgumentException("服务器地址格式不正确") }
            return Parsed(raw, originOf(u), u.toString(), label = hostLabel(u))
        }

        // www.fnos.net/<fnid> 形式
        val fnIdFromPath = Regex("^(?:www\\.)?fnos\\.net/([A-Za-z0-9_-]+)/?$", RegexOption.IGNORE_CASE)
            .find(raw)
        if (fnIdFromPath != null) {
            val fnId = fnIdFromPath.groupValues[1]
            val href = "https://fnos.net/" + URLEncoder.encode(fnId, "UTF-8")
            return Parsed(raw, "https://fnos.net", href, isFnId = true, fnId = fnId, label = "FN ID: $fnId")
        }

        // 裸 FN ID（纯字母数字下划线，且不是纯数字）
        if (Regex("^[A-Za-z0-9_-]+$").matches(raw) && !Regex("^\\d+$").matches(raw)) {
            val fnId = raw.replace(Regex("^fn[-_]", RegexOption.IGNORE_CASE), "")
            val href = "https://fnos.net/" + URLEncoder.encode(fnId, "UTF-8")
            return Parsed(raw, "https://fnos.net", href, isFnId = true, fnId = fnId, label = "FN ID: $fnId")
        }

        // 裸地址：443/8443 这类 TLS 端口按 https 解析（PC v2.13.0 r33）
        val tlsHint = Regex(":(?:443|8443)(?:$|/)").containsMatchIn(raw)
        val scheme = if (tlsHint) "https" else "http"
        val direct: URL? = try { URL("$scheme://$raw") } catch (_: Exception) { null }
        val u: URL = direct ?: run {
            // IPv6 常常不带方括号（2409:8a00::1）→ 补方括号重试（PC v5.1.14 工单 9）
            val looksV6 = Regex("^[0-9a-fA-F:]+$").matches(raw) && raw.split(':').size >= 3 && !raw.startsWith("[")
            if (!looksV6) throw IllegalArgumentException("服务器地址格式不正确")
            try { URL("$scheme://[$raw]") } catch (_: Exception) {
                throw IllegalArgumentException("服务器地址格式不正确")
            }
        }
        // URL 会吞掉默认端口：仅「非 TLS 提示且确实无端口」时补 5666
        return if (u.port == -1 && !tlsHint) {
            val withPort = URL("${u.protocol}://${u.host}:5666${u.file}")
            Parsed(raw, originOf(withPort), withPort.toString(), label = u.host)
        } else {
            Parsed(raw, originOf(u), u.toString(), label = hostLabel(u))
        }
    }

    /** 等价 PC `__resolveServerScheme`：裸地址探测 http/https，返回可直接连接的字符串 */
    fun resolveScheme(input: String): String {
        val raw = (input ?: "").trim()
        if (raw.isEmpty()) return raw
        if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(raw)) return raw
        if (Regex("^(?:www\\.)?fnos\\.net/", RegexOption.IGNORE_CASE).containsMatchIn(raw)) return raw
        if (Regex("^[A-Za-z0-9_-]+$").matches(raw) && !Regex("^\\d+$").matches(raw)) return raw

        var hostPart = raw
        var pathPart = ""
        val slash = raw.indexOf('/')
        if (slash > 0) { hostPart = raw.substring(0, slash); pathPart = raw.substring(slash) }
        val m = Regex("^([^:]+):(\\d+)$").find(hostPart)
        val host = m?.groupValues?.get(1) ?: hostPart
        val port = m?.groupValues?.get(2) ?: ""

        val candidates = ArrayList<String>()
        if (port.isNotEmpty()) {
            val hint = if (port == "443" || port == "8443") "https" else "http"
            val other = if (hint == "https") "http" else "https"
            candidates.add("$hint://$host:$port$pathPart")
            candidates.add("$other://$host:$port$pathPart")
        } else {
            candidates.add("http://$host:5666$pathPart")
            candidates.add("https://$host$pathPart")
            candidates.add("https://$host:5666$pathPart")
        }
        var firstReachable = ""
        for (c in candidates) {
            val r = probe(c, 2000)
            if (r.first && r.second != 400) {
                AppCtx.ctx?.let { Log2.w(it, "server: scheme autoprobed -> $c") }
                return c
            }
            if (r.first && firstReachable.isEmpty()) firstReachable = c
        }
        return if (firstReachable.isNotEmpty()) firstReachable else raw
    }

    /** 等价 PC `__probeOnce`：短超时 GET，只看状态码（不跟随跳转内容） */
    fun probe(url: String, timeoutMs: Int = 2500): Pair<Boolean, Int> {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("User-Agent", NasUa.DESKTOP)
            }
            val code = c.responseCode
            try { c.inputStream?.close() } catch (_: Exception) {}
            Pair(true, code)
        } catch (_: Exception) {
            Pair(false, 0)
        } finally {
            try { c?.disconnect() } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------ 小工具

    private fun originOf(u: URL): String {
        val scheme = u.protocol.lowercase()
        val host = u.host
        val port = u.port
        val defaultPort = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
        return if (port == -1 || port == 0 || defaultPort) "$scheme://$host" else "$scheme://$host:$port"
    }

    private fun hostLabel(u: URL): String =
        if (u.port == -1 || u.port == 0) u.host else "${u.host}:${u.port}"
}
