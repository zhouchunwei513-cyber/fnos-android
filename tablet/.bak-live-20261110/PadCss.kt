package com.fnos.tablet

/**
 * 平板适配 CSS —— 只注入到 Android 壳内的本地页面（帮助 / 偏好设置 / 关于 / 菜单）。
 *
 * 为什么放在壳里而不是改 assets/web 下的 html：
 *   assets/web/ 下的文件与 PC 仓库是**同一份**（PC 用 Electron loadFile 加载），
 *   直接改它们等于改 PC 端界面。这里用 documentStart 注入一段 <style>，
 *   只在 WebView 内生效，PC 端一行不动。
 *
 * 适配目标（2560×1600、dpr 2.25、横屏 ≈1138 CSS px / 竖屏 ≈711 CSS px）：
 *  · 字号基准：正文 12.5/14px → 15.5/16px，行高 1.7 → 1.85，触控不费眼
 *  · 卡片间距/圆角/内边距放大
 *  · 偏好设置栅格列数：横屏双列**等宽**瀑布流、竖屏单列**铺满**（PC 的 726px 固定宽在平板上
 *    只占 64%，两侧空一片）
 *  · 控件高度：按钮/输入框 34~40px → 44px（平板拇指点击面积）
 *  · 菜单面板：宽度贴内容（最长条目 + 内边距），上限 42vw 横屏 / 78vw 竖屏
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

    /**
     * 偏好设置页（settings.html：main.settings-card + N×section.settings-section）。
     *
     * 两条硬要求（用户两次反馈）：
     *  1) 横屏两列**严格等宽**且底部齐平 → 用 CSS 多列（column-count:2 + column-fill:balance）。
     *     多列的列宽由容器宽等分，天然等宽（不存在「一列宽一列窄」）；
     *     平衡填充按「列高均衡」分配，底部不会出现整列空白。
     *  2) 竖屏**铺满宽度** → 取消 .settings-card 的 max-width:726px（PC 窗口值），
     *     只留 20px 左右安全边距（卡片宽 = 屏宽 - 40px，满足「≥ 屏宽-48dp」）。
     *
     * 注意：网页自己的 settings.css 里 `.settings-card{max-width:726px}` 与
     * `.settings-body{justify-content:center}` 是 PC 窄窗的排版，必须在这里全部覆盖。
     */
    val SETTINGS: String = BASE + """
/* ===== 基础（横竖屏共用）===== */
.settings-body {
  /* 20px 左右边距：竖屏卡片宽 = 屏宽 - 40px（要求 ≥ 屏宽 - 48dp） */
  padding: 18px 20px 26px !important;
  justify-content: flex-start !important;
}
.settings-card {
  /* 不许固定 max-width —— 竖屏要铺满、横屏要撑满可用宽 */
  max-width: none !important;
  width: 100% !important;
  padding: 4px 0 16px !important;
}
.settings-head { padding-bottom: 22px !important; }
.settings-head h1 { font-size: 26px !important; }
.settings-head p { font-size: 13px !important; }
.settings-section {
  padding: 24px 20px 18px !important;
  border-radius: 14px;
  border-bottom: 1px solid rgba(255,255,255,.06) !important;
}
.section-title h2 { font-size: 18px !important; }
.section-desc { font-size: 14px !important; line-height: 1.8 !important; }

/* 控件放大：平板拇指点击 */
.glass-input, .rewrite-row .glass-input { height: 44px !important; font-size: 14.5px !important; }
.settings-btn, .account-btn, .hotkey-input { min-height: 40px !important; font-size: 14px !important; }
.app-card { padding: 14px 16px !important; }
.app-card-name { font-size: 15px !important; }
.app-card-btn { height: 40px !important; font-size: 13.5px !important; }

/* 等宽的关键：列里的任何子元素都不许把所在列撑宽 */
.settings-section, .settings-section > * { min-width: 0 !important; max-width: 100% !important; }
.settings-section img { max-width: 100% !important; height: auto; }

/* ===== 横屏：两列，严格等宽 ===== */
@media (orientation: landscape) {
  /* ① 首选方案（PadCss.SETTINGS_JS 生效后加 .fn-cols）：两个 flex 列，
        列宽 = (卡片宽 - 22)/2，天然等宽；卡片整块不切断，按高度贪心分配到底部齐平。 */
  .settings-card.fn-cols {
    display: flex !important;
    flex-wrap: wrap !important;
    align-items: flex-start !important;
    column-count: auto !important;
    column-width: auto !important;
    column-gap: 22px !important;
    row-gap: 0 !important;
  }
  .settings-card.fn-cols > .settings-head { flex: 1 1 100% !important; }
  .settings-card.fn-cols > .fn-col {
    flex: 1 1 0 !important;
    min-width: 0 !important;
    display: flex !important;
    flex-direction: column !important;
    gap: 18px !important;
  }
  .settings-card.fn-cols > .fn-col > .settings-section { margin-bottom: 0 !important; }

  /* ② 兜底（脚本没跑起来时）：CSS 多列。列宽一样是等分，但长卡片会被跨列切断。 */
  .settings-card:not(.fn-cols) {
    display: block !important;
    column-count: 2 !important;
    column-gap: 22px !important;
    column-fill: balance !important;
    column-width: auto !important;
  }
  .settings-card:not(.fn-cols) > .settings-head {
    column-span: all !important;
    display: block !important;
    break-inside: avoid !important;
  }
  .settings-section {
    break-inside: avoid !important;
    -webkit-column-break-inside: avoid !important;
    page-break-inside: avoid !important;
    margin-bottom: 18px !important;
  }
  .settings-section:last-child { border-bottom: 0 !important; }
  /* 超长列表卡片允许跨列，否则单卡把一个列撑得远超另一列（整块空白） */
  .settings-section:has(#app-list),
  .settings-section:has(.account-list),
  .settings-section:has(#account-list),
  .settings-section:has(.skin-list),
  .settings-section:has(#url-rewrite-list),
  .settings-section:has(#rewrite-list),
  .settings-section.allow-split {
    break-inside: auto !important;
    -webkit-column-break-inside: auto !important;
    page-break-inside: auto !important;
  }

  /* ------------------------------------------------------------------
     列宽只有 ~533 CSS px，而网页里这些行是按 PC 的 726px 写的：
     「说明 + 固定宽控件」并排时说明会被挤成一条竖排窄条（实测过，很难看）。
     这里统一改成「说明占满一行，控件换行到下一行」—— 仅平板注入，PC 不动。
     ------------------------------------------------------------------ */
  .hotkey-row {
    flex-wrap: wrap !important;
    row-gap: 10px !important;
    column-gap: 0 !important;
  }
  .hotkey-row > .hotkey-label { flex: 1 1 100% !important; min-width: 0 !important; }
  .hotkey-row > *:not(.hotkey-label) { flex: 0 1 auto !important; max-width: 100% !important; }
  /* 内联样式的「说明 + 控件」行（界面/播放器里大量存在）：同样折行 */
  .settings-section > div[style*="display:flex"],
  .settings-section > div[style*="display: flex"] {
    flex-wrap: wrap !important;
    row-gap: 10px !important;
  }
  .settings-section > div[style*="display:flex"] > *:first-child,
  .settings-section > div[style*="display: flex"] > *:first-child {
    flex: 1 1 100% !important;
    min-width: 0 !important;
  }
  .settings-section > div[style*="display:flex"] > *:last-child,
  .settings-section > div[style*="display: flex"] > *:last-child {
    flex: 0 1 auto !important;
    max-width: 100% !important;
  }
  /* 圆角五档 / 材质切换这类按钮组：允许换行，别把卡片撑破 */
  .tb-material-group { flex-wrap: wrap !important; }
  .setup-actions { flex-wrap: wrap !important; }
}

/* ===== 竖屏：单列铺满（无 max-width）===== */
@media (orientation: portrait) {
  .settings-card { column-count: 1 !important; display: block !important; }
  .settings-section { padding: 22px 16px 16px !important; margin-bottom: 14px !important; }
  .section-title h2 { font-size: 17.5px !important; }
  .section-desc { font-size: 13.5px !important; }
  .glass-input, .rewrite-row .glass-input { height: 46px !important; font-size: 15px !important; }
  .settings-btn, .account-btn, .hotkey-input { min-height: 44px !important; }
}
"""

    /**
     * 偏好设置页的布局自检脚本（padJs）。
     *
     * 只做三件事：
     *  ① 曝光真实几何（viewport / 卡片 / 每个 section 的 left+width+height）走 console.log，
     *     由 LocalWebActivity 落进 startup.log —— 真机验收「两列等宽 ≤4px」「竖屏满宽」
     *     靠这行数字，不靠肉眼。
     *  ② 粘性兜底：横屏时若多列高度明显失衡（某列比另一列高 15% 以上），
     *     把最长的那个 section 追加 `allow-split` 允许跨列，让底部对齐。
     *  ③ 不改任何设置项行为（不碰 settings.js 的 DOM 结构）。
     */
    val SETTINGS_JS: String = """
(function(){
  try {
    var COLGAP = 22, ROWGAP = 18;
    function rect(el){
      var r = el.getBoundingClientRect();
      return [Math.round(r.left), Math.round(r.top + window.scrollY), Math.round(r.width), Math.round(r.height)];
    }
    function cards(card){ return [].slice.call(card.querySelectorAll('.settings-section')); }
    function colsOf(card){ return [].slice.call(card.querySelectorAll(':scope > .fn-col')); }

    function collect(tag){
      try {
        var card = document.querySelector('.settings-card');
        if (!card) return;
        var cols = colsOf(card);
        var cs = window.getComputedStyle(card);
        var bottoms = {}, widths = {};
        cols.forEach(function(c){
          var r = c.getBoundingClientRect();
          var k = String(Math.round(r.left));
          bottoms[k] = Math.round(r.top + window.scrollY + r.height);
          widths[k] = Math.round(r.width);
        });
        var o = {
          tag: tag,
          vw: window.innerWidth, vh: window.innerHeight,
          mode: cols.length ? 'flex2' : (cs.columnCount !== 'auto' ? 'css-multicol' : 'single'),
          card: rect(card),
          colBottom: bottoms,
          colWidth: widths,
          sections: cards(card).map(function(s){
            var h2 = s.querySelector('.section-title h2');
            return {t: h2 ? h2.textContent : '', r: rect(s)};
          })
        };
        console.log('fn-pad-metrics ' + JSON.stringify(o));
      } catch (e) { console.log('fn-pad-metrics err ' + e); }
    }

    /** 只保留一列（竖屏 / 窄窗）：把卡片搬回 .settings-card 里，删掉列容器 */
    function unwrap(card, cols){
      var i, j, c, ks;
      for (i = 0; i < cols.length; i++) {
        c = cols[i];
        ks = [].slice.call(c.querySelectorAll('.settings-section'));
        for (j = 0; j < ks.length; j++) card.appendChild(ks[j]);
        c.parentNode.removeChild(c);
      }
      card.classList.remove('fn-cols');
    }

    /** 横屏两列：按高度贪心（LPT）分配，两列等宽、卡片整块不切断、底部齐平 */
    function layout(){
      var card = document.querySelector('.settings-card');
      if (!card) return;
      var land = window.matchMedia('(orientation: landscape)').matches && window.innerWidth >= 900;
      var cols = colsOf(card);
      if (!land) { if (cols.length) unwrap(card, cols); return; }
      var secs = cards(card);
      if (secs.length < 2) return;
      if (cols.length !== 2) {
        if (cols.length) unwrap(card, cols);
        for (var n = 0; n < 2; n++) {
          var d = document.createElement('div');
          d.className = 'fn-col';
          card.appendChild(d);
        }
        cols = colsOf(card);
      }
      card.classList.add('fn-cols');
      /* 先全部塞进左列，量「半宽」下的真实高度 */
      secs.forEach(function(s){ cols[0].appendChild(s); });
      void card.offsetHeight;
      var hs = secs.map(function(s){ return Math.round(s.getBoundingClientRect().height); });
      var idx = hs.map(function(h, i){ return i; }).sort(function(a, b){ return hs[b] - hs[a]; });
      var sums = [0, 0], put = [];
      idx.forEach(function(i){
        var t = sums[0] <= sums[1] ? 0 : 1;
        put[i] = t;
        sums[t] += hs[i] + ROWGAP;
      });
      /* 按原 DOM 顺序落位，阅读顺序不乱 */
      secs.forEach(function(s, i){ cols[put[i]].appendChild(s); });
      var a = Math.round(cols[0].getBoundingClientRect().height);
      var b = Math.round(cols[1].getBoundingClientRect().height);
      console.log('fn-pad-layout flex2 a=' + a + ' b=' + b + ' diff=' + Math.abs(a - b) + ' cards=' + secs.length);
    }

    if (!window.__fnPadMetrics) {
      window.__fnPadMetrics = collect;
      var run = function(){ try { layout(); } catch (e) { console.log('fn-pad-layout err ' + e); } };
      var boot = function(){ run(); collect('boot'); };
      if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
      else boot();
      /* settings.js 异步渲染列表 → 卡片高度后变，再排一次 */
      setTimeout(function(){ run(); collect('t900'); }, 900);
      setTimeout(function(){ run(); collect('t2000'); }, 2000);
      var last = 0;
      window.addEventListener('resize', function(){
        clearTimeout(last);
        last = setTimeout(function(){ run(); collect('resize'); }, 400);
      });
      var lasto = 0;
      window.addEventListener('orientationchange', function(){
        clearTimeout(lasto);
        lasto = setTimeout(function(){ run(); collect('orient'); }, 500);
      });
    } else {
      try { layout(); } catch (e) {}
      window.__fnPadMetrics('reinject');
    }
  } catch (e) {}
})();
"""

    /**
     * 菜单面板（menu-popup.html）—— 宽度贴内容。
     *
     * 旧的平板版把一级面板写死 34vw（横屏）/ 66vw（竖屏），实际文字只要 ~100 CSS px，
     * 右侧因此空掉一大片（用户原话「放下菜单选项文字就行」）。
     * 这里改成：
     *  · 宽度 = max-content：正好等于「最长条目（图标位 + 文字 + 快捷键 + 箭头）+ 左右内边距」
     *  · 上限 42vw（横屏）/ 78vw（竖屏）：极端长文案时允许变宽但不超屏宽比例
     *  · 文字不换行不省略（去掉 .lbl 的 max-width:400px 与省略号）
     *  · 二级菜单（data-level>0）本来就是 max-content，允许比一级更宽
     *
     * 选择器统一加 `body` 前缀（0,2,1 > 网页自带的 0,2,0），保证优先级压过
     * menu-popup.html 里那条 `width:34vw !important`。
     */
    fun menuCss(landscape: Boolean): String {
        val cap = if (landscape) "42vw" else "78vw"
        val row = if (landscape) "" else """
body .panel .item { height: 48px !important; min-height: 48px !important; font-size: 15px !important; }
body .panel .sep { margin: 8px !important; }
"""
        return BASE + """
body .panel {
  max-width: $cap !important;
}
body .panel[data-level="0"] {
  width: max-content !important;
  min-width: 0 !important;
  max-width: $cap !important;
}
body .panel[data-level="0"] .item .lbl {
  max-width: none !important;
  overflow: visible !important;
  text-overflow: clip !important;
  white-space: nowrap !important;
}
/* 二级菜单：内容宽度，但同样不许超出屏宽比例 */
body .panel[data-level]:not([data-level="0"]) { max-width: $cap !important; }
$row
"""
    }

    /** 关于页（about.html：单卡片小页面） */
    val ABOUT: String = BASE + """
.about-card, .card { max-width: 900px !important; }
.card p, .card li { font-size: 15.5px !important; line-height: 1.85 !important; }
"""

    /**
     * 注入脚本：改 viewport meta（settings.html 里写死了 width=780，PC 窗口用；
     * 平板上会让页面以 780 排版再放大，字号与占比都不对）+ 插 <style>。
     *
     * v5.1.34：`apply()` 改成「每次都把 <style> 重新 append」——appendChild 一个已存在的
     * 节点等于把它移到末尾，于是我们的样式永远排在页面自带样式之后（同 selectors 时我们赢）。
     * 之前的实现只在节点缺失时才插，documentStart 阶段 head 还没创建时会插到 documentElement，
     * 之后 head 解析出来的 <style> 就排到了我们后面，`!important` 同优先级时反而输掉。
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
    var s = document.getElementById('fn-pad-css');
    if (!s) { s = document.createElement('style'); s.id = 'fn-pad-css'; }
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
