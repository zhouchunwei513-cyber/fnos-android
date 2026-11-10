package com.fnos.tablet

import android.content.Context
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 应用清单（桌面快捷方式 / 创建快捷方式面板的数据源）。
 *
 * v5.1.32：**强制以 FPK 图标管理器为准**（用户要求，与 PC 端一致）。
 *   GET http://<nas>:18080/api/apps
 *     → {"apps":[{name, display_name, title, path, port, protocol, source, version,
 *                 has_custom_icon, has_default_icon, icons:{256:true,64:true}, ...}]}
 *   图标：GET http://<nas>:18080/api/icons/<name>/256   （实测 200 image/png）
 *   这个接口是从管理页 http://<nas>:18080/ 的前端 JS 里扒出来的：
 *     /api/icons、/api/icons/{appname}/{size}、/api/apps/{name}/icon
 *
 * 回退链路（界面会注明来源）：
 *   ① FPK（默认；端口取 settings.fpkApi.port，缺省 18080，host 缺省取当前 NAS 主机）
 *   ② app-center  REST（GET {origin}/app-center/v1/app/installed）—— 老路径
 *   ③ 内置清单 COMMON（保证离线也有常用应用可钉）
 */
object Apps {

    data class Entry(
        val id: String,
        val name: String,
        val anchor: String,
        val url: String = "",
        /** FPK 图标地址（/api/icons/<name>/256）；空=没有（用应用自身图标兜底） */
        val iconUrl: String = "",
        /** fpk / app-center / builtin */
        val source: String = ""
    )

    /** 顺序即快捷方式顺序；anchor 为 fnOS 应用标识（appId） */
    val COMMON: List<Entry> = listOf(
        Entry("appcenter", "应用中心", "trim.app-center", source = "builtin"),
        Entry("filemanager", "文件管理", "trim.file-manager", source = "builtin"),
        Entry("media", "影视", "trim.media", source = "builtin"),
        Entry("photos", "相册", "trim.photos", source = "builtin"),
        Entry("music", "音乐", "trim.music", source = "builtin"),
        Entry("download", "下载", "trim.downloads", source = "builtin"),
        Entry("docker", "Docker", "trim.docker", source = "builtin"),
        Entry("vm", "虚拟机", "trim.vm", source = "builtin"),
        Entry("harness", "DeepSeek Harness", "trim.deepseek-harness", source = "builtin"),
        Entry("xte", "XTE", "trim.xte", source = "builtin")
    )

    private val pool = Executors.newSingleThreadExecutor()

    /** 上次成功拉取时间：设置页 10s 轮询不会反复打 NAS */
    @Volatile private var lastRefreshAt = 0L

    /** 当前生效的清单来源（fpk / app-center / builtin），给界面标注用 */
    fun sourceOf(ctx: Context): String =
        Settings.raw(ctx).optString("appsSource", "").ifBlank { "builtin" }

    /** 内置清单 + 已缓存的真实清单（同 id 或同名时用缓存覆盖 anchor/url/icon） */
    fun all(ctx: Context): List<Entry> {
        val cache = cached(ctx)
        if (cache.isEmpty()) return COMMON
        val byName = cache.associateBy { it.name.lowercase() }
        val byId = cache.associateBy { it.id.lowercase() }
        val byAnchor = cache.associateBy { it.anchor.lowercase() }
        val out = ArrayList<Entry>()
        val used = HashSet<String>()
        for (c in COMMON) {
            val hit = byId[c.id.lowercase()] ?: byName[c.name.lowercase()] ?: byAnchor[c.anchor.lowercase()]
            if (hit != null) {
                out.add(hit); used.add(hit.id.lowercase())
            } else out.add(c)
        }
        for (c in cache) {
            if (out.size >= 24) break
            if (used.contains(c.id.lowercase())) continue
            out.add(c); used.add(c.id.lowercase())
        }
        return out
    }

