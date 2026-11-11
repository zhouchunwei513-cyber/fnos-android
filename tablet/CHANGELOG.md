# CHANGELOG —— 飞牛平板客户端（com.fnos.tablet）

> 版本号由用户锁定：本轮改动**不升版本**，仍是 `versionCode 16 / 5.1.31-tablet16`。

## [未发布] 2026-11-11 平板端「全部钉到桌面」一键功能

### 新增 1：设置页一键把全部应用钉到桌面

- 页面：偏好设置 → 应用快捷方式创建，新增按钮行「打开应用宫格 / **全部钉到桌面** / 清理全部快捷方式」，
  下方 `#pin-all-status` 状态行。
- 原生：`Shortcuts.pinAll()` —— 主线程 `postDelayed(400ms)` 逐个 `requestPinShortcut`，
  不在页面里 for 循环（WebView 被遮挡时 `setTimeout` 会被节流，且并发下发会被 MIUI 丢）。
- 进度回推：`SettingsBridge.pinAll()` 立即返回 `{ok,started,total}`（不占 JavaBridge 线程），
  进度/结果通过 `evalJs` 调 `window.__fnosPinAllProgress` / `window.__fnosPinAllDone`。
- 幂等：以 `getPinnedShortcuts()` 快照判断，已钉的直接跳过并计入「已存在」。
- 批量模式传 `notify=false`：41 个应用连弹 41 个 Toast 既难看又会被系统限流，结果由页面汇总。
- 完成汇总实测：`✓ 已完成：成功 0 个，已存在 39 个，失败 0 个`（第二次点）；首次 31 新钉 + 7 已存在。
- 失败项：每个都 `Log2.w("shortcut: pinAll.fail id=… reason=…")`，并按两种原因归类
  （MIUI AppOps 10017 未授权 / 请求受理但未落图标）。

### 新增 2：清理全部快捷方式（二次确认）

- `Shortcuts.clearAll()`：`removeAllDynamicShortcuts()` + 对已钉项 `disableShortcuts()`（置灰，附移除提示文案）。
  Android 不允许应用直接删桌面图标，所以文案明确写「长按灰图标移除」。
- 页面侧两道确认：`askConfirm('清理全部快捷方式', …, '确定清理')` → `askConfirm('再确认一次', …, '真的清理')`。

### 修复：批量统计把「电视直播」误判为失败

- 真因：批量队列用 `ID_LIVE`（`__fnos.live__`，PC 端保留标识）当 id，核对时拼成
  `fn-pin-__fnos.live__`，与真实快捷方式 id `fn-pin-live` 对不上 → 明明已落地却记成失败。
- 修法：新增 `LIVE_PIN_SUFFIX` / `LIVE_PIN_ID`，`requestPinLive()` 与 `pinAll()` 共用，
  已钉时能正确走「已存在」分支。

### 修复：确认框确定按钮文案写死「钉到桌面」

- `askConfirm(title, text, okText)` 增加第三个参数；清理流程传「确定清理 / 真的清理」。

### 未改动

- 版本号仍 `5.1.31-tablet16 / code 16`；直播页 / 标题栏 / 快捷方式 URL 修正 / 设置页栅格 / 菜单：一行未动。
- 未提交、未推送 GitHub。

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
