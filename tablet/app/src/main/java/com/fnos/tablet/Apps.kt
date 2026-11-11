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
        val source: String = "",
        /**
         * v5.1.34（用户 Bug A）：FPK 清单里的 `path` —— **应用真实入口**。
         * 实测 trim.media → "/v"、trim.photos → "/p"、trim.snapshots → "/snapshot?ver=…"。
         * 快捷方式必须带上它，打开时拼 base + path；原来的 base + "/appview?anchor=" 是 404。
         */
        val path: String = "",
        /** FPK 清单里的 port：只有自带端口的第三方应用才非空（如 iconmgr=18080） */
        val port: String = "",
        /** FPK 清单里的分组：thirdparty / official / system（宫格分组用） */
        val group: String = ""
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
        // v5.1.33（用户第 3 项：FPK 25/38）：这里原来有一句 `if (out.size >= 24) break`
        // 是历史上为了"设置页别太长"加的截断——它把 FPK 的 38 个应用硬砍到 24 个，
        // 再算上「电视直播」正好 25，与日志 `installedApps n=25 source=fpk`、
        // 以及服务端 /api/apps 返回 38 条完全对得上。
        // 用户要求"全部应用可见"，故取消截断，FPK 有多少列多少。
        for (c in cache) {
            if (used.contains(c.id.lowercase())) continue
            out.add(c); used.add(c.id.lowercase())
        }
        return out
    }

    // ------------------------------------------------------------------ 常用应用

    /** 设为常用/置顶的应用 id 列表（settings.json 的 favApps） */
    fun favorites(ctx: Context): List<String> {
        val a = Settings.raw(ctx).optJSONArray("favApps") ?: return emptyList()
        val out = ArrayList<String>(a.length())
        for (i in 0 until a.length()) {
            val s = a.optString(i, "").trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    fun setFavorites(ctx: Context, ids: List<String>) {
        try {
            val arr = JSONArray()
            for (id in ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()) arr.put(id)
            Settings.merge(ctx, JSONObject().put("favApps", arr))
            Log2.w(ctx, "apps: favApps=${arr.length()}")
        } catch (e: Exception) {
            Log2.w(ctx, "apps: setFavorites failed ${e.message}")
        }
    }

    fun find(ctx: Context, id: String): Entry? = all(ctx).firstOrNull { it.id == id }

    // ------------------------------------------------------- 入口 URL（Bug A）

    /**
     * 飞牛工作台内置应用没有独立页面，但这几个在外壳里有「微应用」地址（实测 200 + 真页面）。
     * 从 fnOS 前端 bundle 的 `f1()` 内置应用表里扒出来的（url: /apps/download/、/apps/docker/）。
     */
    val SHELL_PAGES: Map<String, String> = mapOf(
        "trim.download-center" to "/apps/download/",
        "trim.docker" to "/apps/docker/",
        "trim.text-editor" to "/app/text-editor"
    )

    /**
     * 应用真实入口：FPK `path` 优先 → 外壳微应用地址 → 工作台内置应用落工作台 → /app/<name>/ 回退。
     * 自带端口的第三方应用直接用清单里算好的 url。
     */
    fun urlFor(base: String, e: Entry): String {
        if (e.url.startsWith("http")) return e.url
        val b = base.trimEnd('/')
        val p = e.path.trim()
        if (p.isNotEmpty() && p != "/") return Lines.appUrl(base, p)
        SHELL_PAGES[e.id]?.let { return b + it }
        // 文件管理 / 应用中心 / 系统设置 这类 INBUILT 组件：NAS 上没有可直连的页面
        // （/app/<name>/ 实测返回 `invalid token`），只能落到工作台。
        if (isShellApp(e)) return b + "/"
        return Lines.appPathFallback(base, e.name.ifBlank { e.id })
    }

    /** 工作台内置应用判定：系统/官方应用且 FPK 没给入口 path（注意分组存在 group，source 是 fpk） */
    internal fun isShellApp(e: Entry): Boolean {
        val g = (e.group.ifBlank { e.source }).trim().lowercase()
        val p = e.path.trim()
        return (p == "/" || p.isEmpty()) && (g == "system" || g == "official")
    }

    /** 只有 anchor / appId / 名称时（桌面点击、旧快捷方式）→ 从清单里找到那一条 */
    fun resolve(ctx: Context, anchor: String): Entry? {
        val a = anchor.trim()
        if (a.isEmpty()) return null
        return all(ctx).firstOrNull {
            it.anchor.equals(a, true) || it.id.equals(a, true) || it.name.equals(a, true)
        }
    }

    /**
     * 统一解析「要加载哪个地址」（Bug A）：
     *   ① 快捷方式带的 path（新钉的都有）      → base + path
     *   ② 清单缓存里该应用的 path              → base + path
     *   ③ 带端口的第三方应用 url               → 原样
     *   ④ 回退 /app/<name>/
     *   ⑤ 最后的兜底才是 /appview?anchor=（NAS 上 404，正常不应走到）
     *
     * urlHint 只接受「非 appview」的真实页面地址；`/appview?anchor=` 一律当作没有。
     */
    fun launchUrl(ctx: Context, base: String, anchor: String, pathHint: String = "", urlHint: String = ""): String {
        val hint = urlHint.trim()
        if (hint.startsWith("http") && hint.indexOf("/appview") < 0) return hint
        if (pathHint.isNotBlank()) {
            val p = Lines.appUrl(base, pathHint)
            if (p.isNotBlank()) return p
        }
        val e = resolve(ctx, anchor)
        if (e != null) {
            val u = urlFor(base, e)
            if (u.isNotBlank()) return u
        }
        if (anchor.isNotBlank()) return Lines.appPathFallback(base, anchor)
        if (base.isNotBlank()) return base.trimEnd('/') + "/"
        return ""
    }

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
                    source = o.optString("source", ""),
                    path = o.optString("path", ""),
                    port = o.optString("port", ""),
                    group = o.optString("group", "")
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
    fun refreshBlocking(ctx: Context, base: String, timeoutMs: Long = 3500, force: Boolean = false) {
        if (base.isBlank()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastRefreshAt < 60_000L && cached(ctx).isNotEmpty()) return
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

    /** 清单里「可用 path」的条数（path 非空且不是 "/"）—— 用来识别退化后端 */
    private fun countUsablePath(arr: JSONArray): Int {
        var n = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val p = o.optString("path", "").trim()
            if (p.isNotEmpty() && p != "/") n++
        }
        return n
    }

    /** 返回解析到的应用数；0 = 不可用（没装 FPK / 端口不通） */
    private fun tryRefreshFpk(ctx: Context, origin: String): Int {
        val fb = fpkBase(ctx, origin)
        if (fb.isBlank()) return 0
        // v5.1.34（用户 Bug A，真机实测）：这台 NAS 的 /api/apps 在**两个后端之间随机漂移**，
        // 交替返回两套数据（连续 12 次请求 6 : 6）：
        //   ① 38 条，带真实 path（trim.media=/v、trim.photos=/p）和自带端口（bililivetools=3000）
        //   ② 40 条，path 全是 "/"、port 全空，还多一条 name=app
        // 拿到 ② 就会把「影视」解析成 http://nas:5666/（NAS 首页），即用户看到的「点快捷方式回到首页」。
        // 对策：重试最多 4 次取到带 path 的那套；单条 path 缺失时用旧缓存里同名的 path 兜底；
        //      实在只有 ② 也照常保存（名字/图标可用，打开时回退 /app/<name>/），但不覆盖旧缓存的好 path。
        var best: JSONArray? = null
        var bestGood = -1
        for (attempt in 0 until 4) {
            val text = httpGet("$fb/api/apps", "") ?: continue
            val arr = try { JSONObject(text).optJSONArray("apps") } catch (_: Exception) { null } ?: continue
            val good = countUsablePath(arr)
            if (good > bestGood) { best = arr; bestGood = good }
            if (good >= 1) break
            try { Thread.sleep(250) } catch (_: InterruptedException) {}
        }
        val arr = best ?: return cached(ctx).size
        Log2.w(ctx, "apps: fpk 清单 n=${arr.length()} 可用path=$bestGood（第${if (bestGood >= 1) "1~4" else "4"}次取到）")
        return try {
            val old = cached(ctx).associateBy { it.id.lowercase() }
            val out = JSONArray()
            val nasHost = try { URL(origin).host } catch (_: Exception) { "" }
            var skipped = 0
            var noPath = 0
            val skippedNames = StringBuilder()
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                val name = a.optString("name", "").trim()
                if (name.isBlank()) {
                    // v5.1.33（用户第 3 项）：被跳过的条目要能说出原因，不再静默丢
                    skipped++
                    if (skippedNames.length < 400) skippedNames.append("[no-name]")
                    continue
                }
                val label = a.optString("display_name", "")
                    .ifBlank { a.optString("title", name) }
                    .ifBlank { name }
                // 图标：/api/icons/<name>/256 是实测可用的真实接口（返回 image/png）
                val icon = "$fb/api/icons/${android.net.Uri.encode(name)}/256"
                // v5.1.34（用户 Bug A）：path 原样保留；但 "/" 等于「没有入口」（退化后端），
                // 这种先从旧缓存里捞同名的真实 path，捞不到就存空 → 打开时回退 /app/<name>/。
                val prev = old[name.lowercase()]
                val rawPath = a.optString("path", "").trim()
                val path = if (rawPath.isNotEmpty() && rawPath != "/") rawPath else prev?.path.orEmpty()
                if (path.isBlank()) noPath++
                val rawPort = a.optString("port", "").trim()
                val port = if (rawPort.isNotEmpty() && rawPort != "0") rawPort else prev?.port.orEmpty()
                // 只有「自身带端口」的第三方应用才预先算 URL；其余交给 path / anchor 解析
                val url = if (port.isNotBlank() && port != "0" && port != "5666" && nasHost.isNotBlank())
                    "http://$nasHost:$port${path.ifBlank { "/" }}" else ""
                out.put(
                    JSONObject().apply {
                        put("appId", name)
                        put("name", label)
                        put("anchor", name)
                        put("url", url)
                        put("iconUrl", icon)
                        put("source", "fpk")
                        put("path", path)
                        put("port", port)
                        put("group", a.optString("source", "").trim())
                    }
                )
            }
            if (out.length() == 0) return 0
            save(ctx, out, "fpk")
            Log2.w(ctx, "apps: fpk total=${arr.length()} kept=${out.length()} skipped=$skipped noPath=$noPath ${skippedNames}")
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
