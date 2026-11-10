package com.fnos.tablet

/**
 * 网页自绘标题栏 → Android 窗口的控制回调。
 * 对应 PC 端主进程处理的 ipcMain 通道（window-close / window-minimize / window-maximize /
 * app-popup-menu / auth:back-to-connect）。
 */
interface TitlebarHost {
    /** window-close */
    fun tbClose()

    /** window-minimize */
    fun tbMinimize()

    /** window-maximize（Android 语义：切全屏 / 退出全屏） */
    fun tbToggleMaximize()

    /** auth:back-to-connect（悬浮球「添加账号」） */
    fun tbBackToConnect()

    /** app-popup-menu（标题栏 ☰） */
    fun tbPopupMenu(anchorJson: String?)

    /** win-fs-state */
    fun tbIsFullscreen(): Boolean
}
