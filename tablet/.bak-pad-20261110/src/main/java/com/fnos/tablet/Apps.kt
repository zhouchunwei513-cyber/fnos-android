package com.fnos.tablet

import android.content.Context
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 常见应用清单（桌面快捷方式的数据源）。
 *
 * PC 端「创建桌面快捷方式」的数据来自主页扫描 / app-center REST：
 *   GET {origin}/app-center/v1/app/installed?language=zh-CN  →  data.list[].appName / name / appServiceInfo
 * 这里同构：先用内置清单（保证离线也有 10 个常用应用可钉），登录后后台拉一次
 * app-center 清单，把真实 appName / 启动地址补进 settings.json 的 "apps"，下次启动即生效。
 */
object Apps {

    data class Entry(
        val id: String,
        val name: String,
        val anchor: String,
        val url: String = ""
    )

    /** 顺序即快捷方式顺序；anchor 为 fnOS 应用标识（appId） */
    val COMMON: List<Entry> = listOf(
        Entry("appcenter", "应用中心", "trim.app-center"),
        Entry("filemanager", "文件管理", "trim.file-manager"),
        Entry("media", "影视", "trim.media"),
        Entry("photos", "相册", "trim.photos"),
        Entry("music", "音乐", "trim.music"),
        Entry("download", "下载", "trim.downloads"),
        Entry("docker", "Docker", "trim.docker"),
        Entry("vm", "虚拟机", "trim.vm"),
        Entry("harness", "DeepSeek Harness", "trim.deepseek-harness"),
        Entry("xte", "XTE", "trim.xte")
    )

    private val pool = Executors.newSingleThreadExecutor()

    /** 内置清单 + 已缓存的真实清单（同 id 或同名时用缓存覆盖 anchor/url） */
    fun all(ctx: Context): List<Entry> {
        val cache = cached(ctx)
        if (cache.isEmpty()) return COMMON
        val byName = cache.associateBy { it.name.lowercase() }
        val byId = cache.associateBy { it.id.lowercase() }
        val out = ArrayList<Entry>()
        val usedIds = HashSet<String>()
        for (c in COMMON) {
            val hit = byId[c.id.lowercase()] ?: byName[c.name.lowercase()]
            if (hit != null) {
                out.add(hit); usedIds.add(hit.id.lowercase())
            } else out.add(c)
        }
        for (c in cache) {
            if (out.size >= 16) break
            if (usedIds.contains(c.id.lowercase())) continue
            out.add(c); usedIds.add(c.id.lowercase())
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
            out.add(Entry(id.ifBlank { name }, name.ifBlank { id }, o.optString("appId", id), o.optString("url", "")))
        }
        return out
    }

    /** 登录后拉一次 app-center 清单（best-effort，失败静默） */
    fun refresh(ctx: Context, base: String) {
        if (base.isBlank()) return
        pool.execute {
            try {
                val origin = base.trimEnd('/')
                val url = "$origin/app-center/v1/app/installed?language=zh-CN"
                val cookie = try { CookieManager.getInstance().getCookie(origin) ?: "" } catch (_: Exception) { "" }
                val text = httpGet(url, cookie) ?: return@execute
                val json = JSONObject(text)
                val list = json.optJSONObject("data")?.optJSONArray("list") ?: return@execute
                val out = JSONArray()
                for (i in 0 until list.length()) {
                    val a = list.optJSONObject(i) ?: continue
                    val appName = a.optString("appName", "")
                    val name = a.optString("name", appName)
                    if (appName.isBlank() || name.isBlank()) continue
                    val svc = a.optJSONObject("appServiceInfo")
                    val launch = launchUrl(origin, svc)
                    out.put(
                        JSONObject().apply {
                            put("appId", appName)
                            put("name", name)
                            put("anchor", appName)
                            put("url", launch)
                        }
                    )
                }
                if (out.length() == 0) return@execute
                val raw = Settings.raw(ctx)
                raw.put("apps", out)
                java.io.File(ctx.filesDir, "settings.json").writeText(raw.toString(2))
                Log2.w(ctx, "apps: refreshed ${out.length()} from app-center")
            } catch (e: Exception) {
                Log2.w(ctx, "apps: refresh failed ${e.message}")
            }
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
                connectTimeout = 6000; readTimeout = 8000
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
