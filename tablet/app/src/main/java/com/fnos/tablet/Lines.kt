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

    fun appviewUrl(base: String, anchor: String): String =
        "$base/appview?anchor=" + android.net.Uri.encode(anchor)
}
