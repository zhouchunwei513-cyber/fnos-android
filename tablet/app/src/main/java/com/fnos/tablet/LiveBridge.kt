package com.fnos.tablet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors

/**
 * `window.fnosLive` 的 Android 实现（PC 端 live-preload.js 的等价物）。
 *
 * PC 侧：Electron contextBridge + ipcMain.handle（网络请求全部在主进程做，规避 CORS）。
 * 这里：addJavascriptInterface(LiveBridge) + LiveBridge.SHIM（把同名方法逐一映射）。
 *
 * 与 SettingsBridge 的差别：直播页的方法几乎都要联网（拉播放列表 / 线路探测），
 * 同步返回会把 WebView 的 JS 线程卡住（页面掉帧、按钮点不动），所以这里走
 * **异步回执**：JS 侧 `LiveBridge.call(id, method, argsJson)` 立刻返回，
 * 结果由后台线程 evalJs 回灌 `window.__fnosLiveResolve(id, json)`。
 *
 * 返回结构逐一对齐 PC 主进程：
 *   getContext       ← iptv:get-context       {baseUrl,line,favorites,cacheSeconds,proxyBase,proxyListening,defaultPlayer}
 *   fetchPlaylist    ← iptv:fetch-playlist    {baseUrl,channels[],epgUrl}
 *   setLine          ← iptv:set-line          {ok,line}
 *   probeLines       ← iptv:probe-lines       {at,base,results[],best}
 *   toggleFavorite   ← iptv:toggle-favorite   [favorites]
 *   getStatus        ← iptv:get-status        {ok,status:{port,listening}}
 *   catchupUrl       ← iptv:catchup-url       {ok,url}
 *   fetchEpg         ← iptv:fetch-epg         {ok:false,error}（平板暂不解析 XMLTV）
 *   recordStart/Stop/OpenFolder ← 平板暂不支持录制，返回 {ok:false,error}
 *   vlc*             ← 与 PC v1.25.0 之后的存根一致
 *   mpvEmbed         ← mpv:embed → **Android 原生 ExoPlayer**（LiveActivity 内嵌播放区）
 *   mpvEmbedRect     ← mpv:embed-rect → 原生播放器跟随播放区几何
 *   liveLog / diagLog → Log2（平板统一日志）
 */
