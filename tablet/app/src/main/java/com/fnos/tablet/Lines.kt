package com.fnos.tablet

/**
 * 与 PC 端一致的四种接入线路。PC 端逻辑：
 *  - lan  : http://<lan-ip>:5666
 *  - ipv6 : http://[<ipv6>]:5666
 *  - frp  : DDNS 或 FRP 映射地址
 *  - fnid : FNID 中继（https://<fnid>.fnos.com 之类）
 * 这里只负责把用户输入的地址规范化成 base URL；具体线路由用户在连接页选择。
 */
object Lines {
    fun normalize(input: String, line: String): String {
        var s = input.trim()
        if (s.isEmpty()) return ""
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = (if (line == "fnid") "https://" else "http://") + s
        }
        if (!s.contains(":")) {
            s = s.trimEnd('/') + ":5666"
        }
        return s.trimEnd('/')
    }

    /** 与 PC 端一致：桌面 = NAS 根路径（appview 由网页自己渲染） */
    fun desktopUrl(base: String): String = "$base/"

    /**
     * v5.1.34（用户 Bug A）：把 FPK 清单里的 `path` 拼成应用真实入口。
     *
     * 实测（GET http://<nas>:18080/api/apps）：
     *   name=trim.media display=影视 path=/v  →  http://<nas>:5666/v （真实影视页）
     *   name=trim.photos           path=/p  →  http://<nas>:5666/p  （真实相册页）
     * 而 `base + "/appview?anchor=<name>"` 在 NAS 上是 **404**，网页壳会落到
     * 「兼容模式 · 页面未能完整加载」——这条拼法是 Bug A 的根因。
     */
    fun appUrl(base: String, path: String): String {
        val b = base.trimEnd('/')
        val p = path.trim()
        return when {
            // "/" 不是应用入口（是 NAS 首页）；把它当「没有 path」，交给上层回退
            p.isEmpty() || p == "/" -> ""
            p.startsWith("http://") || p.startsWith("https://") -> p
            p.startsWith("/") -> b + p
            else -> "$b/$p"
        }
    }

    /** FPK 没给 path 时的回退：/app/<name>/（用户验收要求第 2 条） */
    fun appPathFallback(base: String, name: String): String =
        base.trimEnd('/') + "/app/" + android.net.Uri.encode(name) + "/"

    /**
     * 历史拼法，**只作最后兜底**（任何信息都拿不到时）。
     * 注意：NAS 上 /appview 返回 404，会进「兼容模式」，正常路径不应走到这里。
     */
    fun appviewUrl(base: String, anchor: String): String =
        base.trimEnd('/') + "/appview?anchor=" + android.net.Uri.encode(anchor)
}
