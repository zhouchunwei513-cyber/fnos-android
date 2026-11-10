# fnos-Android

飞牛 fnOS 客户端 —— **Android 家族**（与 `fnos-desktop` 的 Windows/macOS 端同源同功能）。

目标：**页面 / 功能 / 逻辑与 PC 客户端完全一致**，只按设备形态重做布局与输入方式。

## 目录

| 目录 | 端 | 状态 | 说明 |
|---|---|---|---|
| `tablet/` | 平板端 | ✅ **已发布** | 小米平板 5 Pro（1600×2560），横竖屏自适应 |
| `phone/` | 手机端 | 🚧 开发中 | 小米 13（1080×2400 @440dpi），纵向滚动应用网格 |
| `tv/` | TV 端 | 🚧 开发中 | Android TV / 盒子，遥控器 D-pad + 虚拟光标，不做竖屏 |
| `fold/` | 折叠屏 | 📋 规划 | 内折/外折、双屏连续性 |
| `wide/` | 阔折叠 / 阔板 | 📋 规划 | 大内屏多任务布局 |

## 各端共同点（与 PC 一致的部分）

- **登录页**：PC 原版 `login.html`（服务器地址 / FNID / https 自动识别）
- **自绘标题栏**：汉堡菜单 + 窗口按钮 + 账号球（TV 端用焦点导航）
- **菜单**：PC 同款 7 项（文件 / 下载 / 视图 / 工具 / 设置 / 帮助 / 关于），label、顺序、分隔线、快捷键文案一致
- **偏好设置**：PC 原版 `settings.html`（不是原生长列表）
- **帮助 / 关于**：PC 原版 `help.html` / `about.html`
- **电视直播**：PC 原版 `live.html`（频道分组 / 收藏 / 线路切换）
- **应用列表与图标**：FPK 图标管理器 `GET /api/apps` + `/api/icons/<name>/256`
- **桌面快捷方式**：长按应用图标 → 快捷方式列表；可钉到桌面；点图标直达应用窗

## 构建（tablet）

```bash
cd tablet
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
gradle assembleDebug --no-build-cache --offline
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> **注意**：Gradle 构建缓存会返回旧 dex（`dexBuilderDebug FROM-CACHE`），务必带 `--no-build-cache`。

## 设备适配

| 端 | 分辨率 | density | 输入 |
|---|---|---|---|
| 平板 | 1600×2560 | 360 | 触摸 + 横竖屏 |
| 手机 | 1080×2400 | 440~468 | 触摸 |
| TV | 1920×1080 | 320 | 遥控器 D-pad |
