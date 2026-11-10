package com.fnos.tablet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 菜单动作的宿主（主窗口 / 应用窗各实现一份）。
 * 对应 PC 端 `__menuRegistry[id].click(item, target)` 里那个 target 窗口。
 */
interface MenuTarget {
    val act: AppCompatActivity
    fun menuWeb(): WebView?
    fun menuBase(): String

    /** 返回 FNOS 主页 */
    fun menuGoHome()

    /** 锁定 FNOS（PC：有启动密码时才出现；Android 映射为回到登录页并隐藏桌面） */
    fun menuLock()

    /** 一键隐藏 / 呼出（Android 无托盘 → moveTaskToBack） */
    fun menuHideAll()

    /** 最小化 → moveTaskToBack */
    fun menuMinimize()

    /** 隐藏到托盘（Android 无托盘 → moveTaskToBack + Toast） */
    fun menuHideToTray()

    fun menuCloseWindow()
    fun menuToggleFullscreen()

    /** 切换账号 → 直接连到该账号（历史里的 origin / href） */
    fun menuConnectTo(href: String)

    /** 登录其它账号… → 回到登录页 */
    fun menuLoginOther()
}

/**
 * 菜单叶子项 id → 动作。id 规则见 MenuModel（`file./account./win./app|/view./tool./set./help./about.` 前缀）。
 * 与 PC 端 `__menuRegistry` 的语义一致：点完先关菜单，再执行动作。
 */
object MenuActions {

    fun perform(t: MenuTarget, id: String): Boolean {
        val web = t.menuWeb()
        val base = t.menuBase()
        val ctx = t.act
        when {
            // ---------------------------------------------------------- 文件
            id == "file.home" -> { t.menuGoHome(); return true }
            id == "account.other" -> { t.menuLoginOther(); return true }
            id.startsWith("account.use|") -> {
                t.menuConnectTo(id.substringAfter("account.use|"))
                return true
            }
            id.startsWith("win.goto|") -> {
                focusWindow(ctx, id.substringAfter("win.goto|"))
                return true
            }
            id == "file.lock" -> { t.menuLock(); return true }
            id == "file.hide" -> { t.menuHideAll(); return true }
            id == "file.minimize" -> { t.menuMinimize(); return true }
            id == "file.tray" -> { t.menuHideToTray(); return true }
            id == "file.close" -> { t.menuCloseWindow(); return true }

            // ---------------------------------------------------------- 下载
            id == "app|download" -> { openApp(ctx, base, "download", "下载"); return true }

            // ---------------------------------------------------------- 视图
            id == "view.back" -> {
                if (web != null && web.canGoBack()) web.goBack() else toast(ctx, "没有上一页")
                return true
            }
            id == "view.forward" -> {
                if (web != null && web.canGoForward()) web.goForward() else toast(ctx, "没有下一页")
                return true
            }
            id == "view.reload" -> { web?.reload(); return true }
            id == "view.hardReload" -> {
                try { web?.clearCache(true) } catch (_: Throwable) {}
                web?.reload(); return true
            }
            id == "view.zoomReset" -> { setZoom(ctx, web, 1.0); return true }
            id == "view.zoomIn" -> { setZoom(ctx, web, 0.0, up = true); return true }
            id == "view.zoomOut" -> { setZoom(ctx, web, 0.0, up = false); return true }
            id == "view.fullscreen" -> { t.menuToggleFullscreen(); return true }

            // ---------------------------------------------------------- 工具
            id == "tool.live" -> {
                val i = Intent(ctx, LiveActivity::class.java)
                i.putExtra("base", base)
                ctx.startActivity(i)
                return true
            }
            // PC 是「用 MPV 嵌入播放」；Android 无内嵌 mpv → 用系统播放器/libmpv 打开当前视频 URL
            id == "tool.mpv" -> { openCurrentVideo(ctx, web); return true }
            id == "tool.copyLink" -> {
                val u = web?.url ?: ""
                copy(ctx, "当前窗口链接", u)
                toast(ctx, if (u.isBlank()) "当前没有链接" else "已复制：$u")
                return true
            }
            id == "tool.openBrowser" -> {
                val u = web?.url ?: ""
                if (u.isBlank()) { toast(ctx, "当前没有链接"); return true }
                try {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                } catch (_: Exception) { toast(ctx, "没有可用的浏览器") }
                return true
            }

            // ---------------------------------------------------------- 设置
            // v5.1.30：与 PC 完全一致 —— 打开的是 PC 那份 settings.html（SettingsWebActivity），
            // 原生 SettingsActivity 保留但不再从菜单进入（诊断导出等能力还在里面）。
            id == "set.prefs" -> {
                ctx.startActivity(Intent(ctx, SettingsWebActivity::class.java))
                return true
            }

            // ---------------------------------------------------------- 帮助
            id == "help.netprefs" -> { openAppSystemSettings(ctx); return true }
            id == "help.steps" -> {
                ctx.startActivity(Intent(ctx, HelpActivity::class.java))
                return true
            }
            id == "help.uninstall" -> { uninstallSelf(ctx); return true }

            // ---------------------------------------------------------- 关于
            id == "about.app" -> {
                ctx.startActivity(Intent(ctx, AboutActivity::class.java))
                return true
            }
        }
        Log2.w(ctx, "menu: unhandled action $id")
        return false
    }

