package com.fnos.tablet

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.io.File

/**
 * 把 PC 端的自绘标题栏（fnos-desktop/titlebar-inject.js）原样注入平板 WebView。
 *
 * 思路与 PC 端一致：**同一份 JS、同一套皮肤 manifest、同一套设置字段**，只在下面这层
 * 用 Android 的 addJavascriptInterface 冒充 Electron 的 ipcRenderer。
 *
 * PC 端 titlebar-inject.js 的契约（本文件严格照抄，不改协议）：
 *   - 是个 CommonJS 模块：`module.exports = function (ctx) { ... ctx.ipcRenderer ... }`
 *   - 依赖全局 `require('fs')` / `require('path')` 读皮肤素材（转 data: URL）
 *   - 依赖 `process.argv` 里的 `--fnos-shell` / `--fnos-app-window` 决定走主窗还是应用窗分支
 *   - 通过 ipcRenderer.send / sendSync / invoke / on 收发下面这些通道
 *
 * 因此注入时先打一层 shim（window.fnosHost → ipcRenderer + module/require/process），
 * 再把 titlebar-inject.js 原文塞进同一个作用域里执行，PC 端代码零改动。
 */
object TitlebarInject {

    private const val ASSET = "titlebar-inject.js"
    private const val BRIDGE = "fnosHost"

    /** NAS 源站（用于 PC 端「第三方页面 / 自家页面」判定，等价主进程的 nasOrigin） */
    @Volatile
    var nasOrigin: String = ""

    @Volatile
    private var source: String? = null

    @Volatile
    private var bundleMain: String? = null

    @Volatile
    private var bundleApp: String? = null

    /** App 启动时调用：把 assets/titlebar-inject.js 读成字符串缓存 */
    fun warmUp(ctx: Context) {
        source(ctx)
    }

    private fun source(ctx: Context): String? {
        source?.let { return it.ifBlank { null } }
        return try {
            val s = ctx.applicationContext.assets.open(ASSET).bufferedReader().use { it.readText() }
            source = s
            Log2.w(ctx, "titlebar: asset loaded ${s.length} bytes")
            s
        } catch (e: Throwable) {
            Log2.w(ctx, "titlebar: asset load failed ${e.message}")
            null
        }
    }

    /** onPageFinished 里调用：注入 shim + PC 端标题栏脚本 */
    fun inject(web: WebView, ctx: Context, isMain: Boolean, onResult: ((Boolean) -> Unit)? = null) {
        val js = bundle(ctx, isMain)
        if (js == null) { onResult?.invoke(false); return }
        try {
            web.evaluateJavascript(js) { r ->
                val s = r ?: ""
                val ok = s.contains("ok") && !s.contains("err:")
                if (!ok) Log2.w(ctx, "titlebar: inject returned $s")
                onResult?.invoke(ok)
            }
        } catch (e: Throwable) {
            Log2.w(ctx, "titlebar: evaluateJavascript failed ${e.message}")
            onResult?.invoke(false)
        }
    }

    /** 注册 window.fnosHost（每个 WebView 一份，持有该窗口的宿主回调） */
    fun attach(web: WebView, ctx: Context, host: TitlebarHost, isMain: Boolean) {
        try {
            web.addJavascriptInterface(TitlebarBridge(ctx, host, isMain), BRIDGE)
        } catch (e: Throwable) {
            Log2.w(ctx, "titlebar: addJavascriptInterface failed ${e.message}")
        }
    }

    /** Kotlin → 页面事件（等价 ipcRenderer.on 那一侧），如 settings:titlebar-changed */
    fun emit(web: WebView?, channel: String, json: String?) {
        if (web == null) return
        try {
            val j = JSONObject.quote(json ?: "null")
            val c = JSONObject.quote(channel)
            web.evaluateJavascript("window.__fnosIpcEmit&&window.__fnosIpcEmit($c,$j)", null)
        } catch (_: Throwable) {}
    }

    /** 把最新标题栏/皮肤配置推给页面（等价主进程 settings:titlebar-changed 广播） */
    fun pushTitlebarSettings(web: WebView?, ctx: Context) {
        try {
            emit(web, "settings:titlebar-changed", titlebarJson(ctx).toString())
        } catch (_: Throwable) {}
    }

    // ---------------------------------------------------------------- bundle

