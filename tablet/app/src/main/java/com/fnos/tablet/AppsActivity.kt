package com.fnos.tablet

/**
 * v5.1.34（用户 Bug B）：应用宫格 —— App 内**唯一**的快捷方式入口。
 *
 * 为什么需要它：MIUI Pad 5 Pro 长按应用图标**不弹** dynamic shortcuts
 * （真机截图 /tmp/h1.png：什么菜单都没有），所以「长按图标 → 选应用 → 拖到桌面」
 * 这条兜底在 MIUI 上不成立，必须有一个 App 内可用的宫格。
 *
 * 实现上不复制一套 UI：宫格就是偏好设置页里的那个弹窗（settings.js 的 dsh-grid），
 * 本 Activity 只负责「打开后立刻弹宫格」——所以两个入口（设置页按钮 / 本 Activity）
 * 的行为、图标、分组、长按菜单永远一致。
 *
 *   adb shell am start -n com.fnos.tablet/.AppsActivity
 *
 * 宫格交互：单击 = 打开该应用窗；长按 = 操作菜单（打开 / 钉到桌面 / 设为常用）。
 */
class AppsActivity : SettingsWebActivity() {

    override fun openGridOnLoad(): Boolean = true

    override fun dialogTitle(): String = "应用宫格"
}
