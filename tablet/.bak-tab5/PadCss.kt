package com.fnos.tablet

/**
 * 平板适配 CSS —— 只注入到 Android 壳内的本地页面（帮助 / 偏好设置 / 关于）。
 *
 * 为什么放在壳里而不是改 assets/web 下的 html：
 *   assets/web/ 下的文件与 PC 仓库是**同一份**（PC 用 Electron loadFile 加载），
 *   直接改它们等于改 PC 端界面。这里用 documentStart 注入一段 <style>，
 *   只在 WebView 内生效，PC 端一行不动。
 *
 * 适配目标（2560×1600、dpr 2.25、横屏 ≈1138 CSS px / 竖屏 ≈711 CSS px）：
 *  · 字号基准：正文 12.5/14px → 15.5/16px，行高 1.7 → 1.85，触控不费眼
 *  · 卡片间距/圆角/内边距放大
 *  · 栅格列数：横屏双列、竖屏单列（PC 的 880/726px 固定宽在平板上只占 64%，两侧空一片）
 *  · 控件高度：按钮/输入框 34~40px → 44px（平板拇指点击面积）
 */
object PadCss {

    /** 所有本地页共用的小垫片 */
    private const val BASE = """
*, *::before, *::after { -webkit-tap-highlight-color: transparent; }
html { -webkit-text-size-adjust: 100%; text-size-adjust: 100%; }
"""

    /** 帮助页（help.html：main.container + section.card 单列） */
    val HELP: String = BASE + """
main.container {
  max-width: 1200px !important;
  padding-left: 30px !important;
  padding-right: 30px !important;
  padding-bottom: 64px !important;
}
main.container > .card {
  margin-bottom: 20px !important;
  padding: 24px 28px !important;
  border-radius: 20px !important;
  min-width: 0;
}
.card h2 { font-size: 20px !important; margin-bottom: 14px !important; }
.card h3 { font-size: 16.5px !important; margin-top: 18px !important; }
.card p, .card li { font-size: 15.5px !important; line-height: 1.85 !important; }
.card li { margin-bottom: 7px !important; }
.card .kbd, .card code { font-size: 13.5px !important; }
.hero { padding-top: 40px !important; }
.hero-title { font-size: 34px !important; }
.hero-sub { font-size: 14px !important; letter-spacing: 5px !important; }

/* 横屏（平板横放）→ 双列；竖屏（默认）→ 单列 */
@media (orientation: landscape) and (min-width: 760px) {
  main.container {
    display: grid !important;
    grid-template-columns: repeat(2, minmax(0, 1fr)) !important;
    column-gap: 22px !important;
    row-gap: 22px !important;
    align-items: start;
  }
  main.container > .card { margin-bottom: 0 !important; }
  /* 表格/长代码块这类宽内容跨满两列 */
  main.container > .card:has(pre),
  main.container > .card:has(table) { grid-column: 1 / -1 !important; }
}

/* 竖屏：单列铺满，两侧留白收窄（711 CSS px 宽度下 30px 边距太浪费） */
@media (orientation: portrait) {
  main.container {
    padding-left: 16px !important;
    padding-right: 16px !important;
    max-width: none !important;
  }
  main.container > .card { padding: 20px 18px !important; margin-bottom: 16px !important; }
  .card h2 { font-size: 19px !important; }
  .card p, .card li { font-size: 15px !important; line-height: 1.8 !important; }
  .hero { padding-top: 26px !important; }
  .hero-title { font-size: 28px !important; }
}
"""