    private fun bundle(ctx: Context, isMain: Boolean): String? {
        val cached = if (isMain) bundleMain else bundleApp
        if (cached != null) return cached
        val src = source(ctx) ?: return null
        val argv = if (isMain) "['electron','--fnos-shell']" else "['electron','--fnos-app-window']"
        val out = SHIM.replace("\u27E6ARGV\u27E7", argv).replace("\u27E6SRC\u27E7", src)
        if (isMain) bundleMain = out else bundleApp = out
        return out
    }

    // ------------------------------------------------------------ titlebar JSON

    private fun cornerNorm(v: String?): String {
        val n = (v ?: "").trim()
        return when (n) {
            "round-small", "round-large", "half-small", "half-large", "square" -> n
            "round" -> "round-large"
            "half" -> "half-large"
            else -> "round-large"
        }
    }

    /**
     * 与 PC 端 main.js `ipcMain.on('settings:get-titlebar')` **同构**的返回体。
     * 字段名、默认值、取值范围一律照抄，PC 端皮肤/设置档案可以直接拿过来用。
     * 额外把皮肤目录解析成设备上的真实绝对路径，titlebar-inject.js 的
     * `__path.join(skinDir, src)` + `__fs.readFileSync()` 会经桥读到文件内容
     * 并按 PC 端同样的方式转成 data:base64 URL。
     */
    fun titlebarJson(ctx: Context): JSONObject {
        val st = Settings.load(ctx)
        val raw = Settings.raw(ctx)
        val o = JSONObject()
        o.put("autoHide", raw.optBoolean("titleBarAutoHide", false))
        o.put("opacity", raw.optInt("titleBarOpacity", 96).coerceIn(0, 100))
        o.put("color", raw.optString("titleBarColor", "#101426"))
        o.put("material", if (raw.optString("titleBarMaterial", "solid") == "frosted") "frosted" else "solid")
        o.put("blur", raw.optInt("titleBarBlur", 20).coerceIn(0, 40))
        o.put("highlight", raw.optInt("titleBarHighlight", 55).coerceIn(0, 100))
        o.put("frostColor", raw.optString("titleBarFrostColor", "#ffffff"))
        o.put("frostAlpha", raw.optInt("titleBarFrostAlpha", 10).coerceIn(2, 35))
        val solidColor = raw.optString("titleBarSolidColor",
            raw.optString("titleBarColor", "#101426"))
        o.put("solidColor", solidColor)
        o.put("solidAlpha", raw.optInt("titleBarSolidAlpha", 100).coerceIn(90, 100))
        o.put("divider", raw.optBoolean("titleBarDivider", true))
        o.put("hideAccountBall", raw.optBoolean("hideAccountBall", false))
        val uiAlpha = if (raw.has("uiTransparentAlpha")) raw.optInt("uiTransparentAlpha", 62)
            else if (!raw.optBoolean("uiTransparent", true)) 0 else 62
        o.put("uiTransparentAlpha", uiAlpha.coerceIn(0, 100))
        o.put("uiTransparent", uiAlpha > 0)
        o.put("barCorner", cornerNorm(raw.optString("barCorner", "round-large")))
        o.put("winCorner", cornerNorm(raw.optString("winCorner", "round-large")))
        // v4.0.31 悬浮球外观（跟随标题栏 / 单独设置）
        val ballMode = if (raw.optString("ballAppearanceMode", "follow") == "custom") "custom" else "follow"
        val mat = if (raw.optString("titleBarMaterial", "solid") == "frosted") "frosted" else "solid"
        o.put("ballAppearanceMode", ballMode)
        o.put("ballMaterial", if (ballMode == "custom")
            (if (raw.optString("ballMaterial", "solid") == "frosted") "frosted" else "solid") else mat)
        o.put("ballSolidColor", if (ballMode == "custom")
            raw.optString("ballSolidColor", solidColor) else solidColor)
        o.put("ballSolidAlpha", raw.optInt("ballSolidAlpha", 100).coerceIn(40, 100))
        o.put("ballFrostColor", if (ballMode == "custom")
            raw.optString("ballFrostColor", "#FFFFFF") else raw.optString("titleBarFrostColor", "#FFFFFF"))
        o.put("ballFrostAlpha", raw.optInt("ballFrostAlpha", 14).coerceIn(2, 60))

        // 皮肤：manifest.json 原样下发（PC 端也是把 manifest 整体交给渲染端）
        var skinDir = ""
        var skin: JSONObject? = null
        val name = st.activeSkin.trim()
        if (name.isNotEmpty()) {
            try {
                val dir = File(Skins.skinsDir(ctx), name)
                val mf = File(dir, "manifest.json")
                if (dir.isDirectory && mf.isFile) {
                    val m = JSONObject(mf.readText())
                    if (m.optJSONObject("titlebar") != null) {
                        skin = m
                        skinDir = dir.absolutePath
                    }
                }
            } catch (_: Throwable) {}
        }
        o.put("skinDir", skinDir)
        o.put("skin", skin ?: JSONObject.NULL)
        return o
    }