    // ------------------------------------------------------------- 小工具

    private fun openApp(ctx: Context, base: String, appId: String, name: String) {
        val e = Apps.find(ctx, appId) ?: Apps.Entry(appId, name, appId)
        val b = base.ifBlank { Settings.load(ctx).server }
        ctx.startActivity(Shortcuts.intentFor(ctx, e, b))
    }

    /** 「切换窗口」：把登记在案的窗口重新拉到前台（PC 是 win.show() + win.focus()） */
    private fun focusWindow(ctx: Context, name: String) {
        val e = WindowRegistry.find(name)
        if (e == null) { toast(ctx, "该窗口已关闭"); return }
        try {
            val i = Intent(e.intent)
            i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (ex: Exception) {
            toast(ctx, "切换到「$name」失败：${ex.message}")
        }
    }

    /**
     * 工具 → 🎬 用 MPV 嵌入播放当前视频
     * Android 映射：取当前网页 <video> 的直链，交给系统播放器（装了 libmpv 内核的播放器会接管）。
     */
    private fun openCurrentVideo(ctx: Context, web: WebView?) {
        if (web == null) { toast(ctx, "当前页面没有可播放的视频"); return }
        val js = """
            (function(){
              try{
                var v = document.querySelector('video');
                var u = v ? (v.currentSrc || v.src || '') : '';
                if (!u) {
                  var ss = document.querySelectorAll('source');
                  for (var i=0;i<ss.length;i++){ if (ss[i].src) { u = ss[i].src; break; } }
                }
                return String(u || '');
              }catch(e){ return ''; }
            })()
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            val url = unquoteJs(raw)
            if (url.isBlank()) { toast(ctx, "当前页面没有可播放的视频"); return@evaluateJavascript }
            try {
                val u = Uri.parse(url)
                val i = Intent(Intent.ACTION_VIEW)
                i.setDataAndType(u, videoMime(url))
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
                Log2.w(ctx, "menu: mpv → ACTION_VIEW $url")
            } catch (e: Exception) {
                Log2.w(ctx, "menu: mpv open failed ${e.message}")
                toast(ctx, "没有可用的视频播放器")
            }
        }
    }

    private fun videoMime(url: String): String {
        val p = url.substringBefore('?').lowercase()
        return when {
            p.endsWith(".m3u8") -> "application/x-mpegURL"
            p.endsWith(".mpd") -> "application/dash+xml"
            p.endsWith(".mkv") -> "video/x-matroska"
            p.endsWith(".mp4") || p.endsWith(".m4v") -> "video/mp4"
            p.endsWith(".webm") -> "video/webm"
            p.endsWith(".ts") -> "video/mp2t"
            p.endsWith(".mov") -> "video/quicktime"
            else -> "video/*"
        }
    }

    private fun unquoteJs(raw: String?): String {
        var s = (raw ?: "").trim()
        if (s == "null") return ""
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length - 1)
        return s.replace("\\/", "/").replace("\\u003d", "=").replace("\\u0026", "&")
    }

    /** 帮助 → 打开「本地网络」权限设置…（Android 映射为本应用的系统设置页） */
    private fun openAppSystemSettings(ctx: Context) {
        try {
            val i = Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + ctx.packageName)
            )
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (e: Exception) {
            toast(ctx, "打不开系统设置：${e.message}")
        }
    }

    /** 帮助 → 卸载 FNOS 客户端…（ACTION_DELETE 指向自身包名） */
    private fun uninstallSelf(ctx: Context) {
        try {
            val i = Intent(Intent.ACTION_DELETE, Uri.parse("package:" + ctx.packageName))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (e: Exception) {
            toast(ctx, "打不开卸载界面：${e.message}")
        }
    }

    private fun setZoom(ctx: Context, web: WebView?, target: Double, up: Boolean = false) {
        if (web == null) return
        val cur = web.settings.textZoom
        val next = when {
            target > 0 -> (target * 100).toInt()
            up -> (cur * 1.1).toInt()
            else -> (cur / 1.1).toInt()
        }.coerceIn(50, 250)
        web.settings.textZoom = next
        toast(ctx, "缩放 $next%")
    }

    private fun copy(ctx: Context, label: String, text: String) {
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
        } catch (_: Exception) {}
    }

    private fun toast(ctx: Context, s: String) {
        try { Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
    }

    // v5.1.30：原来的「原生 AlertDialog 版帮助 / 关于」已下线，
    // 改为打开与 PC 完全一致的 assets/web/help.html 与 assets/web/about.html
    // （HelpActivity / AboutActivity，见 MenuActions.perform 的 help.steps / about.app）。
}