    /** 偏好设置页（settings.html：main.settings-card + section.settings-section 单列） */
    val SETTINGS: String = BASE + """
.settings-body { padding-left: 34px !important; padding-right: 34px !important; }
.settings-card {
  max-width: 1120px !important;
  padding: 8px 4px 28px !important;
}
.settings-head { padding-bottom: 24px !important; }
.settings-head h1 { font-size: 26px !important; }
.settings-head p { font-size: 13px !important; }
.settings-section { padding: 26px 22px 20px !important; }
.section-title h2 { font-size: 18px !important; }
.section-desc { font-size: 14px !important; line-height: 1.8 !important; }

/* 控件放大：平板拇指点击 */
.glass-input, .rewrite-row .glass-input { height: 44px !important; font-size: 14.5px !important; }
.settings-btn, .account-btn, .hotkey-input { min-height: 40px !important; font-size: 14px !important; }
.app-card { padding: 14px 16px !important; }
.app-card-name { font-size: 15px !important; }
.app-card-btn { height: 40px !important; font-size: 13.5px !important; }

/* 横屏：卡片区两列 —— 用 CSS 多列（瀑布流）而不是 grid。
   grid 的两列是「按行对齐」的：13 个 section 高矮差很大，短卡片排完右列就空了，
   页面底部出现整块空白（用户重点反馈）。多列按「列高均衡」分配，底部自然齐平。 */
@media (orientation: landscape) and (min-width: 760px) {
  .settings-card {
    display: block !important;
    column-count: 2 !important;
    column-gap: 22px !important;
    column-fill: balance !important;
    max-width: none !important;
  }
  /* 页头与底部按钮条跨满整宽（不在列里被切断） */
  .settings-card > .settings-head,
  .settings-card > .setup-actions,
  .settings-card > .settings-foot,
  .settings-card > .footer,
  .settings-card > .actions { column-span: all !important; display: block !important; }
  /* 每个大项整体不跨列断开 */
  .settings-section {
    break-inside: avoid !important;
    -webkit-column-break-inside: avoid !important;
    page-break-inside: avoid !important;
    margin-bottom: 18px !important;
    border-bottom: 1px solid rgba(255,255,255,.06) !important;
    border-radius: 14px;
  }
  /* 应用列表 / 账号列表这类超长卡片允许折列，否则单卡会把列高撑到超过一屏 */
  .settings-section:has(#app-list),
  .settings-section:has(.account-list),
  .settings-section:has(#account-list),
  .settings-section:has(.skin-list),
  .settings-section:has(#url-rewrite-list) {
    break-inside: auto !important;
    -webkit-column-break-inside: auto !important;
    page-break-inside: auto !important;
  }
}

/* 竖屏：单列铺满、去掉 max-width 造成的左右留白 */
@media (orientation: portrait) {
  .settings-body { padding-left: 14px !important; padding-right: 14px !important; }
  .settings-card {
    max-width: none !important;
    width: auto !important;
    padding: 4px 0 22px !important;
  }
  .settings-section { padding: 22px 16px 18px !important; margin-bottom: 14px !important; }
  .section-title h2 { font-size: 17.5px !important; }
  .section-desc { font-size: 13.5px !important; }
  .glass-input, .rewrite-row .glass-input { height: 46px !important; font-size: 15px !important; }
  .settings-btn, .account-btn, .hotkey-input { min-height: 44px !important; }
}
"""

    /** 关于页（about.html：单卡片小页面） */
    val ABOUT: String = BASE + """
.about-card, .card { max-width: 900px !important; }
.card p, .card li { font-size: 15.5px !important; line-height: 1.85 !important; }
"""

    /**
     * 注入脚本：改 viewport meta（settings.html 里写死了 width=780，PC 窗口用；
     * 平板上会让页面以 780 排版再放大，字号与占比都不对）+ 插 <style>。
     * 用 MutationObserver 兜底：页面若二次插入自己的 <style>，我们的 style 永远排在最后。
     */
    fun script(css: String): String {
        val safe = css.replace("\\", "\\\\").replace("`", "\\`").replace("$", "\\$")
        return """
(function(){
  try {
    var m = document.querySelector('meta[name="viewport"]');
    if (m) m.setAttribute('content', 'width=device-width, initial-scale=1, viewport-fit=cover');
  } catch (e) {}
  function apply() {
    if (document.getElementById('fn-pad-css')) return;
    var s = document.createElement('style');
    s.id = 'fn-pad-css';
    s.textContent = `$safe`;
    (document.head || document.documentElement).appendChild(s);
  }
  apply();
  try {
    if (document.head) {
      new MutationObserver(function(){
        if (!document.getElementById('fn-pad-css')) apply();
      }).observe(document.head, { childList: true });
    }
  } catch (e) {}
})();
"""
    }
}