    // ------------------------------------------------------------------ bridge

    /**
     * window.fnosHost —— 唯一被 addJavascriptInterface 暴露给页面的对象。
     * 方法名刻意保持短：call 对应 sendSync，notify 对应 send。
     */
    private class TitlebarBridge(
        private val ctx: Context,
        private val host: TitlebarHost,
        private val isMain: Boolean
    ) {
        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var logBudget = 300

        /** sendSync 的落点：同步返回 JSON 文本（页面会 JSON.parse） */
        @JavascriptInterface
        fun call(channel: String, payloadJson: String?): String? {
            return try {
                when (channel) {
                    // 始终注入（PC 端注释里也是：所有窗口 skip=false）
                    "titlebar:should-inject" -> JSONObject().apply {
                        put("skip", false)
                        put("reason", "")
                        put("nasOrigin", nasOrigin)
                        // 等价 PC：NAS 同源 且（appview 容器页 或 非主窗）才清理壳残件
                        put("chromeFix", !isMain)
                    }.toString()

                    "theme:sys-dark" -> if (isNight()) "1" else "0"
                    "theme:mode" -> "1"
                    "settings:get-titlebar" -> titlebarJson(ctx).toString()
                    "settings:get-keep-login" -> "true"
                    "ball:get-pos" -> readBallPos()
                    "fnos:zoom-get" -> readZoom(payloadJson)
                    "win-fs-state" -> if (host.tbIsFullscreen()) "1" else "0"
                    else -> "null"
                }
            } catch (e: Throwable) {
                Log2.w(ctx, "titlebar: call $channel failed ${e.message}")
                "null"
            }
        }

        /** send 的落点：fire-and-forget（窗口控制、埋点、位置记忆） */
        @JavascriptInterface
        fun notify(channel: String, payloadJson: String?) {
            try {
                when (channel) {
                    "window-close" -> ui { host.tbClose() }
                    "window-minimize" -> ui { host.tbMinimize() }
                    "window-maximize" -> ui { host.tbToggleMaximize() }
                    "app-popup-menu" -> ui { host.tbPopupMenu(payloadJson) }
                    "auth:back-to-connect" -> ui { host.tbBackToConnect() }
                    "ball:set-pos" -> saveBallPos(payloadJson)
                    "fnos:zoom-set" -> saveZoom(payloadJson)
                    // Android 不需要原生拖动：window-drag / win-drag-* 全部空实现
                    "window-drag", "win-drag-start", "win-drag-move", "win-drag-end",
                    "win-drag-arm", "win-drag-arm-end", "win-drag-cancel" -> {}
                    "tb-hit" -> {}
                    "fnos:media-log" -> {
                        if (logBudget > 0) {
                            logBudget--
                            Log2.w(ctx, "tb-js: " + (payloadJson ?: "").take(220))
                        }
                    }
                    else -> {}
                }
            } catch (e: Throwable) {
                Log2.w(ctx, "titlebar: notify $channel failed ${e.message}")
            }
        }

        /** 皮肤素材：是否存在于 app filesDir/skins 下（只放行这个目录） */
        @JavascriptInterface
        fun skinExists(path: String?): Boolean = resolveSkinFile(path)?.isFile == true

        @JavascriptInterface
        fun skinMtime(path: String?): Double =
            try { (resolveSkinFile(path)?.lastModified() ?: 0L).toDouble() } catch (_: Throwable) { 0.0 }

        /** 皮肤素材内容 → base64（页面自己拼 data:image/…;base64,） */
        @JavascriptInterface
        fun skinBase64(path: String?): String? {
            return try {
                val f = resolveSkinFile(path) ?: return null
                if (f.length() > 32L * 1024 * 1024) return null
                Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
            } catch (e: Throwable) {
                Log2.w(ctx, "titlebar: skinBase64 failed ${e.message}")
                null
            }
        }

        private fun resolveSkinFile(path: String?): File? {
            if (path.isNullOrBlank()) return null
            return try {
                val root = Skins.skinsDir(ctx).canonicalFile
                val f = File(path).canonicalFile
                if (!f.path.startsWith(root.path + File.separator)) return null
                if (!f.isFile) return null
                f
            } catch (_: Throwable) { null }
        }

        private fun isNight(): Boolean {
            return try {
                val m = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                m == Configuration.UI_MODE_NIGHT_YES
            } catch (_: Throwable) { false }
        }

        private fun prefs() = ctx.getSharedPreferences("titlebar", Context.MODE_PRIVATE)

        private fun readBallPos(): String {
            val p = prefs()
            val fx = p.getFloat("ball_fx", -1f)
            val fy = p.getFloat("ball_fy", -1f)
            if (fx < 0f || fy < 0f) return "null"
            return JSONObject().put("fx", fx.toDouble()).put("fy", fy.toDouble()).toString()
        }

        private fun saveBallPos(json: String?) {
            try {
                val o = JSONObject(json ?: return)
                if (!o.has("fx") || !o.has("fy")) return
                prefs().edit()
                    .putFloat("ball_fx", o.optDouble("fx", 1.0).toFloat().coerceIn(0f, 1f))
                    .putFloat("ball_fy", o.optDouble("fy", 0.5).toFloat().coerceIn(0f, 1f))
                    .apply()
            } catch (_: Throwable) {}
        }

        private fun readZoom(payloadJson: String?): String {
            val key = try { JSONObject(payloadJson ?: "null").optString("key", "") } catch (_: Throwable) { "" }
            if (key.isBlank()) return "1"
            return prefs().getFloat("zoom_$key", 1f).toString()
        }

        private fun saveZoom(payloadJson: String?) {
            try {
                val o = JSONObject(payloadJson ?: return)
                val key = o.optString("key", "")
                val f = o.optDouble("factor", 1.0)
                if (key.isBlank()) return
                prefs().edit().putFloat("zoom_$key", f.toFloat().coerceIn(0.5f, 3f)).apply()
            } catch (_: Throwable) {}
        }

        private fun ui(block: () -> Unit) {
            mainHandler.post { try { block() } catch (_: Throwable) {} }
        }
    }

