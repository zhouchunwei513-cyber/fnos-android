package com.fnos.tablet

/**
 * 帮助页 —— 与 PC 端**同一份** assets/web/help.html（自带内联 CSS/JS，无外部依赖）。
 *
 * PC：`BrowserWindow.loadFile('help.html')`；入口「帮助 → 操作步骤帮助」。
 * 平板：WebView 显示同一份页面，外加 PadCss.HELP（字号/行高/卡片间距/横屏双列）。
 *
 * v5.1.32：
 *  · pageBackgroundColor 改成 #000 —— help.html 是 `html{background:transparent}` +
 *    `/body{background:#000}`，壳里原来给的是 #0B0D12，首帧就是「#0B0D12 → #000」的颜色跳变，
 *    用户看到的就是打开帮助页闪一下。三者对齐后不再闪。
 *  · 注入 PadCss.HELP：平板上不再是 PC 的 880px 单列细条。
 */
class HelpActivity : LocalWebActivity() {

    override fun pageUrl(): String = WebAssets.helpUrl() + "?v=" + android.net.Uri.encode(appVersion())

    override fun dialogTitle(): String = "操作步骤帮助"

    /** 与 help.html 的 body 背景一致 */
    override fun pageBackgroundColor(): Int = 0xFF000000.toInt()

    override fun padCss(): String = PadCss.HELP
}