    fun find(ctx: Context, id: String): Entry? = all(ctx).firstOrNull { it.id == id }

    fun cached(ctx: Context): List<Entry> {
        val a = Settings.raw(ctx).optJSONArray("apps") ?: return emptyList()
        val out = ArrayList<Entry>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val id = o.optString("appId", o.optString("id", ""))
            val name = o.optString("name", o.optString("appName", id))
            if (id.isBlank() && name.isBlank()) continue
            out.add(
                Entry(
                    id = id.ifBlank { name },
                    name = name.ifBlank { id },
                    anchor = o.optString("anchor", o.optString("appId", id)),
                    url = o.optString("url", ""),
                    iconUrl = o.optString("iconUrl", ""),
                    source = o.optString("source", "")
                )
            )
        }
        return out
    }

    // ------------------------------------------------------------------ 刷新

    /**
     * 拉一次清单：FPK → app-center → 保留旧缓存。
     * best-effort，失败静默（只写日志），界面靠 sourceOf() 标注来源。
     */
    fun refresh(ctx: Context, base: String) {
        if (base.isBlank()) return
        pool.execute {
            val origin = base.trimEnd('/')
            val fpk = tryRefreshFpk(ctx, origin)
            if (fpk > 0) {
                Log2.w(ctx, "apps: refreshed $fpk from fpk")
                return@execute
            }
            val n = tryRefreshAppCenter(ctx, origin)
            Log2.w(ctx, "apps: fpk miss, app-center=$n")
        }
    }

    /** 后台等待一次刷新（设置页打开前用，最多 3.5s） */
    fun refreshBlocking(ctx: Context, base: String, timeoutMs: Long = 3500) {
        if (base.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - lastRefreshAt < 60_000L && cached(ctx).isNotEmpty()) return
        lastRefreshAt = now
        val t = Thread {
            val origin = base.trimEnd('/')
            if (tryRefreshFpk(ctx, origin) <= 0) tryRefreshAppCenter(ctx, origin)
        }
        t.isDaemon = true
        t.start()
        try { t.join(timeoutMs) } catch (_: InterruptedException) {}
    }

    /** FPK 地址：settings.fpkApi.host/port，缺省取当前 NAS 主机 + 18080 */
    fun fpkBase(ctx: Context, nasBase: String): String {
        val fpk = Settings.raw(ctx).optJSONObject("fpkApi") ?: JSONObject()
        val host = fpk.optString("host", "").trim().removePrefix("http://").removePrefix("https://")
            .substringBefore('/').substringBefore(':')
        val port = fpk.optInt("port", 0).let { if (it in 1..65535) it else 18080 }
        val h = host.ifBlank {
            try { URL(nasBase.trimEnd('/')).host } catch (_: Exception) { "" }
        }
        if (h.isBlank()) return ""
        return "http://$h:$port"
    }

    /** 返回解析到的应用数；0 = 不可用（没装 FPK / 端口不通） */
    private fun tryRefreshFpk(ctx: Context, origin: String): Int {
        val fb = fpkBase(ctx, origin)
        if (fb.isBlank()) return 0
        val text = httpGet("$fb/api/apps", "") ?: return 0
        return try {
            val arr = JSONObject(text).optJSONArray("apps") ?: return 0
            val out = JSONArray()
            val nasHost = try { URL(origin).host } catch (_: Exception) { "" }
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                val name = a.optString("name", "").trim()
                if (name.isBlank()) continue
                val label = a.optString("display_name", "")
                    .ifBlank { a.optString("title", name) }
                    .ifBlank { name }
                // 图标：/api/icons/<name>/256 是实测可用的真实接口（返回 image/png）
                val icon = "$fb/api/icons/${android.net.Uri.encode(name)}/256"
                val port = a.optString("port", "").trim()
                val path = a.optString("path", "").trim().ifBlank { "/" }
                // 只有「自身带端口」的第三方应用才预先算 URL；系统应用交给 anchor → appview
                val url = if (port.isNotBlank() && port != "0" && port != "5666" && nasHost.isNotBlank())
                    "http://$nasHost:$port$path" else ""
                out.put(
                    JSONObject().apply {
                        put("appId", name)
                        put("name", label)
                        put("anchor", name)
                        put("url", url)
                        put("iconUrl", icon)
                        put("source", "fpk")
                    }
                )
            }
            if (out.length() == 0) return 0
            save(ctx, out, "fpk")
            out.length()
        } catch (e: Exception) {
            Log2.w(ctx, "apps: fpk parse failed ${e.message}")
            0
        }
    }

    /** 老路径：app-center REST（FPK 不可用时） */
    private fun tryRefreshAppCenter(ctx: Context, origin: String): Int {
        return try {
            val url = "$origin/app-center/v1/app/installed?language=zh-CN"
            val cookie = try { CookieManager.getInstance().getCookie(origin) ?: "" } catch (_: Exception) { "" }
            val text = httpGet(url, cookie) ?: return 0
            val list = JSONObject(text).optJSONObject("data")?.optJSONArray("list") ?: return 0
            val out = JSONArray()
            for (i in 0 until list.length()) {
                val a = list.optJSONObject(i) ?: continue
                val appName = a.optString("appName", "")
                val name = a.optString("name", appName)
                if (appName.isBlank() || name.isBlank()) continue
                val svc = a.optJSONObject("appServiceInfo")
                out.put(
                    JSONObject().apply {
                        put("appId", appName)
                        put("name", name)
                        put("anchor", appName)
                        put("url", launchUrl(origin, svc))
                        put("iconUrl", "")
                        put("source", "app-center")
                    }
                )
            }
            if (out.length() == 0) return 0
            save(ctx, out, "app-center")
            out.length()
        } catch (e: Exception) {
            Log2.w(ctx, "apps: app-center failed ${e.message}")
            0
        }
    }

    private fun save(ctx: Context, arr: JSONArray, source: String) {
        try {
            val raw = Settings.raw(ctx)
            raw.put("apps", arr)
            raw.put("appsSource", source)
            java.io.File(ctx.filesDir, "settings.json").writeText(raw.toString(2))
        } catch (e: Exception) {
            Log2.w(ctx, "apps: save failed ${e.message}")
        }
    }

    /** 与 PC buildAppCenterUrl 同规则：跨 host 的 fullUrl 丢弃，改用当前 origin + 相对字段 */
    private fun launchUrl(origin: String, svc: JSONObject?): String {
        if (svc == null) return ""
        val full = svc.optString("fullUrl", "")
        val urls = svc.optJSONObject("urls")
        val hasRel = urls != null && (urls.has("path") || urls.has("port") || urls.has("host"))
        return try {
            val cu = URL(origin)
            if (full.startsWith("http") && (!hasRel || URL(full).host == cu.host)) return full
            val protocol = urls?.optString("protocol", "").orEmpty().ifBlank { cu.protocol }.trimEnd(':')
            val host = urls?.optString("host", "").orEmpty().ifBlank { cu.host }
            val port = urls?.optString("port", "").orEmpty().ifBlank { if (cu.port > 0) cu.port.toString() else "" }
            val path = urls?.optString("path", "").orEmpty().ifBlank { "/" }
            if (port.isEmpty()) "$protocol://$host$path" else "$protocol://$host:$port$path"
        } catch (_: Exception) { "" }
    }

    private fun httpGet(u: String, cookie: String): String? {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(u).openConnection() as HttpURLConnection).apply {
                connectTimeout = 2500; readTimeout = 3500
                setRequestProperty("User-Agent", NasUa.DESKTOP)
                if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
            }
            if (c.responseCode >= 400) null
            else c.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            try { c?.disconnect() } catch (_: Exception) {}
        }
    }
}
