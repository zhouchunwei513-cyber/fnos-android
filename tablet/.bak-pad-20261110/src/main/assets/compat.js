/* 飞牛平板 · 降级兼容加载层 v1
 * 在页面任何脚本之前注入（documentStart）：
 *  1) 补齐旧 WebView 缺失的 API（polyfill）
 *  2) 关掉会让画面发灰/发黑的重量级视觉效果（backdrop-filter / blur）
 *  3) 页面 8 秒内没渲染出内容 → 降级为「原生风格应用网格」，至少能用
 */
(function () {
  if (window.__fnosCompat) return;
  window.__fnosCompat = 'v1';

  /* ---------- 1. polyfill ---------- */
  try {
    if (!Promise.prototype.finally) {
      Promise.prototype.finally = function (cb) {
        var P = this.constructor || Promise;
        return this.then(
          function (v) { return P.resolve(cb()).then(function () { return v; }); },
          function (e) { return P.resolve(cb()).then(function () { throw e; }); }
        );
      };
    }
    if (!Array.prototype.at) {
      Array.prototype.at = function (n) { n = Math.trunc(n) || 0; if (n < 0) n += this.length; return this[n]; };
    }
    if (!String.prototype.at) {
      String.prototype.at = function (n) { n = Math.trunc(n) || 0; if (n < 0) n += this.length; return this[n]; };
    }
    if (!Array.prototype.flat) {
      Array.prototype.flat = function (d) {
        d = d === undefined ? 1 : d;
        var out = [];
        (function walk(a, depth) {
          for (var i = 0; i < a.length; i++) {
            if (Array.isArray(a[i]) && depth > 0) walk(a[i], depth - 1); else out.push(a[i]);
          }
        })(this, d);
        return out;
      };
    }
    if (!Object.fromEntries) {
      Object.fromEntries = function (it) {
        var o = {};
        try { it.forEach(function (e) { o[e[0]] = e[1]; }); } catch (x) {}
        return o;
      };
    }
    if (!String.prototype.replaceAll) {
      String.prototype.replaceAll = function (a, b) { return this.split(a).join(b); };
    }
    if (!window.structuredClone) {
      window.structuredClone = function (o) { try { return JSON.parse(JSON.stringify(o)); } catch (e) { return o; } };
    }
    if (!window.requestIdleCallback) {
      window.requestIdleCallback = function (cb) { return setTimeout(function () { cb({ timeRemaining: function () { return 8; } }); }, 1); };
    }
    if (!window.IntersectionObserver) {
      window.IntersectionObserver = function (cb) {
        this.observe = function (el) { try { cb([{ target: el, isIntersecting: true, intersectionRatio: 1 }], this); } catch (e) {} };
        this.unobserve = function () {}; this.disconnect = function () {};
      };
    }
    if (!window.ResizeObserver) {
      window.ResizeObserver = function (cb) {
        this.observe = function (el) { try { cb([{ target: el, contentRect: el.getBoundingClientRect() }], this); } catch (e) {} };
        this.unobserve = function () {}; this.disconnect = function () {};
      };
    }
  } catch (e) {}

  /* ---------- 2. 视觉降级：干掉模糊/毛玻璃（旧 GPU 上会整块发灰或黑） ---------- */
  function injectCss() {
    try {
      var st = document.createElement('style');
      st.id = 'fnos-compat-css';
      st.textContent = [
        '*,*::before,*::after{-webkit-backdrop-filter:none!important;backdrop-filter:none!important;}',
        '.glass,.glass-panel,.blur,.backdrop-blur{background-color:rgba(20,22,28,.92)!important;filter:none!important;}',
        'canvas{background-color:transparent!important;}'
      ].join('\n');
      (document.head || document.documentElement).appendChild(st);
    } catch (e) {}
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', injectCss);
  else injectCss();

  /* ---------- 3. 兜底：8 秒还没内容就给一个能用的应用网格 ---------- */
  var APPS = [
    ['应用中心', 'trim.app-center', '🧩'], ['文件管理', 'trim.file', '📁'],
    ['影视', 'trim.media', '🎬'], ['相册', 'trim.photos', '🖼️'],
    ['音乐', 'trim.music', '🎵'], ['下载', 'trim.download', '⬇️'],
    ['Docker', 'trim.docker', '🐳'], ['虚拟机', 'trim.vm', '💻'],
    ['安全中心', 'trim.security', '🛡️'], ['资源管理', 'trim.monitor', '📊'],
    ['日志', 'trim.log', '📜'], ['备份', 'trim.backup', '💾'],
    ['DeepSeek Harness', 'fn-deepseek-harness', '✨'], ['XTE', 'trim.xte', '📡']
  ];

  function visibleCount() {
    try {
      var n = 0, all = document.querySelectorAll('*');
      for (var i = 0; i < all.length && i < 800; i++) {
        var r = all[i].getBoundingClientRect();
        if (r.width > 40 && r.height > 40 && r.top < innerHeight && r.bottom > 0) n++;
      }
      return n;
    } catch (e) { return -1; }
  }

  function fallbackGrid() {
    if (document.getElementById('fnos-compat-fallback')) return;
    var base = location.origin;
    var wrap = document.createElement('div');
    wrap.id = 'fnos-compat-fallback';
    wrap.style.cssText = 'position:fixed;inset:0;z-index:2147483000;overflow:auto;' +
      'background:linear-gradient(160deg,#0b1020,#131a2b 55%,#1b2436);padding:24px 20px;box-sizing:border-box;' +
      'font:14px/1.4 -apple-system,"PingFang SC",sans-serif;color:#e6edf3';
    var h = document.createElement('div');
    h.textContent = '兼容模式 · 页面未能完整加载';
    h.style.cssText = 'font-size:18px;font-weight:700;margin:0 0 6px';
    wrap.appendChild(h);
    var tip = document.createElement('div');
    tip.textContent = '已降级为简化桌面，应用仍可正常打开。路径 ' + location.pathname;
    tip.style.cssText = 'color:#8b98a5;font-size:12px;margin-bottom:20px';
    wrap.appendChild(tip);

    var grid = document.createElement('div');
    grid.style.cssText = 'display:grid;grid-template-columns:repeat(auto-fill,minmax(104px,1fr));gap:16px';
    APPS.forEach(function (a) {
      var it = document.createElement('a');
      it.href = base + '/appview?anchor=' + encodeURIComponent(a[1]);
      it.style.cssText = 'display:flex;flex-direction:column;align-items:center;gap:8px;text-decoration:none;color:#dbe4ee;' +
        'padding:14px 8px;border-radius:14px;background:rgba(255,255,255,.06)';
      var ic = document.createElement('div');
      ic.textContent = a[2];
      ic.style.cssText = 'font-size:30px;line-height:1';
      var nm = document.createElement('div');
      nm.textContent = a[0];
      nm.style.cssText = 'font-size:12px;text-align:center;word-break:break-all';
      it.appendChild(ic); it.appendChild(nm);
      grid.appendChild(it);
    });
    wrap.appendChild(grid);
    var btn = document.createElement('button');
    btn.textContent = '重新加载原页面';
    btn.style.cssText = 'margin-top:24px;padding:12px 22px;border:0;border-radius:10px;background:#4ea1ff;color:#04121f;font-weight:700';
    btn.onclick = function () { location.reload(); };
    wrap.appendChild(btn);
    (document.body || document.documentElement).appendChild(wrap);
  }

  function check() {
    try {
      // 只在「应用页」判定，登录页不处理
      if (/\/login/.test(location.pathname)) return;
      var n = visibleCount();
      if (n >= 0 && n < 25) {
        if (window.__fnosCompatLogged !== location.pathname) {
          window.__fnosCompatLogged = location.pathname;
          try { console.log('[fnos-compat] degraded path=' + location.pathname + ' visible=' + n); } catch (e) {}
        }
        fallbackGrid();
      }
    } catch (e) {}
  }
  setTimeout(check, 8000);
  setTimeout(check, 15000);
})();