    // -------------------------------------------------------------------- shim
    // 下面这段 JS 就是「Electron preload 的 Android 替身」：
    //   window.fnosHost（Java 桥）→ ipcRenderer（send/sendSync/invoke/on）
    //   require('fs'/'path'/'electron') / process.argv / Buffer / module.exports
    // 然后原样执行 titlebar-inject.js（⟦SRC⟧ 处替换）。
    private const val SHIM = """
(function(){
  try {
    if (window.__fnosTbAndroidInjected) return 'ok-skip';
    window.__fnosTbAndroidInjected = true;
    var H = window.fnosHost;
    if (!H) return 'err:no-bridge';

    // ---------------- ipcRenderer ----------------
    var __ls = {};
    var ipcRenderer = {
      __n: 0,
      send: function (ch, data) {
        try {
          if (ch === 'fnos:media-log') { if (ipcRenderer.__n > 250) return; ipcRenderer.__n++; }
          H.notify(String(ch), JSON.stringify(data === undefined ? null : data));
        } catch (e) {}
      },
      sendSync: function (ch, data) {
        try {
          var r = H.call(String(ch), JSON.stringify(data === undefined ? null : data));
          if (r === null || r === undefined || r === '') return undefined;
          return JSON.parse(r);
        } catch (e) { return undefined; }
      },
      invoke: function (ch, data) {
        return new Promise(function (res) {
          var v = null; try { v = ipcRenderer.sendSync(ch, data); } catch (e) {}
          res(v);
        });
      },
      on: function (ch, fn) { if (typeof fn !== 'function') return; (__ls[ch] = __ls[ch] || []).push(fn); },
      once: function (ch, fn) {
        var w = function (e, d) { try { ipcRenderer.removeListener(ch, w); } catch (x) {} try { fn(e, d); } catch (x) {} };
        ipcRenderer.on(ch, w);
      },
      removeListener: function (ch, fn) { var a = __ls[ch]; if (!a) return; var i = a.indexOf(fn); if (i >= 0) a.splice(i, 1); },
      removeAllListeners: function (ch) { if (ch) { delete __ls[ch]; } else { __ls = {}; } },
      listenerCount: function (ch) { return (__ls[ch] || []).length; }
    };
    window.__fnosIpc = ipcRenderer;
    // Kotlin 侧用 TitlebarInject.emit() 推事件（对应 ipcRenderer.on 的接收端）
    window.__fnosIpcEmit = function (ch, json) {
      try {
        var a = __ls[ch]; if (!a || !a.length) return;
        var d = null;
        try { d = (json === null || json === undefined) ? null : JSON.parse(json); } catch (e) { d = json; }
        for (var i = 0; i < a.length; i++) { try { a[i]({ channel: ch, sender: null }, d); } catch (e) {} }
      } catch (e) {}
    };
    // PC 端 preload 会挂 window.fnos.backToConnect（悬浮球「添加账号」用），有壳对象才补
    try {
      if (window.fnos && typeof window.fnos === 'object' && !window.fnos.backToConnect) {
        window.fnos.backToConnect = function () { try { H.notify('auth:back-to-connect', 'null'); } catch (e) {} };
      }
    } catch (e) {}

    // ---------------- Buffer / fs / path（皮肤素材走 data:URL，与 PC 一致） ----------------
    function __b64bytes(b64) {
      try {
        var bin = atob(String(b64)), n = bin.length, u = new Uint8Array(n);
        for (var i = 0; i < n; i++) u[i] = bin.charCodeAt(i);
        try { u.__b64 = String(b64); } catch (e) {}
        return u;
      } catch (e) { return new Uint8Array(0); }
    }
    var __fsShim = {
      existsSync: function (p) { try { return !!H.skinExists(String(p)); } catch (e) { return false; } },
      statSync: function (p) {
        var m = 0; try { m = Number(H.skinMtime(String(p))) || 0; } catch (e) {}
        return { mtimeMs: m, size: 0, isFile: function () { return true; }, isDirectory: function () { return false; } };
      },
      readFileSync: function (p) {
        var b = null; try { b = H.skinBase64(String(p)); } catch (e) {}
        return b ? __b64bytes(b) : new Uint8Array(0);
      },
      readdirSync: function () { return []; }
    };
    var Buffer = {
      from: function (x) {
        return {
          length: (x && x.length) || 0,
          toString: function () { try { return (x && x.__b64) ? String(x.__b64) : ''; } catch (e) { return ''; } }
        };
      }
    };
    var __pathShim = {
      sep: '/',
      join: function () {
        var parts = [];
        for (var i = 0; i < arguments.length; i++) {
          var s = arguments[i];
          if (s === null || s === undefined || s === '') continue;
          parts.push(String(s));
        }
        return parts.join('/').replace(/\/{2,}/g, '/');
      },
      basename: function (p) { var a = String(p).split('/'); return a[a.length - 1] || ''; },
      extname: function (p) { var b = __pathShim.basename(p); var i = b.lastIndexOf('.'); return i > 0 ? b.slice(i) : ''; },
      dirname: function (p) { var s = String(p); var i = s.lastIndexOf('/'); return i > 0 ? s.slice(0, i) : '/'; }
    };

    // ---------------- process / require / module ----------------
    var __argv = ⟦ARGV⟧;
    var process = {
      platform: 'win32',           // 要 Windows 的 —□✕ 三键；mac 会被 isMac 判定成原生红绿灯
      type: 'renderer',
      argv: __argv,
      execPath: 'fnos-tablet',
      versions: { node: '20.0.0', electron: '28.0.0' },
      env: {},
      cwd: function () { return '/'; },
      nextTick: function (f) { setTimeout(f, 0); },
      on: function () {}
    };
    var module = { exports: {} };
    var exports = module.exports;
    function require(name) {
      if (name === 'electron') return { ipcRenderer: ipcRenderer };
      if (name === 'fs') return __fsShim;
      if (name === 'path') return __pathShim;
      return {};
    }

    // ---------------- PC 端注入脚本原文 ----------------
    ⟦SRC⟧

    if (typeof module.exports !== 'function') return 'ok-noexport';
    try {
      module.exports({ ipcRenderer: ipcRenderer });
      window.__fnosTbAndroidMode = (__argv.indexOf('--fnos-app-window') >= 0) ? 'app' : 'main';
      return 'ok';
    } catch (e) {
      try { H.notify('fnos:media-log', JSON.stringify({ stage: 'android.inject.ex', err: String((e && e.message) || e).slice(0, 160) })); } catch (x) {}
      return 'err:' + String((e && e.message) || e).slice(0, 120);
    }
  } catch (e) {
    try { window.fnosHost && window.fnosHost.notify('fnos:media-log', JSON.stringify({ stage: 'android.wrap.ex', err: String((e && e.message) || e).slice(0, 160) })); } catch (x) {}
    return 'err:' + String((e && e.message) || e).slice(0, 120);
  }
})()
"""
}
