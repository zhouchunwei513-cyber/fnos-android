package com.fnos.tablet

/**
 * 帮助页 —— 与 PC 端**同一份** assets/web/help.html（自带内联 CSS/JS，无外部依赖）。
 *
 * PC：`BrowserWindow.loadFile('help.html')`；入口「帮助 → 操作步骤帮助」。
 * 这里只做一件事：把 PC 的 help.html 原样显示出来，并把版本号通过 ?v= 传进去
 * （页脚的 FNOS CLIENT 版本号就是读这个 query）。
 */
class HelpActivity : LocalWebActivity() {

    override fun pageUrl(): String = WebAssets.helpUrl() + "?v=" + android.net.Uri.encode(appVersion())

    override fun dialogTitle(): String = "操作步骤帮助"
}