class LiveBridge(private val host: LiveActivity) {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "live-bridge").apply { isDaemon = true }
    }

    // XTE 基地址缓存（与 PC 一致：5 分钟）
    private var xteCache = ""
    private var xteCacheAt = 0L
    private var probeCache: JSONObject? = null
    private var probeCacheAt = 0L

    @JavascriptInterface
    fun call(id: String, method: String, argsJson: String?): String {
        try {
            worker.execute {
                val out = try {
                    dispatch(method, argsJson)
                } catch (e: Throwable) {
                    Log2.w(host, "live: $method failed ${e.message}")
                    JSONObject().put("ok", false).put("error", e.message ?: e.toString())
                }
                host.evalJs(
                    "window.__fnosLiveResolve&&window.__fnosLiveResolve(" +
                        JSONObject.quote(id) + "," + JSONObject.quote(out.toString()) + ")"
                )
            }
        } catch (e: Throwable) {
            Log2.w(host, "live: dispatch failed ${e.message}")
        }
        return "1"
    }

    // ------------------------------------------------------------------ 分发

    private fun dispatch(method: String, argsJson: String?): JSONObject {
        val env = try { JSONObject(argsJson ?: "{}") } catch (_: Exception) { JSONObject() }
        val a = env.optJSONArray("a") ?: JSONArray()
        fun str(i: Int): String = if (a.isNull(i)) "" else a.optString(i, "")
        fun boolAt(i: Int): Boolean = try { a.optBoolean(i, false) } catch (_: Exception) { false }
        fun objAt(i: Int): JSONObject = a.optJSONObject(i) ?: JSONObject()

        return when (method) {
            // ---------------------------------------------------------- 上下文
            "getContext" -> getContext()

            // ---------------------------------------------------------- 频道列表
            "fetchPlaylist" -> fetchPlaylist(str(0))
            "setLine" -> setLine(str(0))
            "probeLines" -> probeLines(boolAt(0))
            "toggleFavorite" -> toggleFavorite(str(0))
            "getStatus" -> JSONObject()
                .put("ok", true)
                .put("status", JSONObject().put("port", 0).put("listening", false))

            // ---------------------------------------------------------- EPG / 回看 / 录制
            "fetchEpg" -> JSONObject()
                .put("ok", false)
                .put("error", "平板端暂不支持节目单（EPG）解析，请在 PC 端查看")
                .put("programmes", JSONArray())
                .put("channels", JSONObject())
            "catchupUrl" -> catchupUrl(objAt(0), a.optLong(1, 0L), a.optLong(2, 0L))
            "recordStart", "recordStop", "recordOpenFolder" -> JSONObject()
                .put("ok", false)
                .put("error", "平板端暂不支持录制")
            "recordList" -> JSONObject().put("ok", true).put("list", JSONArray())

            // ---------------------------------------------------------- 原生播放器（ExoPlayer）
            "mpvEmbed" -> {
                val o = objAt(0)
                val url = o.optString("url", "").trim()
                val title = o.optString("title", "")
                if (url.isEmpty()) {
                    JSONObject().put("ok", false).put("error", "缺少播放地址")
                } else {
                    main.post { host.startEmbed(url, title, o.optJSONObject("rectPad")) }
                    JSONObject().put("ok", true)
                }
            }
            "mpvEmbedRect" -> {
                val o = objAt(0)
                main.post { host.moveEmbed(o.optJSONObject("rectPad")) }
                JSONObject().put("ok", true)
            }
            "toggleFullscreen" -> {
                main.post { host.toggleFullscreen() }
                JSONObject().put("ok", true)
            }
            // 原生播放器是 Android View，永远压在 WebView 之上；竖屏抽屉展开时页面里的
            // 频道列表会被播放器盖住 → 由注入脚本通知这里临时把播放器让开（声音继续）。
            "padPlayerVisible" -> {
                val v = objAt(0).optBoolean("visible", true)
                main.post { host.setPlayerVisible(v) }
                JSONObject().put("ok", true)
            }

            // ---------------------------------------------------------- VLC 存根（与 PC 一致）
            "vlcStatus" -> JSONObject().put("ok", true).put("available", false).put("enabled", false)
            "vlcPlay" -> JSONObject().put("ok", false).put("fallback", true)
            "vlcRect", "vlcVisible", "vlcControl", "vlcSet", "vlcDestroy" ->
                JSONObject().put("ok", true)

            // ---------------------------------------------------------- 系统能力
            "openExternal" -> openExternal(str(0))
            "copy" -> copyText(str(0))
            "toast" -> { main.post { toast(str(0)) }; JSONObject().put("ok", true) }
            "clearCache" -> {
                main.post { host.clearWebCache() }
                JSONObject().put("ok", true)
            }

            // ---------------------------------------------------------- 日志
            "liveLog" -> {
                Log2.w(host, "live: [${str(0)}] ${str(1)} ${if (a.length() > 2) a.opt(2).toString() else ""}")
                JSONObject().put("ok", true)
            }
            "diagLog" -> {
                Log2.w(host, "live: ${str(0)}")
                JSONObject().put("ok", true)
            }

            else -> JSONObject().put("ok", false).put("error", "未知方法：$method")
        }
    }

    // ------------------------------------------------------------------ 上下文 / 线路

    private fun getContext(): JSONObject {
        val raw = Settings.raw(host)
        val c = raw.optJSONObject("iptv") ?: JSONObject()
        val line = if (c.optString("iptvLine", "inner") in LIVE_LINES) c.optString("iptvLine", "inner") else "inner"
        return JSONObject()
            .put("baseUrl", xteBase())
            .put("line", line)
            .put("favorites", c.optJSONArray("iptvFavorites") ?: JSONArray())
            .put("cacheSeconds", 30)
            .put("proxyBase", JSONObject.NULL)
            .put("proxyListening", false)
            .put("defaultPlayer", "web")
    }

    private fun setLine(line: String): JSONObject {
        val ln = if (line in LIVE_LINES) line else "inner"
        val raw = Settings.raw(host)
        val c = raw.optJSONObject("iptv") ?: JSONObject()
        c.put("iptvLine", ln)
        Settings.merge(host, JSONObject().put("iptv", c))
        Log2.w(host, "live: setLine=$ln")
        return JSONObject().put("ok", true).put("line", ln)
    }

    private fun toggleFavorite(name: String): JSONObject {
        val raw = Settings.raw(host)
        val c = raw.optJSONObject("iptv") ?: JSONObject()
        val src = c.optJSONArray("iptvFavorites") ?: JSONArray()
        val set = LinkedHashSet<String>()
        for (i in 0 until src.length()) src.optString(i, "").takeIf { it.isNotEmpty() }?.let { set.add(it) }
        if (name.isNotEmpty()) {
            if (!set.remove(name)) set.add(name)
        }
        val out = JSONArray()
        set.forEach { out.put(it) }
        c.put("iptvFavorites", out)
        Settings.merge(host, JSONObject().put("iptv", c))
        Log2.w(host, "live: favorites=${out.length()}")
        return JSONObject().put("ok", true).put("favorites", out)
    }

    /**
     * 拉取某条线路的 M3U（PC 在 main.js `fetchIptvPlaylist` 里做同一件事）。
     * URL 形如 `<xteBase>/m3u/<line>.m3u8`。
     */
    private fun fetchPlaylist(line: String): JSONObject {
        val base = xteBase()
        if (base.isEmpty()) throw IllegalStateException("未配置 NAS 地址，请先连接服务器")
        val which = if (line in PT_LINES) line else "inner"
        val url = "$base/m3u/$which.m3u8"
        val text = httpGet(url, 15000)
        val chs = M3u.parse(text, base)
        val arr = JSONArray()
        for (c in chs) {
            arr.put(
                JSONObject()
                    .put("name", c.name)
                    .put("group", c.group)
                    .put("logo", c.logo)
                    .put("url", c.url)
                    .put("tvgId", c.tvgId)
                    .put("catchup", c.catchup)
                    .put("catchupSource", c.catchupSource)
            )
        }
        Log2.w(host, "live: playlist line=$which channels=${arr.length()} base=$base")
        return JSONObject().put("baseUrl", base).put("channels", arr).put("epgUrl", "")
    }

    /** 三线路连通性探测（PC `iptv:probe-lines` 同口径：HTTP 2xx/3xx + 以 #EXTM3U 开头） */
    private fun probeLines(force: Boolean): JSONObject {
        val now = System.currentTimeMillis()
        val cached = probeCache
        if (!force && cached != null && now - probeCacheAt < 5 * 60 * 1000) return cached
        val base = xteBase()
        val results = JSONArray()
        var best: String? = null
        for (line in PT_LINES) {
            val o = JSONObject().put("line", line)
            if (base.isEmpty()) {
                o.put("ok", false).put("latencyMs", -1).put("error", "未配置 NAS 地址")
            } else {
                val t0 = System.currentTimeMillis()
                try {
                    val head = httpGet("$base/m3u/$line.m3u8", 3000).trimStart()
                    val ok = head.startsWith("#EXTM3U")
                    o.put("ok", ok).put("latencyMs", (System.currentTimeMillis() - t0).toInt())
                    if (!ok) o.put("error", "非 M3U 响应")
                    if (ok && best == null) best = line
                } catch (e: Exception) {
                    o.put("ok", false).put("latencyMs", -1).put("error", e.message ?: "error")
                }
            }
            results.put(o)
        }
        val out = JSONObject()
            .put("at", now)
            .put("base", base)
            .put("results", results)
            .put("best", best ?: JSONObject.NULL)
        probeCache = out
        probeCacheAt = now
        Log2.w(host, "live: probe best=${best ?: "none"} base=$base")
        return out
    }

    /**
     * 直播基地址（XTE 服务所在 origin）。
     * 与 PC `resolveIptvBaseAsync` 同规则：① 用户在偏好设置里填了「直播基地址」就直接用；
     * ② 否则在 NAS host 上依次探测候选端口（PC 的 XTE_PORT_CANDIDATES），第一条可用即采用；
     * ③ 全失败兜底返回 NAS origin，让上层报 404 并提示排查。
     */
    private fun xteBase(): String {
        userXteBase().takeIf { it.isNotEmpty() }?.let { return it }
        val now = System.currentTimeMillis()
        if (xteCache.isNotEmpty() && now - xteCacheAt < 5 * 60 * 1000) return xteCache
        val nas = nasBase()
        if (nas.isEmpty()) return ""
        val uri = try { URI(nas) } catch (_: Exception) { null }
        val hostName = uri?.host ?: ""
        val scheme = if (nas.startsWith("https://")) "https" else "http"
        if (hostName.isEmpty()) return nas
        for (port in XTE_PORTS) {
            val origin = "$scheme://$hostName:$port"
            if (probeHealth(origin)) {
                xteCache = origin
                xteCacheAt = now
                Log2.w(host, "live: xte base=$origin")
                return origin
            }
        }
        xteCache = nas
        xteCacheAt = now
        Log2.w(host, "live: xte base fallback=$nas")
        return nas
    }

    /**
     * 供原生播放器做「地址纠正」：当前探测到可用的直播 origin（形如 http://host:port）。
     * 背景：XTE 的播放清单里写死了它自己的内网地址（如 http://192.168.31.101:34500/play/…，
     * 分片也是绝对地址 /proxy/ts），平板常常是走 Tailscale（100.x）连 NAS 的，
     * 内网地址在平板上不可达 → 播放器一直 buffering。播放前取一次这里的结果即可。
     * 解析不到返回空串（此时不改写）。
     */
    internal fun playOrigin(): String = try { xteBase() } catch (_: Exception) { "" }

    private fun userXteBase(): String {
        val raw = Settings.raw(host)
        val iptv = raw.optJSONObject("iptv") ?: return ""
        return normalizeBase(iptv.optString("iptvBaseUrl", ""))
    }

    private fun normalizeBase(raw: String): String {
        var v = raw.trim()
        if (v.isEmpty()) return ""
        v = v.trimEnd('/')
        if (!v.startsWith("http://", true) && !v.startsWith("https://", true)) v = "http://$v"
        return try { URI(v).let { u -> "${u.scheme}://${u.authority}" } } catch (_: Exception) { "" }
    }

    private fun nasBase(): String {
        val fromIntent = host.intent?.getStringExtra("base").orEmpty().trim()
        if (fromIntent.isNotEmpty()) return fromIntent.trimEnd('/')
        val raw = Settings.raw(host)
        val origin = raw.optString("origin", "").trim()
        if (origin.isNotEmpty()) return origin.trimEnd('/')
        val st = Settings.load(host)
        return try { Lines.normalize(st.server, st.line) } catch (_: Exception) { st.server }
    }

    private fun probeHealth(origin: String): Boolean {
        return try {
            val conn = URL("$origin/health").openConnection() as HttpURLConnection
            conn.connectTimeout = 1200
            conn.readTimeout = 1200
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "*/*")
            val code = conn.responseCode
            try { conn.inputStream.close() } catch (_: Exception) {}
            conn.disconnect()
            code in 200..499
        } catch (_: Exception) { false }
    }

    // ------------------------------------------------------------------ 回看 / 系统能力

    /** PC `buildCatchupUrl` 的同款实现（catchup-source 模板 + 默认 utc/lutc 追加） */
    private fun catchupUrl(ch: JSONObject, startMs: Long, stopMs: Long): JSONObject {
        val start = java.util.Date(startMs)
        val utcFmt = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val utc = utcFmt.format(start) + " Z"
        val lutc = (startMs / 1000).toString()
        val duration = Math.max(1, Math.round((stopMs - startMs) / 1000.0)).toString()
        var tpl = ch.optString("catchupSource", "").trim()
        var base = ch.optString("url", "")
        if (tpl.isEmpty()) {
            if (base.isNotEmpty()) {
                base = try {
                    val u = URI(base)
                    URI(u.scheme, u.authority, u.path, null, null).toString()
                } catch (_: Exception) { base }
            }
            val sep = if (base.contains("?")) "&" else "?"
            tpl = base + sep + "utc={utc}&lutc={lutc}"
            base = ""
        }
        val out = tpl
            .replace("{utc}", Uri.encode(utc), ignoreCase = true)
            .replace("{lutc}", lutc, ignoreCase = true)
            .replace("{start}", lutc, ignoreCase = true)
            .replace("{end}", (stopMs / 1000).toString(), ignoreCase = true)
            .replace("{duration}", duration, ignoreCase = true)
        return JSONObject().put("ok", true).put("url", out)
    }

    private fun openExternal(url: String): JSONObject {
        if (url.isEmpty()) return JSONObject().put("ok", false).put("error", "空地址")
        return try {
            main.post {
                try {
                    val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    host.startActivity(i)
                } catch (e: Exception) {
                    toast("打不开：${e.message}")
                }
            }
            JSONObject().put("ok", true)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "")
        }
    }

    private fun copyText(text: String): JSONObject {
        return try {
            main.post {
                try {
                    val cm = host.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("FNOS", text))
                    toast("已复制")
                } catch (_: Exception) {}
            }
            JSONObject().put("ok", true)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "")
        }
    }

    private fun toast(msg: String) {
        try { android.widget.Toast.makeText(host, msg, android.widget.Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ 网络

    private fun httpGet(url: String, timeoutMs: Int): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = minOf(timeoutMs, 8000)
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "*/*")
        conn.setInstanceFollowRedirects(true)
        try {
            val code = conn.responseCode
            if (code == 404) {
                throw IllegalStateException(
                    "HTTP 404：请检查 NAS 侧 XTE.fpk 是否已安装并启动（当前基地址 ${url.substringBefore("/m3u/")}）"
                )
            }
            if (code >= 400) throw IllegalStateException("HTTP $code")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    companion object {
        const val IFACE = "LiveBridge"

        private const val UA = "Mozilla/5.0 FNOS-Tablet/1.0"

        /** inner / ipv6 / frp（PC `iptv:set-line` 的白名单） */
        private val LIVE_LINES = listOf("inner", "ipv6", "frp")

        /** 播放列表允许的线路（PC `fetchIptvPlaylist` 还接受 all） */
        private val PT_LINES = listOf("inner", "ipv6", "frp", "all")

        /** PC `XTE_PORT_CANDIDATES`：34500 是 XTE 默认端口，其余是历史/备用 */
        private val XTE_PORTS = listOf(34500, 5666, 8340, 8080, 80, 5000)

        /**
         * Electron live-preload.js 的 Android 替身 —— 必须在 live.html 的
         * `const api = window.fnosLive` 之前执行（document-start），否则 api 为 undefined。
         *
         * 协议：Promise 方法 → LiveBridge.call(id, method, args) → 后台线程 →
         * `window.__fnosLiveResolve(id, json)`。Kotlin 回 `{ok:false,error}` 时 reject，
         * 让页面自己的 catch 分支显示友好提示（与 PC ipcRenderer.invoke 抛错行为一致）。
         */
        val SHIM: String = """
(function () {
  try {
    if (window.__fnosLiveShim) return;
    window.__fnosLiveShim = true;
    if (typeof LiveBridge === 'undefined') return;
    var waiting = {};
    var seq = 0;
    window.__fnosLiveResolve = function (id, json) {
      var w = waiting[id];
      if (!w) return;
      delete waiting[id];
      var v = null;
      try { v = (typeof json === 'string') ? JSON.parse(json) : json; } catch (e) { v = null; }
      if (v && v.ok === false && v.error) { try { w.reject(new Error(String(v.error))); } catch (e) {} return; }
      try { w.resolve(v); } catch (e) {}
    };
    function invoke(m, args) {
      return new Promise(function (resolve, reject) {
        var id = 'L' + (++seq);
        waiting[id] = { resolve: resolve, reject: reject };
        try {
          LiveBridge.call(id, String(m), JSON.stringify({ a: args || [] }));
        } catch (e) {
          delete waiting[id];
          reject(new Error('桥调用异常：' + String((e && e.message) || e)));
          return;
        }
        setTimeout(function () {
          if (waiting[id]) { delete waiting[id]; resolve(null); }
        }, 30000);
      });
    }
    function M(m) { return function () { return invoke(m, Array.prototype.slice.call(arguments)); }; }
    var events = {};
    function on(name) {
      return function (cb) {
        try {
          var h = function (v) { try { cb && cb(v); } catch (e) {} };
          (events[name] = events[name] || []).push(h);
          return function () {
            var l = events[name] || [];
            var i = l.indexOf(h);
            if (i >= 0) l.splice(i, 1);
          };
        } catch (e) { return function () {}; }
      };
    }
    window.__fnosLiveEmit = function (name, v) {
      try { (events[name] || []).forEach(function (f) { f(v); }); } catch (e) {}
    };
    var live = {
      platform: 'android',
      getContext: M('getContext'),
      fetchPlaylist: M('fetchPlaylist'),
      setLine: M('setLine'),
      probeLines: M('probeLines'),
      toggleFavorite: M('toggleFavorite'),
      getStatus: M('getStatus'),
      fetchEpg: M('fetchEpg'),
      catchupUrl: M('catchupUrl'),
      recordStart: M('recordStart'),
      recordStop: M('recordStop'),
      recordList: M('recordList'),
      recordOpenFolder: M('recordOpenFolder'),
      clearCache: M('clearCache'),
      mpvEmbed: M('mpvEmbed'),
      mpvEmbedRect: M('mpvEmbedRect'),
      mpvPlay: M('mpvEmbed'),
      toggleFullscreen: M('toggleFullscreen'),
      padPlayerVisible: M('padPlayerVisible'),
      liveLog: M('liveLog'),
      diagLog: M('diagLog'),
      vlcStatus: M('vlcStatus'),
      vlcPlay: M('vlcPlay'),
      vlcRect: M('vlcRect'),
      vlcVisible: M('vlcVisible'),
      vlcControl: M('vlcControl'),
      vlcSet: M('vlcSet'),
      vlcDestroy: M('vlcDestroy'),
      openExternal: M('openExternal'),
      copy: M('copy'),
      toast: M('toast'),
      onNetworkChanged: on('networkChanged'),
      onPlay: on('play'),
      onRecordingState: on('recordingState'),
      onRecordingProgress: on('recordingProgress'),
      onVlcEvent: on('vlcEvent'),
      onMpvEmbedClosed: on('mpvEmbedClosed')
    };
    try {
      live = new Proxy(live, {
        get: function (t, k) {
          if (typeof k !== 'string') return t[k];
          if (k in t) return t[k];
          return function () { return Promise.resolve(null); };
        }
      });
    } catch (e) {}
    window.fnosLive = live;
    window.api = live;
  } catch (e) {}
})();
        """.trimIndent()
    }
}
