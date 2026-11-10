package com.fnos.tablet

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * PC 端登录页（assets/web/login.html + login.css + login.js，与 PC 端仓库逐字节同一份）。
 *
 * login.js 只认 `window.fnos` 三个方法（签名从 login.js 反推）：
 *   const API = window.fnos;
 *   await API.connect(server)                  // 入参是字符串；只要 res.ok / res.error
 *   const data = await API.loadLastServer()    // { server, origin, history:[…] }
 *   const res  = await API.removeHistory(href) // { ok, history:[…] }
 * 都是 await 的，所以桥必须真返回 Promise。Electron 里这三条走 ipcRenderer.invoke，
 * 这里用「原生侧持有 requestId、算完再回 evaluateJavascript」的 RPC 等价实现：
 * JS 侧 window.fnos.* 返回 Promise，Kotlin 侧用 __fnosResolve(id, ok, json) 回调。
 */
class LoginScreen(
    private val act: AppCompatActivity,
    private val web: WebView,
    private val onConnected: (ServerAddr.Parsed) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()
    private val loader by lazy { WebAssets.loader(act) }

    @SuppressLint("SetJavaScriptEnabled")
    fun start() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            // 登录页是本地页面，但 NAS 跳转/探测需要真实网络
            userAgentString = NasUa.DESKTOP
            allowFileAccess = true
            allowContentAccess = true
            setSupportZoom(false)
        }
        web.setBackgroundColor(0xFF07090F.toInt())
        web.setLayerType(WebView.LAYER_TYPE_HARDWARE, null)
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?) =
                WebAssets.intercept(loader, request) ?: super.shouldInterceptRequest(view, request)

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url?.toString() ?: return false
                // 本地登录页内部不应发生跳转；万一发生，交给桌面逻辑判定
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log2.w(act, "login: page finished $url")
                // 老 WebView（无 DOCUMENT_START_SCRIPT）兜底：页面脚本执行前补桥
                if (!startScriptOk) {
                    view?.evaluateJavascript(SHIM, null)
                }
            }
        }
        web.addJavascriptInterface(Bridge(), "FnosLogin")

        // 与 PC 端 preload 时机对齐：桥必须在 login.js 之前就绪
        startScriptOk = try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(web, SHIM, setOf("*"))
                Log2.w(act, "login: bridge installed at document-start")
                true
            } else false
        } catch (e: Throwable) {
            Log2.w(act, "login: documentStart failed ${e.message}")
            false
        }
        if (!startScriptOk) {
            try { web.evaluateJavascript(SHIM, null) } catch (_: Throwable) {}
        }

        Log2.w(act, "login: load ${WebAssets.loginUrl()} (bridge=$startScriptOk)")
        web.loadUrl(WebAssets.loginUrl())
    }

    fun reload() {
        try { web.reload() } catch (_: Exception) {}
    }

    private var startScriptOk = false

    // ---------------------------------------------------------------- 原生桥

    private inner class Bridge {

        @JavascriptInterface
        fun call(method: String, argsJson: String?, reqId: String?) {
            val id = reqId ?: ""
            when (method) {
                // ---------------- auth:connect（校验可达 → 存 Settings → 打开桌面）
                "connect" -> {
                    val server = try {
                        JSONObject(argsJson ?: "{}").optString("server", "")
                    } catch (_: Exception) { "" }
                    if (server.isBlank()) {
                        respond(id, err("请输入服务器地址或 FN ID")); return
                    }
                    pool.execute {
                        try {
                            // ① 裸地址先探测 scheme（等价 PC __resolveServerScheme）
                            val resolved = ServerAddr.resolveScheme(server)
                            // ② 规范化（等价 PC normalizeServer）—— 格式错误直接回错误给页面
                            val parsed = ServerAddr.normalize(resolved)
                            // ③ 可达性校验：PC 端是把 URL load 进窗口，这里必须自己探一次，
                            //    否则「输入错地址」会静默切到桌面并显示错误页
                            val originOk = ServerAddr.probe(parsed.origin, 4000)
                            val hrefOk = if (parsed.href != parsed.origin) ServerAddr.probe(parsed.href, 4000) else originOk
                            if (!originOk.first && !hrefOk.first) {
                                Log2.w(act, "login: connect unreachable ${parsed.origin}")
                                respond(id, err("无法连接 $server，请检查地址与网络"))
                                return@execute
                            }
                            Log2.w(act, "login: connect ok ${parsed.href}")
                            main.post {
                                try {
                                    History.remember(act, parsed)
                                    // 与 PC 等同：line 由地址特征推断（https/fnid → fnid，其余 lan）
                                    val st = Settings.load(act)
                                    val line = when {
                                        parsed.isFnId -> "fnid"
                                        parsed.origin.startsWith("https") -> "frp"
                                        else -> "lan"
                                    }
                                    Settings.save(act, st.copy(server = parsed.serverInput, line = line))
                                    respond(id, JSONObject().put("ok", true).toString())
                                    onConnected(parsed)
                                } catch (e: Throwable) {
                                    respond(id, err(e.message ?: "连接失败"))
                                }
                            }
                        } catch (e: Throwable) {
                            respond(id, err(e.message ?: "连接失败，请检查地址"))
                        }
                    }
                }

                // ---------------- auth:load-history
                "loadLastServer" -> {
                    val o = JSONObject()
                    try {
                        o.put("server", History.lastServer(act))
                        o.put("origin", History.lastOrigin(act))
                        o.put("history", History.list(act))
                    } catch (_: Exception) {}
                    respond(id, o.toString())
                }

                // ---------------- auth:remove-history
                "removeHistory" -> {
                    val href = try {
                        JSONObject(argsJson ?: "{}").optString("href", "")
                    } catch (_: Exception) { "" }
                    if (href.isBlank()) {
                        respond(id, JSONObject().put("ok", false).toString())
                    } else {
                        val arr: JSONArray = History.remove(act, href)
                        respond(id, JSONObject().put("ok", true).put("history", arr).toString())
                    }
                }

                // ---------------- auth:back-to-connect
                "backToConnect" -> {
                    main.post { onBackToConnect?.invoke() }
                    respond(id, "true")
                }

                else -> respond(id, err("unknown method: $method"))
            }
        }
    }

    /** 悬浮球「添加账号」等场景由外部决定回登录页 */
    var onBackToConnect: (() -> Unit)? = null

    private fun err(msg: String): String = JSONObject().put("ok", false).put("error", msg).toString()

    /** 与 ipcRenderer.invoke 语义一致：业务结果一律 resolve（失败也在返回值里带 ok/error） */
    private fun respond(reqId: String, json: String) = resolve(reqId, true, json)

    private fun resolve(reqId: String, ok: Boolean, json: String) {
        if (reqId.isEmpty()) return
        val escaped = JSONObject.quote(json)
        val flag = if (ok) "true" else "false"
        main.post {
            try {
                web.evaluateJavascript(
                    "window.__fnosResolve&&window.__fnosResolve(${JSONObject.quote(reqId)},$flag,$escaped)",
                    null
                )
            } catch (_: Throwable) {}
        }
    }

    companion object {
        /**
         * Electron preload 的 Android 替身：只暴露 login.js 实际用到的能力。
         * platform/version 与 PC 端 preload 暴露的值保持一致（页面若有判定不会走岔）。
         */
        private val SHIM = """
(function(){
  try {
    if (window.__fnosLoginBridge) return;
    window.__fnosLoginBridge = true;
    var seq = 0, pend = {};
    window.__fnosResolve = function (id, ok, json) {
      var p = pend[id];
      if (!p) return;
      delete pend[id];
      var v = null;
      try { v = (json === null || json === undefined || json === '') ? null : JSON.parse(json); }
      catch (e) { v = json; }
      if (ok) { try { p.res(v); } catch (e) {} }
      else { try { p.rej(v); } catch (e) {} }
    };
    function rpc(m, a) {
      return new Promise(function (res, rej) {
        var id = 'q' + (++seq);
        pend[id] = { res: res, rej: rej };
        try { FnosLogin.call(String(m), JSON.stringify(a || {}), id); }
        catch (e) { delete pend[id]; rej(e); }
      });
    }
    window.fnos = {
      platform: 'win32',
      version: '2.0.5',
      connect: function (server) { return rpc('connect', { server: server }); },
      loadLastServer: function () { return rpc('loadLastServer', {}); },
      removeHistory: function (href) { return rpc('removeHistory', { href: href }); },
      backToConnect: function () { return rpc('backToConnect', {}); }
    };
  } catch (e) {}
})();
        """.trimIndent()
    }
}
