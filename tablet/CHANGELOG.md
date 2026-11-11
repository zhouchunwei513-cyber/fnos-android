# CHANGELOG —— 飞牛平板客户端（com.fnos.tablet）

> 版本号由用户锁定：本轮改动**不升版本**，仍是 `versionCode 16 / 5.1.31-tablet16`。

## [未发布] 2026-11-11 平板端收尾三项

### 修复 1：电视直播「创建桌面快捷方式」点了没反应

- **真因（真机实测 07:30）**：页面报 `创建失败：intent's action must be set`。
  `Shortcuts.liveIntent()` 构造的 Intent 只设了 component/extras/data，**没有 action**；
  `ShortcutManagerCompat.requestPinShortcut()` 校验 `ShortcutInfo` 的 intent 时必须带 action，
  直接抛 `IllegalArgumentException`，被 `pin()` 的 catch 兜住后返回失败 → 桌面上永远不会出现图标。
  （对照：`intentFor()` / `mainIntent()` 都写了 `i.action = ACTION_OPEN_APP`，所以应用图标一直能钉。）
- **修法**：`liveIntent()` 补 `action = ACTION_OPEN_APP` + `FLAG_ACTIVITY_NEW_TASK`，与其它两条 Intent 一致。
- **取证**：`cmd shortcut get-shortcuts --user 0 com.fnos.tablet` 出现 `fn-pin-live`（PinIc）；
  桌面第 2 页出现「电视直播 · 飞牛」图标（内置电视图标 `res/drawable/ic_live.xml`）；
  系统 Toast「✓ 已钉到桌面：电视直播」；点该图标 `ResumedActivity = com.fnos.tablet/.LiveActivity`。

### 修复 2：应用宫格长按 → 钉到桌面（全部应用）

- `Shortcuts.pin()` 统一入口：先 `support()` 判断桌面是否支持，再 `requestPinShortcut`，
  2.5s 后回读 `getPinnedShortcuts()` 复核并以 Toast 明示「✓ 已钉到桌面 / 未受理原因」。
- 设置页「应用快捷方式创建」面板每行都有「钉到桌面」，已钉的行显示「✓ 已钉到桌面」
  （`SettingsWebActivity.onResume()` 调 `window.__fnosRefreshPinned()` 回读系统真实状态）。
- **取证**：本次又钉 6 个应用（XTE / Lucky / NPC / Moo / OpenList / UPS 电源管理），
  叠加此前影视，桌面共 7 个 `fn-pin-*`；设置页截图里 影视/Moo 显示「✓ 已钉到桌面」。

### 修复 3：直播页没有标题栏

- `LiveActivity` 改为实现 `TitlebarHost`（`tbClose / tbMinimize / tbToggleMaximize / tbPopupMenu /
  tbIsFullscreen / tbBackToConnect`），在 `onWebPageFinished()` 里调
  `TitlebarInject.inject(web, this, false)`，与主窗/应用窗注入**同一份** `titlebar-inject.js`。
- 窗口按钮语义与主窗一致：**最小化 = 退到后台（音频继续）**、**最大化 = 页面全屏（隐藏系统栏）**、
  **关闭 = finish()**。默认是「窗口态」，第一次点 □ 才是进全屏（此前 onCreate 里强制沉浸，语义反了，已删）。
- 标题栏让出顶部 34px 后重新贴合原生 ExoPlayer 矩形（`syncEmbedRect()` 延时两次）；
  原生 ☰ 浮钮保留，用于播放中展开频道抽屉（页面内的 `#sideToggle` 被原生播放器盖住）。
- **取证**：横屏截图 `/tmp/live-run3.png`（☰ + − □ ✕ + 账号球，CCTV1 正在播）；
  日志 `live: titlebar-inject ok=true`、`live: rect x=765 y=77 w=1796 h=1284 root=2560x1504 vid=1920x1080`；
  竖屏 `bh=48px`、抽屉 `-424,48,420,1048` 正常。

### 未改动

- 版本号、`AppWindowActivity` 的 base+path 直播修正、设置页栅格、菜单：一行未动。
- 未提交、未推送 GitHub。
