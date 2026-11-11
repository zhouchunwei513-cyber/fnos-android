/* global fnosSettings */
// 设置页：启动密码 + 快捷键自定义
(() => {
  const $ = (id) => document.getElementById(id);
  const clampInt = (v, min, max, dflt) => {
    const n = Math.round(Number(v));
    if (!isFinite(n)) return dflt;
    return Math.max(min, Math.min(max, n));
  };

  // 玻璃自定义下拉：统一处理 open/选中/取值，并暴露 value/disable 兼容旧调用
  function bindGlassSelect(el, onChange) {
    if (!el) return null;
    const valueEl = el.querySelector('.glass-select-value');
    const options = Array.from(el.querySelectorAll('.glass-select-option'));
    const api = {
      get value() { return el.getAttribute('data-value') || ''; },
      set value(v) {
        const val = String(v);
        el.setAttribute('data-value', val);
        const opt = options.find((o) => o.getAttribute('data-value') === val);
        if (valueEl) valueEl.textContent = opt ? opt.textContent : (options[0]?.textContent || '');
        options.forEach((o) => o.classList.toggle('selected', o.getAttribute('data-value') === val));
      },
      set disabled(v) {
        if (v) { el.setAttribute('aria-disabled', 'true'); el.style.opacity = '.5'; el.style.pointerEvents = 'none'; }
        else { el.removeAttribute('aria-disabled'); el.style.opacity = ''; el.style.pointerEvents = ''; }
      },
      addEventListener(_type, fn) { el._changeFn = fn; },
    };
    options.forEach((opt) => {
      opt.addEventListener('click', (e) => {
        e.stopPropagation();
        api.value = opt.getAttribute('data-value');
        el.classList.remove('open');
        if (typeof el._changeFn === 'function') el._changeFn({ target: api });
        if (typeof onChange === 'function') onChange(api.value);
      });
    });
    const toggle = (e) => {
      if (el.getAttribute('aria-disabled') === 'true') return;
      e.stopPropagation();
      document.querySelectorAll('.glass-select.open').forEach((s) => { if (s !== el) s.classList.remove('open'); });
      el.classList.toggle('open');
    };
    el.addEventListener('click', toggle);
    el.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(e); }
      else if (e.key === 'Escape') el.classList.remove('open');
    });
    document.addEventListener('click', () => el.classList.remove('open'));
    api.value = el.getAttribute('data-value') || options[0]?.getAttribute('data-value') || '';
    return api;
  }

  // v2.0.0：开机自启动 DOM
  const autostartToggle = $('autostart-toggle');
  const autostartText = $('autostart-text');
  const autostartHint = $('autostart-hint');
  // v4.0.48：保持登录开关
  const keepLoginToggle = $('keep-login-toggle');
  const keepLoginHint = $('keep-login-hint');

  const pwdForm = $('pwd-form');
  const oldPwd = $('old-pwd');
  const newPwd = $('new-pwd');
  const confirmPwd = $('confirm-pwd');
  const pwdError = $('pwd-error');
  const pwdStatus = $('pwd-status');
  const pwdCancel = $('pwd-cancel');

  const hkLock = $('hk-lock');
  const hkHide = $('hk-hide');
  const hkError = $('hk-error');
  const hkSave = $('hk-save');
  const hkReset = $('hk-reset');
  const btnClose = $('btn-close');

  const versionLine = $('version-line');

  // v1.16.1：无操作自动锁定
  const autoLockSel = bindGlassSelect(document.querySelector('[data-select="auto-lock"]'));
  const autoLockHint = $('auto-lock-hint');

  // v2.1.11：快捷方式打开应用后主程序后台化方式
  const shortcutHideSel = bindGlassSelect(document.querySelector('[data-select="shortcut-hide-mode"]'));

  const DEFAULTS = { lockApp: 'Ctrl+Alt+L', hideAll: 'Ctrl+Alt+H' };

  function updateAutoLockHint(hasPwd, mins) {
    if (!autoLockHint) return;
    if (!hasPwd) {
      autoLockHint.textContent = '请先设置启动密码后再开启自动锁定。';
      autoLockSel && (autoLockSel.disabled = true);
      return;
    }
    autoLockSel && (autoLockSel.disabled = false);
    if (!mins || mins <= 0) {
      autoLockHint.textContent = '已关闭。';
    } else {
      autoLockHint.textContent = `已开启：键鼠无操作 ${mins} 分钟后自动锁定，恢复时需输入启动密码。`;
    }
  }

  function showError(el, msg) {
    if (!el) return;
    if (!msg) { el.hidden = true; el.textContent = ''; return; }
    el.textContent = msg; el.hidden = false;
  }

  function setPwdStatus(has) {
    if (has) {
      pwdStatus.classList.remove('off');
      pwdStatus.innerHTML = '<span class="dot-ok"></span><span>当前已设置启动密码</span>';
      oldPwd.placeholder = '当前密码';
      $('old-wrap').style.display = '';
    } else {
      pwdStatus.classList.add('off');
      pwdStatus.innerHTML = '<span class="dot-ok"></span><span>当前未设置启动密码</span>';
      oldPwd.placeholder = '当前未设置，留空即可';
      $('old-wrap').style.display = 'none';
    }
  }

  // --------- 快捷键捕获 ---------
  const SPECIAL = {
    Control: 'Ctrl', Meta: 'Meta', Command: 'Command', Alt: 'Alt', Shift: 'Shift',
  };
  const NICE_KEY = {
    ' ': 'Space', '+': 'Plus', '-': 'Minus', ',': 'Comma', '.': 'Period',
    '/': 'Slash', '\\': 'Backslash', ';': 'Semicolon', "'": 'Quote',
    '[': 'BracketLeft', ']': 'BracketRight', '`': 'Backquote',
    ArrowUp: 'Up', ArrowDown: 'Down', ArrowLeft: 'Left', ArrowRight: 'Right',
  };

  function eventToAccelerator(e) {
    const parts = [];
    if (e.ctrlKey) parts.push('Ctrl');
    if (e.altKey) parts.push('Alt');
    if (e.shiftKey) parts.push('Shift');
    if (e.metaKey) parts.push('Meta');
    let key = e.key;
    if (!key) return '';
    if (SPECIAL[key]) return ''; // 单独按修饰键不完成
    if (/^F\d{1,2}$/.test(key)) {
      parts.push(key);
    } else if (/^[a-zA-Z]$/.test(key)) {
      parts.push(key.toUpperCase());
    } else if (/^[0-9]$/.test(key)) {
      parts.push(key);
    } else if (NICE_KEY[key]) {
      parts.push(NICE_KEY[key]);
    } else {
      return '';
    }
    return parts.join('+');
  }

  function bindHotkeyInput(input) {
    input.addEventListener('keydown', (e) => {
      e.preventDefault();
      e.stopPropagation();
      if (e.key === 'Escape') { input.blur(); input.classList.remove('recording'); return; }
      if (e.key === 'Backspace' || e.key === 'Delete') {
        input.value = '';
        input.classList.remove('recording');
        return;
      }
      if (!e.ctrlKey && !e.altKey && !e.metaKey && !e.shiftKey) {
        // 必须带修饰键（避免和网页输入冲突）
        return;
      }
      const acc = eventToAccelerator(e);
      if (acc) {
        input.value = acc;
        input.classList.remove('recording');
        showError(hkError, '');
      }
    });
    input.addEventListener('focus', () => {
      input.classList.add('recording');
      input.placeholder = '按下组合键（Esc 取消）';
    });
    input.addEventListener('blur', () => {
      input.classList.remove('recording');
      input.placeholder = '点击此处并按下组合键';
    });
    input.addEventListener('click', () => input.focus());
  }

  bindHotkeyInput(hkLock);
  bindHotkeyInput(hkHide);

  document.querySelectorAll('.hotkey-clear').forEach((btn) => {
    btn.addEventListener('click', () => {
      const t = $(btn.dataset.target);
      if (t) t.value = '';
      showError(hkError, '');
    });
  });

  // --------- 密码保存 ---------
  pwdForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    const oldV = oldPwd.value || '';
    const newV = newPwd.value || '';
    const confirmV = confirmPwd.value || '';
    if (newV && newV.length < 4) { showError(pwdError, '新密码至少 4 位'); return; }
    if (newV !== confirmV) { showError(pwdError, '两次输入的新密码不一致'); return; }
    showError(pwdError, '');
    const btn = $('pwd-save');
    btn.disabled = true; btn.textContent = '保 存 中';
    try {
      const res = await fnosSettings.setPassword({ oldPassword: oldV, newPassword: newV });
      if (res && res.ok) {
        oldPwd.value = newPwd.value = confirmPwd.value = '';
        const info = await fnosSettings.getSettings();
        setPwdStatus(!!info?.hasPassword);
        if (autoLockSel) updateAutoLockHint(!!info?.hasPassword, Number(autoLockSel.value) || 0);
        btn.textContent = '已 保 存';
        setTimeout(() => { btn.textContent = '保存密码'; }, 1400);
      } else {
        showError(pwdError, (res && res.error) || '保存失败');
      }
    } catch (err) {
      showError(pwdError, err?.message || '保存失败');
    } finally {
      btn.disabled = false;
      if (btn.textContent === '保 存 中') btn.textContent = '保存密码';
    }
  });

  pwdCancel.addEventListener('click', () => {
    oldPwd.value = newPwd.value = confirmPwd.value = '';
    showError(pwdError, '');
  });

  // --------- 快捷键保存 ---------
  hkSave.addEventListener('click', async () => {
    const lockAcc = (hkLock.value || '').trim();
    const hideAcc = (hkHide.value || '').trim();
    if (lockAcc && hideAcc && lockAcc === hideAcc) {
      showError(hkError, '两个快捷键不能相同'); return;
    }
    showError(hkError, '');
    hkSave.disabled = true; hkSave.textContent = '保 存 中';
    try {
      const res = await fnosSettings.setShortcuts({ lockApp: lockAcc, hideAll: hideAcc });
      if (res && res.ok) {
        hkSave.textContent = '已 保 存';
        setTimeout(() => { hkSave.textContent = '保存快捷键'; }, 1400);
      } else {
        showError(hkError, (res && res.error) || '保存失败');
      }
    } catch (err) {
      showError(hkError, err?.message || '保存失败');
    } finally {
      hkSave.disabled = false;
      if (hkSave.textContent === '保 存 中') hkSave.textContent = '保存快捷键';
    }
  });

  hkReset.addEventListener('click', () => {
    hkLock.value = DEFAULTS.lockApp;
    hkHide.value = DEFAULTS.hideAll;
    showError(hkError, '');
  });

  // --------- URL 重写 ---------
  const rwList = document.getElementById('rewrite-list');
  const rwAdd = document.getElementById('rw-add');
  const rwSave = document.getElementById('rw-save');
  const rwError = document.getElementById('rw-error');
  // v1.69.0：完整 HTML 转义（原实现只转义双引号，地址含 & < > 等字符时会把
  // value 属性截断/渲染错乱，截图里"外网地址末尾乱码 ʂ"即由此类字符污染导致）
  const escAttr = (s) => String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;')
    .replace(/>/g, '&gt;').replace(/'/g, '&#39;');
  // 只保留可打印 ASCII（URL 合法字符），过滤控制符与不可见/异常 Unicode（乱码来源）
  const cleanUrlInput = (s) => String(s == null ? '' : s).replace(/[^\x20-\x7E]/g, '');
  const rwTpl = (m = '', r = '') => {
    const row = document.createElement('div');
    row.className = 'rewrite-row';
    row.innerHTML = `
      <div class="input-wrap glass-input"><input class="rw-match" placeholder="内网端口或路径，例如 5667 或 /movie/" value="${escAttr(cleanUrlInput(m))}" spellcheck="false"/></div>
      <div class="rewrite-arrow">→</div>
      <div class="input-wrap glass-input"><input class="rw-replace" placeholder="外网完整地址，例如 https://nas.example.com:5667/" value="${escAttr(cleanUrlInput(r))}" spellcheck="false"/></div>
      <button type="button" class="rewrite-del" title="删除">×</button>`;
    row.querySelector('.rewrite-del').addEventListener('click', () => row.remove());
    rwList.appendChild(row);
  };
  rwAdd.addEventListener('click', () => rwTpl());
  rwSave.addEventListener('click', async () => {
    const rows = [...rwList.querySelectorAll('.rewrite-row')];
    const list = [];
    for (const row of rows) {
      const m = cleanUrlInput(row.querySelector('.rw-match').value).trim();
      const r = cleanUrlInput(row.querySelector('.rw-replace').value).trim();
      if (!m && !r) continue;
      if (!m || !r) { showError(rwError, '规则的左右两侧都要填'); return; }
      try { new URL(r); } catch { showError(rwError, `右侧不是有效的完整地址：${r}`); return; }
      list.push({ match: m, replace: r });
    }
    rwSave.disabled = true; rwSave.textContent = '保 存 中';
    try {
      const res = await fnosSettings.setUrlRewrites(list);
      if (res && res.ok) {
        rwSave.textContent = '已 保 存';
        setTimeout(() => { rwSave.textContent = '保存规则'; }, 1400);
      } else {
        showError(rwError, (res && res.error) || '保存失败');
      }
    } catch (err) {
      showError(rwError, err?.message || '保存失败');
    } finally {
      rwSave.disabled = false;
      if (rwSave.textContent === '保 存 中') rwSave.textContent = '保存规则';
    }
  });

  // --------- 界面开关 ---------
  // v1.48.0：窗口已改为无边框 + 自定义标题栏，无系统菜单栏，"自动隐藏菜单栏"选项已移除。
  // v1.52.0：标题栏自动隐藏开关
  const optTbAutoHide = document.getElementById('opt-titlebar-autohide');
  const optTbLabel = document.getElementById('opt-titlebar-autohide-label');
  const syncTbLabel = () => { if (optTbLabel) optTbLabel.textContent = optTbAutoHide && optTbAutoHide.checked ? '已开启（悬停顶部/按ALT调出）' : '已关闭（标题栏常驻）'; };
  if (optTbAutoHide) {
    optTbAutoHide.addEventListener('change', syncTbLabel);
  }
  // v3.0.23：隐藏账号悬浮球（有用户反馈球挡操作）
  const optHideBall = document.getElementById('opt-hide-account-ball');
  const optHideBallLabel = document.getElementById('opt-hide-account-ball-label');
  const syncHideBallLabel = () => {
    if (optHideBallLabel) optHideBallLabel.textContent = (optHideBall && optHideBall.checked) ? '已隐藏' : '已显示';
  };
  if (optHideBall) optHideBall.addEventListener('change', syncHideBallLabel);

  // v3.0.76（用户要求：删除深色模式，改成调透明度）——
  // 「透明界面」由开关改为百分比滑块：0% = 深色实底（原深色模式）、100% = 完全透明。
  // 拖动时只做预览（本页立即变 + 广播给其它窗口），点「保存界面设置」才落盘生效。
  const optUiAlpha = document.getElementById('opt-ui-alpha');
  const optUiAlphaVal = document.getElementById('opt-ui-alpha-val');
  let uiAlphaPct = 62;
  const syncUiAlphaLabel = () => {
    if (optUiAlphaVal) optUiAlphaVal.textContent = String(uiAlphaPct) + '%';
  };
  const applyUiAlphaLocal = () => {
    try {
      const el = document.documentElement;
      el.style.setProperty('--fnos-ui-a', String((100 - uiAlphaPct) / 100));
      el.classList.toggle('fnos-ui-transparent', uiAlphaPct > 0);
    } catch (_) {}
  };

  // v3.0.70：标题栏四角 / 窗口四角（圆角 / 直角，可分别设置；保存时生效）
  const barCornerGroup = document.querySelector('[data-bar-corner]');
  const winCornerGroup = document.querySelector('[data-win-corner]');
  // v4.0.39：五档圆角（小/大圆角、小/大半圆角、直角）。旧值 round/half 迁移为「大」档，保持原观感。
  const __cornerNorm = (v) => {
    if (v === 'round-small' || v === 'round-large' || v === 'half-small' || v === 'half-large' || v === 'square') return v;
    if (v === 'round') return 'round-large';
    if (v === 'half') return 'half-large';
    return 'round-large';
  };
  const tbCorner = { bar: 'round-large', win: 'round-large' };
  const syncCornerBtns = () => {
    if (barCornerGroup) {
      barCornerGroup.querySelectorAll('.tb-mat-btn').forEach((b) => {
        b.classList.toggle('active', b.getAttribute('data-corner') === tbCorner.bar);
      });
    }
    if (winCornerGroup) {
      winCornerGroup.querySelectorAll('.tb-mat-btn').forEach((b) => {
        b.classList.toggle('active', b.getAttribute('data-corner') === tbCorner.win);
      });
    }
  };
  if (barCornerGroup) {
    barCornerGroup.addEventListener('click', (e) => {
      const btn = e.target && e.target.closest ? e.target.closest('.tb-mat-btn') : null;
      if (!btn) return;
      // v3.0.74：新增 half（半圆角）——上两角圆、下两角直角
      const __bcv = btn.getAttribute('data-corner');
      tbCorner.bar = __cornerNorm(__bcv);
      syncCornerBtns();
      tbPreview(); // v3.0.71：点选后立即广播预览，标题栏四角即时生效（不必等保存）
    });
  }
  if (winCornerGroup) {
    winCornerGroup.addEventListener('click', (e) => {
      const btn = e.target && e.target.closest ? e.target.closest('.tb-mat-btn') : null;
      if (!btn) return;
      const __wcv = btn.getAttribute('data-corner');
      tbCorner.win = __cornerNorm(__wcv);
      syncCornerBtns();
      tbPreview(); // v3.0.71：点选后立即广播预览，窗口四角即时生效（不必等保存）
    });
  }

  // v1.58：标题栏材质 / 不透明度 / 磨砂程度 / 颜色
  // v2.17.24：磨砂材质修复（真毛玻璃 + 滑块生效）、颜色改为标准取色器（拾色面板/十六进制/Alpha）
  // v2.19.0：material = 纯色/磨砂玻璃，blur = 磨砂强度
  // v3.0.24：默认值与主进程一致（新装默认 96% 不透明 + 深色，避免标题栏看不见）
// v3.0.78（用户方案）：磨砂 / 纯色两套视觉参数各自独立、互不共用，切换后各自记住自己的值
  const tbState = {
    opacity: 96, color: '#101426', material: 'solid', blur: 20, highlight: 55,
    frostColor: '#FFFFFF', frostAlpha: 10,   // 磨砂：染色 + 玻璃浓度 2~35
    solidColor: '#101426', solidAlpha: 100,  // 纯色：实色 + 浓度 90~100
    divider: true,
  };
  const tbOpacity = document.getElementById('tb-opacity');
  const tbOpacityVal = document.getElementById('tb-opacity-val');
  const tbColorInput = document.getElementById('tb-color-input');
  const tbColorHex = document.getElementById('tb-color-hex');
  const tbColorPreview = document.getElementById('tb-color-preview');
  const tbAlpha = document.getElementById('tb-alpha');
  const tbAlphaVal = document.getElementById('tb-alpha-val');
  const tbColorHint = document.getElementById('tb-color-hint');
  // v2.19.0：材质按钮组 / 磨砂强度
  const tbMatGroup = document.querySelector('[data-tb-material]');
  const tbMaterialHint = document.getElementById('tb-material-hint');
  const tbBlur = document.getElementById('tb-blur');
  const tbBlurVal = document.getElementById('tb-blur-val');
  const tbBlurRow = document.getElementById('tb-blur-row');
  // v2.19.2：磨砂高光强度
  const tbHl = document.getElementById('tb-hl');
  const tbHlVal = document.getElementById('tb-hl-val');
  const tbHlRow = document.getElementById('tb-hl-row');
  // v3.0.78：动态文案 / 分隔线开关
  const tbStrengthTitle = document.getElementById('tb-strength-title');
  const tbStrengthSub = document.getElementById('tb-strength-sub');
  const tbColorTitle = document.getElementById('tb-color-title');
  const tbDivider = document.getElementById('tb-divider');
  const tbDividerRow = document.getElementById('tb-divider-row');

  // #RGB / #RRGGBB / #RRGGBBAA 都收，统一归一成 #RRGGBB（Alpha 交给不透明度滑块，避免两个口径打架）
  const hexOk = (h) => /^#?([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/.test(String(h || '').trim());
  const hexNorm6 = (h) => {
    let t = String(h || '').trim();
    if (t.charAt(0) !== '#') t = '#' + t;
    if (!hexOk(t)) return '';
    let body = t.slice(1);
    if (body.length === 3) body = body.split('').map((c) => c + c).join('');
    if (body.length === 8) body = body.slice(0, 6);
    return '#' + body.toUpperCase();
  };

  // 任何一项改动都立刻广播到所有窗口做实时预览（不落盘；点「保存界面设置」才持久化）
  let tbPreviewTimer = null;
  const tbPreview = () => {
    if (tbPreviewTimer) return;
    tbPreviewTimer = setTimeout(() => {
      tbPreviewTimer = null;
      try {
        fnosSettings.previewTitleBar({
          titleBarOpacity: tbState.opacity,
          titleBarColor: tbState.color,
          titleBarMaterial: tbState.material,
          titleBarBlur: tbState.blur,
          titleBarHighlight: tbState.highlight,
          // v3.0.78：磨砂 / 纯色两套参数（各自独立记忆）
          titleBarFrostColor: tbState.frostColor,
          titleBarFrostAlpha: tbState.frostAlpha,
          titleBarSolidColor: tbState.solidColor,
          titleBarSolidAlpha: tbState.solidAlpha,
          titleBarDivider: !!tbState.divider,
          // v3.0.71：四角随预览一起广播 —— v3.0.70 只改本地选中态、从不发预览，页面永远收不到
          barCorner: tbCorner.bar,
          winCorner: tbCorner.win,
          // v3.0.76：透明度滑块拖动即时预览（不落盘）
          uiTransparentAlpha: uiAlphaPct,
          // v4.0.31（用户需求 2）：悬浮球外观也走预览通道（跟随/单独设置 + 材质/颜色/浓度）
          ballAppearanceMode: ballState.mode,
          ballMaterial: ballState.material,
          ballSolidColor: ballState.solidColor,
          ballSolidAlpha: ballState.solidAlpha,
          ballFrostColor: ballState.frostColor,
          ballFrostAlpha: ballState.frostAlpha,
        });
      } catch (_) {}
    }, 40);
  };

  const syncTbMaterialBtns = () => {
    if (tbColorHint) tbColorHint.textContent = '标题栏底色';
    // v2.19.0：材质按钮选中态 + 磨砂强度行的启用状态
    if (tbMatGroup) {
      tbMatGroup.querySelectorAll('.tb-mat-btn').forEach((b) => {
        b.classList.toggle('active', b.getAttribute('data-material') === tbState.material);
      });
    }
    if (tbMaterialHint) {
      tbMaterialHint.textContent = tbState.material === 'frosted'
        ? '磨砂玻璃：透出桌面/背后窗口并模糊，只叠很淡的染色 + 1px 内高光（Windows 原生材质与 20px 圆角不可兼得，这里用实时采样实现）'
        : '纯色：不透明实色，背后什么都不透，只有底色 +（可选）1px 分隔线';
    }
    const frosted = tbState.material === 'frosted';
    // v3.0.78：强度行按材质切换标签 / 取值范围 / 当前值（两套值各自记忆）
    if (tbStrengthTitle) tbStrengthTitle.textContent = frosted ? '玻璃浓度' : '纯色浓度';
    if (tbStrengthSub) {
      tbStrengthSub.textContent = frosted
        ? '染色叠加强度 2%~35%，越低越通透（模糊半径由系统固定，不可调）'
        : '实心色浓度 90%~100%，纯色不透明、不露背后内容';
    }
    if (tbColorTitle) tbColorTitle.textContent = frosted ? '玻璃染色' : '纯色颜色';
    if (tbColorHint) tbColorHint.textContent = frosted ? '磨砂玻璃的染色（很淡地叠在模糊背景上）' : '纯色标题栏的实心颜色';
    if (tbDividerRow) tbDividerRow.style.display = frosted ? 'none' : 'flex';
  };
  const syncTbSliders = () => {
    // v3.0.78：同一个滑杆位，磨砂走 2~35（玻璃浓度），纯色走 90~100（纯色浓度）
    const frosted = tbState.material === 'frosted';
    const v = frosted ? tbState.frostAlpha : tbState.solidAlpha;
    if (tbOpacity) {
      tbOpacity.min = frosted ? '2' : '90';
      tbOpacity.max = frosted ? '35' : '100';
      tbOpacity.value = String(v);
    }
    if (tbOpacityVal) tbOpacityVal.textContent = v + '%';
    // 颜色 alpha 滑块已隐藏（磨砂/纯色浓度各自独立，不再共用颜色 alpha 口径）
    if (tbAlpha) tbAlpha.value = String(tbState.opacity);
    if (tbAlphaVal) tbAlphaVal.textContent = tbState.opacity + '%';
  };
  const tbCurColor = () => (tbState.material === 'frosted' ? tbState.frostColor : tbState.solidColor);
  const syncTbColor = () => {
    const c = tbCurColor();
    if (tbColorInput) tbColorInput.value = c;
    if (tbColorHex && document.activeElement !== tbColorHex) tbColorHex.value = c;
    if (tbColorPreview) tbColorPreview.style.background = c;
  };
  const setTbColor = (c) => {
    const n = hexNorm6(c);
    if (!n) return false;
    if (tbState.material === 'frosted') tbState.frostColor = n; else tbState.solidColor = n;
    tbState.color = n;
    syncTbColor();
    return true;
  };
  // v3.0.78：强度滑杆写入按材质分流（磨砂 → frostAlpha，纯色 → solidAlpha）
  const setTbOpacity = (v) => {
    if (tbState.material === 'frosted') {
      tbState.frostAlpha = clampInt(v, 2, 35, 10);
    } else {
      tbState.solidAlpha = clampInt(v, 90, 100, 100);
      tbState.opacity = tbState.solidAlpha;   // 旧字段兼容
    }
    syncTbSliders();
  };
  // v2.19.0：材质 / 磨砂强度
  const setTbMaterial = (m) => {
    tbState.material = (m === 'frosted') ? 'frosted' : 'solid';
    syncTbMaterialBtns();
    syncTbSliders();
    syncTbColor();
  };
  const setTbBlur = (v) => {
    tbState.blur = clampInt(v, 0, 40, 20);
    if (tbBlur) tbBlur.value = String(tbState.blur);
    if (tbBlurVal) tbBlurVal.textContent = tbState.blur + 'px';
  };
  const setTbHighlight = (v) => {
    tbState.highlight = clampInt(v, 0, 100, 55);
    if (tbHl) tbHl.value = String(tbState.highlight);
    if (tbHlVal) tbHlVal.textContent = String(tbState.highlight);
  };

  if (tbOpacity) tbOpacity.addEventListener('input', () => { setTbOpacity(tbOpacity.value); tbPreview(); });
  if (tbAlpha) tbAlpha.addEventListener('input', () => { setTbOpacity(tbAlpha.value); tbPreview(); });
  if (tbColorInput) tbColorInput.addEventListener('input', () => { if (setTbColor(tbColorInput.value)) tbPreview(); });
  if (tbColorHex) {
    tbColorHex.addEventListener('input', () => { if (setTbColor(tbColorHex.value)) tbPreview(); });
    tbColorHex.addEventListener('blur', () => { if (!hexOk(tbColorHex.value)) tbColorHex.value = tbState.color; });
  }
  // v2.19.0：材质切换 / 磨砂强度拖动 → 立即预览
  if (tbMatGroup) {
    tbMatGroup.addEventListener('click', (e) => {
      const btn = e.target && e.target.closest ? e.target.closest('.tb-mat-btn') : null;
      if (!btn) return;
      setTbMaterial(btn.getAttribute('data-material'));
      tbPreview();
    });
  }
  // v3.0.78：分隔线开关（纯色效果）
  if (tbDivider) tbDivider.addEventListener('change', () => { tbState.divider = !!tbDivider.checked; tbPreview(); });
  if (tbBlur) tbBlur.addEventListener('input', () => { setTbBlur(tbBlur.value); tbPreview(); });
  if (tbHl) tbHl.addEventListener('input', () => { setTbHighlight(tbHl.value); tbPreview(); });
  // v3.0.76：拖动透明界面滑块 —— 本页立刻变 + 广播给其它窗口预览，松手不落盘，点保存才生效
  if (optUiAlpha) {
    optUiAlpha.addEventListener('input', () => {
      uiAlphaPct = Math.max(0, Math.min(100, Math.round(Number(optUiAlpha.value) || 0)));
      syncUiAlphaLabel();
      applyUiAlphaLocal();
      tbPreview();
    });
  }

  // ===== v4.0.31（用户需求 2）：悬浮球外观 —— 跟随标题栏 / 单独设置 =====
  const ballState = { mode: 'follow', material: 'solid', solidColor: '#101426', solidAlpha: 100, frostColor: '#FFFFFF', frostAlpha: 14 };
  const ballModeGroup = document.querySelector('[data-ball-mode]');
  const ballMatGroup = document.querySelector('[data-ball-mat]');
  const ballCustomPanel = document.getElementById('ball-custom-panel');
  const ballSolidRow = document.getElementById('ball-solid-row');
  const ballSolidAlphaRow = document.getElementById('ball-solid-alpha-row');
  const ballFrostRow = document.getElementById('ball-frost-row');
  const ballFrostAlphaRow = document.getElementById('ball-frost-alpha-row');
  const ballSolidColorEl = document.getElementById('ball-solid-color');
  const ballSolidHexEl = document.getElementById('ball-solid-hex');
  const ballSolidAlphaEl = document.getElementById('ball-solid-alpha');
  const ballSolidAlphaValEl = document.getElementById('ball-solid-alpha-val');
  const ballFrostColorEl = document.getElementById('ball-frost-color');
  const ballFrostHexEl = document.getElementById('ball-frost-hex');
  const ballFrostAlphaEl = document.getElementById('ball-frost-alpha');
  const ballFrostAlphaValEl = document.getElementById('ball-frost-alpha-val');

  const syncBallUI = () => {
    const custom = ballState.mode === 'custom';
    if (ballCustomPanel) ballCustomPanel.style.display = custom ? '' : 'none';
    if (ballModeGroup) ballModeGroup.querySelectorAll('.tb-mat-btn').forEach((b) => b.classList.toggle('active', b.getAttribute('data-ballmode') === ballState.mode));
    if (ballMatGroup) ballMatGroup.querySelectorAll('.tb-mat-btn').forEach((b) => b.classList.toggle('active', b.getAttribute('data-ballmat') === ballState.material));
    const frosted = ballState.material === 'frosted';
    if (ballSolidRow) ballSolidRow.style.display = frosted ? 'none' : '';
    if (ballSolidAlphaRow) ballSolidAlphaRow.style.display = frosted ? 'none' : '';
    if (ballFrostRow) ballFrostRow.style.display = frosted ? '' : 'none';
    if (ballFrostAlphaRow) ballFrostAlphaRow.style.display = frosted ? '' : 'none';
    if (ballSolidColorEl) ballSolidColorEl.value = ballState.solidColor;
    if (ballSolidHexEl && document.activeElement !== ballSolidHexEl) ballSolidHexEl.value = ballState.solidColor;
    if (ballSolidAlphaEl) ballSolidAlphaEl.value = String(ballState.solidAlpha);
    if (ballSolidAlphaValEl) ballSolidAlphaValEl.textContent = ballState.solidAlpha + '%';
    if (ballFrostColorEl) ballFrostColorEl.value = ballState.frostColor;
    if (ballFrostHexEl && document.activeElement !== ballFrostHexEl) ballFrostHexEl.value = ballState.frostColor;
    if (ballFrostAlphaEl) ballFrostAlphaEl.value = String(ballState.frostAlpha);
    if (ballFrostAlphaValEl) ballFrostAlphaValEl.textContent = ballState.frostAlpha + '%';
  };

  if (ballModeGroup) {
    ballModeGroup.addEventListener('click', (e) => {
      const btn = e.target.closest('.tb-mat-btn');
      if (!btn) return;
      ballState.mode = (btn.getAttribute('data-ballmode') === 'custom') ? 'custom' : 'follow';
      syncBallUI();
      tbPreview();
    });
  }
  if (ballMatGroup) {
    ballMatGroup.addEventListener('click', (e) => {
      const btn = e.target.closest('.tb-mat-btn');
      if (!btn) return;
      ballState.material = (btn.getAttribute('data-ballmat') === 'frosted') ? 'frosted' : 'solid';
      syncBallUI();
      tbPreview();
    });
  }
  if (ballSolidColorEl) ballSolidColorEl.addEventListener('input', () => {
    const h = hexNorm6(ballSolidColorEl.value);
    if (h) { ballState.solidColor = h; syncBallUI(); tbPreview(); }
  });
  if (ballSolidHexEl) ballSolidHexEl.addEventListener('change', () => {
    const h = hexNorm6(ballSolidHexEl.value);
    if (h) { ballState.solidColor = h; syncBallUI(); tbPreview(); } else { syncBallUI(); }
  });
  if (ballSolidAlphaEl) ballSolidAlphaEl.addEventListener('input', () => {
    ballState.solidAlpha = clampInt(ballSolidAlphaEl.value, 40, 100, 100);
    syncBallUI(); tbPreview();
  });
  if (ballFrostColorEl) ballFrostColorEl.addEventListener('input', () => {
    const h = hexNorm6(ballFrostColorEl.value);
    if (h) { ballState.frostColor = h; syncBallUI(); tbPreview(); }
  });
  if (ballFrostHexEl) ballFrostHexEl.addEventListener('change', () => {
    const h = hexNorm6(ballFrostHexEl.value);
    if (h) { ballState.frostColor = h; syncBallUI(); tbPreview(); } else { syncBallUI(); }
  });
  if (ballFrostAlphaEl) ballFrostAlphaEl.addEventListener('input', () => {
    ballState.frostAlpha = clampInt(ballFrostAlphaEl.value, 2, 60, 14);
    syncBallUI(); tbPreview();
  });
  syncBallUI();

  // 载入当前设置填充界面
  fnosSettings.getSettings().then((info) => {
    try {
      if (!info) return;
      if (optTbAutoHide) { optTbAutoHide.checked = info.titleBarAutoHide === true; syncTbLabel(); }
      if (optHideBall) { optHideBall.checked = info.hideAccountBall === true; syncHideBallLabel(); }
      if (keepLoginToggle) keepLoginToggle.checked = info.keepLogin !== false;
      uiAlphaPct = (info.uiTransparentAlpha != null && Number.isFinite(Number(info.uiTransparentAlpha)))
        ? Math.max(0, Math.min(100, Math.round(Number(info.uiTransparentAlpha))))
        : 62;
      if (optUiAlpha) { optUiAlpha.value = String(uiAlphaPct); syncUiAlphaLabel(); applyUiAlphaLocal(); }
      tbCorner.bar = __cornerNorm(info.barCorner);
      tbCorner.win = __cornerNorm(info.winCorner);
      syncCornerBtns();
      tbState.opacity = clampInt(info.titleBarOpacity, 0, 100, 96);
      tbState.color = hexNorm6(info.titleBarColor) || '#101426';
      tbState.material = info.titleBarMaterial === 'frosted' ? 'frosted' : 'solid';
      // v3.0.78：两套参数各自读自己的值（老配置从共享字段折算，观感不断层）
      tbState.frostColor = hexNorm6(info.titleBarFrostColor) || '#FFFFFF';
      tbState.frostAlpha = (info.titleBarFrostAlpha != null)
        ? clampInt(info.titleBarFrostAlpha, 2, 35, 10)
        : clampInt(Math.round(tbState.opacity * 0.35 / 100 * 100) / 10 + 4, 2, 35, 10);
      tbState.solidColor = hexNorm6(info.titleBarSolidColor) || hexNorm6(info.titleBarColor) || '#101426';
      tbState.solidAlpha = (info.titleBarSolidAlpha != null)
        ? clampInt(info.titleBarSolidAlpha, 90, 100, 100)
        : clampInt(tbState.opacity, 90, 100, 100);
      tbState.divider = info.titleBarDivider === undefined ? true : !!info.titleBarDivider;
      if (tbDivider) tbDivider.checked = !!tbState.divider;
      tbState.blur = clampInt(info.titleBarBlur, 0, 40, 20);
      if (tbBlur) tbBlur.value = String(tbState.blur);
      tbState.highlight = clampInt(info.titleBarHighlight, 0, 100, 55);
      if (tbHl) tbHl.value = String(tbState.highlight);
      syncTbMaterialBtns(); syncTbSliders(); syncTbColor();
      // v4.0.31（用户需求 2）：悬浮球外观回填
      ballState.mode = info.ballAppearanceMode === 'custom' ? 'custom' : 'follow';
      ballState.material = info.ballMaterial === 'frosted' ? 'frosted' : 'solid';
      ballState.solidColor = hexNorm6(info.ballSolidColor) || '#101426';
      ballState.solidAlpha = clampInt(info.ballSolidAlpha, 40, 100, 100);
      ballState.frostColor = hexNorm6(info.ballFrostColor) || '#FFFFFF';
      ballState.frostAlpha = clampInt(info.ballFrostAlpha, 2, 60, 14);
      syncBallUI();
    } catch (_) {}
  }).catch(() => {});
  const optSave = document.getElementById('opt-save');
  if (optSave) {
    optSave.addEventListener('click', async () => {
      optSave.disabled = true; optSave.textContent = '保 存 中';
      try {
        const res = await fnosSettings.setUIOptions({
          titleBarAutoHide: !!(optTbAutoHide && optTbAutoHide.checked),
          titleBarOpacity: tbState.opacity,
          titleBarColor: tbState.color,
          titleBarMaterial: tbState.material,
          titleBarBlur: tbState.blur,
          titleBarHighlight: tbState.highlight,
          // v3.0.78：磨砂 / 纯色两套参数分别落盘
          titleBarFrostColor: tbState.frostColor,
          titleBarFrostAlpha: tbState.frostAlpha,
          titleBarSolidColor: tbState.solidColor,
          titleBarSolidAlpha: tbState.solidAlpha,
          titleBarDivider: !!tbState.divider,
          hideAccountBall: !!(optHideBall && optHideBall.checked),
          // v4.0.31（用户需求 2）：悬浮球外观落盘
          ballAppearanceMode: ballState.mode,
          ballMaterial: ballState.material,
          ballSolidColor: ballState.solidColor,
          ballSolidAlpha: ballState.solidAlpha,
          ballFrostColor: ballState.frostColor,
          ballFrostAlpha: ballState.frostAlpha,
          // v3.0.76：透明界面以百分比落盘（0 = 深色实底）
          uiTransparentAlpha: uiAlphaPct,
          barCorner: tbCorner.bar,
          winCorner: tbCorner.win,
        });
        if (res && res.ok) {
          optSave.textContent = '已 保 存（即时生效）';
          setTimeout(() => { optSave.textContent = '保存界面设置'; }, 1800);
        } else {
          // v3.0.71：保存失败必须看得见。v3.0.70 四角字段被 preload 白名单吞掉时按钮静默还原，
          // 用户看到的就是「点了保存没反应、设置也没存」。
          optSave.textContent = '保存失败，请重试';
          setTimeout(() => { optSave.textContent = '保存界面设置'; }, 2200);
        }
      } catch (_) {
        optSave.textContent = '保存失败，请重试';
        setTimeout(() => { optSave.textContent = '保存界面设置'; }, 2200);
      } finally {
        optSave.disabled = false;
        if (optSave.textContent === '保 存 中') optSave.textContent = '保存界面设置';
      }
    });
  }

  // v3.0.19：标题栏皮肤 —— 列出已安装皮肤、选择 activeSkin（即时应用）、安装 .fskin.zip
  const skinList = document.getElementById('skin-list');
  const skinLoading = document.getElementById('skin-loading');
  const skinInstall = document.getElementById('skin-install');
  const skinStatus = document.getElementById('skin-status');
  const __skinMsg = (m) => { if (skinStatus) skinStatus.textContent = m || ''; };
  const __escHtml = (v) => String(v || '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  function renderSkinList(skins, activeSkin) {
    if (!skinList) return;
    const active = String(activeSkin || '').trim();
    skinList.innerHTML = '';
    const defCard = document.createElement('div');
    defCard.className = 'skin-card skin-default' + (active ? '' : ' active');
    defCard.innerHTML = '<div class="skin-thumb"><span class="skin-thumb-ph">默认</span></div>' +
      '<div class="skin-meta"><div class="skin-name">原生标题栏</div><div class="skin-ver">无皮肤</div></div>';
    defCard.addEventListener('click', () => __applySkin(''));
    skinList.appendChild(defCard);
    (skins || []).forEach((sk) => {
      const card = document.createElement('div');
      card.className = 'skin-card' + (sk && sk.name === active ? ' active' : '');
      const label = __escHtml((sk && sk.manifest && sk.manifest.name) || (sk && sk.name));
      const ver = __escHtml(sk && sk.manifest && sk.manifest.version);
      const thumb = (sk && sk.preview)
        ? '<img src="' + sk.preview + '" alt="" />'
        : '<span class="skin-thumb-ph">' + label + '</span>';
      card.innerHTML = '<div class="skin-thumb">' + thumb + '</div>' +
        '<div class="skin-meta"><div class="skin-name">' + label + '</div><div class="skin-ver">' + ver + '</div></div>' +
        '<button type="button" class="skin-del" title="删除该皮肤" aria-label="删除">×</button>';
      card.addEventListener('click', () => __applySkin(sk.name));
      const delBtn = card.querySelector('.skin-del');
      if (delBtn) {
        delBtn.addEventListener('click', (ev) => {
          ev.stopPropagation();
          __deleteSkin(sk.name);
        });
      }
      skinList.appendChild(card);
    });
  }

  async function __applySkin(name) {
    __skinMsg('应用中…');
    try {
      const res = await fnosSettings.setUIOptions({ activeSkin: name });
      if (res && res.ok) {
        __skinMsg('已应用' + (name ? '：' + name : '默认皮肤'));
        setTimeout(() => __skinMsg(''), 1800);
        await loadSkins();
      } else {
        __skinMsg((res && res.error) || '应用失败');
      }
    } catch (err) {
      __skinMsg(err?.message || '应用失败');
    }
  }

  // v3.0.38：删除已安装皮肤（确认后调用主进程删除目录，删除激活皮肤会回落默认）
  async function __deleteSkin(name) {
    if (!name) return;
    if (!window.confirm('确定删除皮肤「' + name + '」吗？此操作不可恢复。')) return;
    __skinMsg('删除中…');
    try {
      const res = await fnosSettings.deleteSkin(name);
      if (res && res.ok) {
        __skinMsg('已删除：' + name);
        setTimeout(() => __skinMsg(''), 1800);
        await loadSkins();
      } else {
        __skinMsg((res && res.error) || '删除失败');
      }
    } catch (err) {
      __skinMsg(err?.message || '删除失败');
    }
  }

  async function loadSkins() {
    try {
      const res = await fnosSettings.listSkins();
      if (res && res.ok) {
        renderSkinList(res.skins, res.activeSkin);
      } else if (skinLoading) {
        skinLoading.textContent = (res && res.error) || '加载失败';
      }
    } catch (err) {
      if (skinLoading) skinLoading.textContent = err?.message || '加载失败';
    }
  }

  if (skinInstall) {
    skinInstall.addEventListener('click', async () => {
      skinInstall.disabled = true;
      __skinMsg('安装中…');
      try {
        const res = await fnosSettings.installSkin();
        if (res && res.ok) {
          __skinMsg('已安装：' + res.name);
          setTimeout(() => __skinMsg(''), 2200);
          await loadSkins();
        } else if (res && res.canceled) {
          __skinMsg('');
        } else {
          __skinMsg((res && res.error) || '安装失败');
        }
      } catch (err) {
        __skinMsg(err?.message || '安装失败');
      } finally {
        skinInstall.disabled = false;
      }
    });
  }

  loadSkins();

  // v1.59：界面主题色选项已移除（标题栏颜色独立可配），不再设置 accentColor
  btnClose.addEventListener('click', () => fnosSettings.close());

  // --------- 直播与播放（v1.18.0：本地代理与外部播放器路径已移除） ---------
  const iptvBaseUrl = document.getElementById('iptv-baseurl');
  const iptvLine = bindGlassSelect(document.querySelector('[data-select="iptv-line"]'));
  const iptvEpgUrl = document.getElementById('iptv-epgurl');
  const iptvCacheSeconds = document.getElementById('iptv-cacheseconds');
  const iptvSave = document.getElementById('iptv-save');
  const iptvError = document.getElementById('iptv-error');

  async function loadLiveConfig() {
    try {
      const info = await fnosSettings.getSettings();
      if (iptvBaseUrl) iptvBaseUrl.value = info?.iptv?.iptvBaseUrl || '';
      if (iptvLine) iptvLine.value = info?.iptv?.iptvLine || 'inner';
      if (iptvEpgUrl) iptvEpgUrl.value = info?.iptv?.iptvEpgUrl || '';
      if (iptvCacheSeconds) iptvCacheSeconds.value = info?.iptv?.iptvCacheSeconds != null ? info.iptv.iptvCacheSeconds : 30;
    } catch (err) {
      showError(iptvError, err?.message || '加载直播设置失败');
    }
  }

  if (iptvSave) {
    iptvSave.addEventListener('click', async () => {
      iptvSave.disabled = true;
      iptvSave.textContent = '保 存 中';
      showError(iptvError, '');
      try {
        // v1.23.0：保存基地址、线路、EPG 地址、缓冲秒数
        await fnosSettings.iptvSetConfig({
          iptvBaseUrl: iptvBaseUrl ? iptvBaseUrl.value.trim() : '',
          iptvLine: iptvLine ? iptvLine.value : 'inner',
          iptvEpgUrl: iptvEpgUrl ? iptvEpgUrl.value.trim() : '',
          iptvCacheSeconds: iptvCacheSeconds ? Number(iptvCacheSeconds.value) || 0 : 30,
        });
        iptvSave.textContent = '已 保 存';
        setTimeout(() => { iptvSave.textContent = '保存直播设置'; }, 1400);
      } catch (err) {
        showError(iptvError, err?.message || '保存失败');
      } finally {
        iptvSave.disabled = false;
        if (iptvSave.textContent === '保 存 中') iptvSave.textContent = '保存直播设置';
      }
    });
  }

  // v2.11.0 r31：电视直播播放器桌面快捷方式（保留 ID __fnos.live__，主进程三链直达直播窗，
  // 复用 create-desktop-shortcut IPC 的独立创建分支；结果反馈与应用列表快捷方式同约定：
  // 成功按钮态反馈、失败弹窗）
  const iptvShortcut = document.getElementById('iptv-shortcut');
  if (iptvShortcut) {
    iptvShortcut.addEventListener('click', async () => {
      if (!window.fnosSettings || !window.fnosSettings.createDesktopShortcut) {
        showError(iptvError, '创建快捷方式功能不可用');
        return;
      }
      iptvShortcut.disabled = true;
      iptvShortcut.textContent = '创 建 中';
      showError(iptvError, '');
      try {
        const result = await window.fnosSettings.createDesktopShortcut({
          appId: '__fnos.live__',
          appName: '电视直播',
          iconPath: '',
          nasAddress: '',
        });
        if (result && result.success) {
          iptvShortcut.textContent = '✓ 桌面快捷方式已生成';
        } else {
          iptvShortcut.textContent = '创建桌面快捷方式';
          const msg = (result && result.msg) || '未知错误';
          showError(iptvError, '创建失败：' + msg);
          alert('创建失败：' + msg);
        }
      } catch (err) {
        iptvShortcut.textContent = '创建桌面快捷方式';
        showError(iptvError, '创建失败：' + (err?.message || '未知错误'));
        alert('创建失败：' + (err?.message || '未知错误'));
      }
      setTimeout(() => { iptvShortcut.disabled = false; iptvShortcut.textContent = '创建桌面快捷方式'; }, 3000);
    });
  }

  // --------- 兼容性播放器（MPV）v1.25.0 ---------
  const vlcEnabled = document.getElementById('vlc-enabled');
  const vlcEnabledLabel = document.getElementById('vlc-enabled-label');
  // v2.20.0：默认用 MPV 播放（所有视频直接交给 MPV）
  const vlcAlways = document.getElementById('vlc-always');
  const vlcAlwaysLabel = document.getElementById('vlc-always-label');
  const vlcFallbackRow = document.getElementById('vlc-fallback-row');
  const vlcFallbackSub = document.getElementById('vlc-fallback-sub');
  const vlcHw = bindGlassSelect(document.querySelector('[data-select="vlc-hw"]'));
  const vlcCache = bindGlassSelect(document.querySelector('[data-select="vlc-cache"]'));
  // v2.21.0：自定义内核 / 附加参数 / 配置来源 / 低功耗预设 / 字幕样式
  const vlcExe = document.getElementById('vlc-exe');
  const vlcExeBrowse = document.getElementById('vlc-exe-browse');
  const vlcArgs = document.getElementById('vlc-args');
  const vlcConfig = bindGlassSelect(document.querySelector('[data-select="vlc-config"]'));
  // v5.0.5：播放内核（内置 / 自定义）
  const mpvCore = bindGlassSelect(document.querySelector('[data-select="mpv-core"]'));
  const vlcPerf = bindGlassSelect(document.querySelector('[data-select="vlc-perf"]'));
  const vlcSubPos = document.getElementById('vlc-sub-pos');
  const vlcSubSize = document.getElementById('vlc-sub-size');
  const vlcSubScale = document.getElementById('vlc-sub-scale');
  // v2.22.0：本地续播
  const vlcResume = document.getElementById('vlc-resume');
  const vlcResumeLabel = document.getElementById('vlc-resume-label');
  const vlcClearProgress = document.getElementById('vlc-clear-progress');
  const vlcProgressCount = document.getElementById('vlc-progress-count');
  const vlcNum = (el) => {
    if (!el) return null;
    const raw = String(el.value == null ? '' : el.value).trim();
    if (!raw) return null;
    const n = Number(raw);
    return Number.isFinite(n) ? n : null;
  };
  const vlcSave = document.getElementById('vlc-save');
  const vlcError = document.getElementById('vlc-error');
  const vlcRuntime = document.getElementById('vlc-runtime');

  function refreshVlcRuntime() {
    if (!vlcRuntime) return;
    try {
      fnosSettings.vlcRuntime().then((rt) => {
        try {
          const hwLabel = { auto: '自动', d3d11va: 'D3D11VA', dxva2: 'DXVA2', no: '软解' }[rt.hwDecode] || rt.hwDecode;
          const cacheLabel = { standard: '均衡 32MB/60s', smooth: '流畅 128MB/120s', unlimited: '超大 512MB/300s' }[rt.cacheLevel] || rt.cacheLevel;
          let html = '';
          const always = rt.alwaysUse === true;
          if (rt.available) {
            html = '<div style="color:#4ade80">● MPV 播放器已就绪，应用内嵌入播放' + (always ? '（默认播放器）' : '（兜底播放器）') + '</div>' +
              '<div style="opacity:.8;margin-top:4px">' + (always
                ? '当前为默认播放模式：飞牛影视的所有视频都直接交给 MPV 播放，剧集支持上一集 / 下一集与自动连播。'
                : '当前为兜底模式：仅当网页内核无法解码（HEVC/10bit/4K/MKV 等）时自动切换到 MPV。') + '</div>';
          } else {
            html = '<div style="color:#f87171">● MPV 播放器不可用：' + (rt.reason || '未知原因') + '</div>' +
              '<div style="opacity:.8;margin-top:4px">特殊编码视频仍只能依赖网页内核，可能无法播放。</div>';
          }
          if (rt.custom) {
            html += '<div style="margin-top:6px;color:#60a5fa">● 当前使用自定义 MPV 内核：' + (rt.source || '') + '</div>';
          } else if (rt.customMissing) {
            html += '<div style="margin-top:6px;color:#fbbf24">● 自定义内核路径无效，已回退' + (rt.platform === 'darwin' ? '系统 mpv' : '内置内核') + '</div>';
          }
          html += '<div style="margin-top:6px;opacity:.85">硬件解码：' + hwLabel + ' ｜ 网络缓存：' + cacheLabel +
            ((rt.subStyle && (rt.subStyle.pos != null || rt.subStyle.size != null || rt.subStyle.scale != null))
              ? (' ｜ 字幕：位置 ' + (rt.subStyle.pos != null ? rt.subStyle.pos : '默认') +
                 ' / 字号 ' + (rt.subStyle.size != null ? rt.subStyle.size : '默认') +
                 ' / 缩放 ' + (rt.subStyle.scale != null ? rt.subStyle.scale : '默认'))
              : '') + '</div>';
          // v2.22.0：续播状态 + 已记多少条
          html += '<div style="margin-top:6px;opacity:.85">续播：' + (rt.resume === false ? '已关闭' : '已开启，本机记录与飞牛网页端观看记录双向同步') +
            (typeof rt.progressCount === 'number' ? ('（本机已记录 ' + rt.progressCount + ' 条播放进度）') : '') + '</div>';
          vlcRuntime.innerHTML = html;
          if (vlcProgressCount) {
            vlcProgressCount.textContent = (typeof rt.progressCount === 'number' && rt.progressCount > 0)
              ? ('当前已记录 ' + rt.progressCount + ' 条。') : '当前没有记录。';
          }
        } catch (_) {}
      }).catch(() => { if (vlcRuntime) vlcRuntime.textContent = 'MPV 状态检测失败'; });
    } catch (_) {}
  }

  async function loadVlcConfig() {
    try {
      const info = await fnosSettings.getSettings();
      const v = info?.mpv || info?.vlc || {};
      if (vlcEnabled) {
        vlcEnabled.checked = v.enabled !== false;
        if (vlcEnabledLabel) vlcEnabledLabel.textContent = vlcEnabled.checked ? '已启用' : '已关闭';
      }
      if (vlcAlways) {
        vlcAlways.checked = v.alwaysUse === true;
        if (vlcAlwaysLabel) vlcAlwaysLabel.textContent = vlcAlways.checked ? '已开启' : '已关闭';
      }
      syncVlcAlways();
      if (vlcHw) vlcHw.value = v.hwDecode || 'auto';
      if (vlcCache) vlcCache.value = v.cacheLevel || 'smooth';
      // v2.21.0
      if (vlcExe) vlcExe.value = v.exePath || '';
      if (vlcArgs) vlcArgs.value = v.extraArgs || '';
      if (vlcConfig) vlcConfig.value = (v.configMode === 'own') ? 'own' : 'builtin';
      if (mpvCore) mpvCore.value = (v.coreMode === 'custom') ? 'custom' : 'builtin';
      if (vlcPerf) vlcPerf.value = (v.perfPreset === true) ? 'on' : (v.perfPreset === false ? 'off' : 'auto');
      const ss = (v.subStyle && typeof v.subStyle === 'object') ? v.subStyle : {};
      if (vlcSubPos) vlcSubPos.value = (typeof ss.pos === 'number') ? String(ss.pos) : '';
      if (vlcSubSize) vlcSubSize.value = (typeof ss.size === 'number') ? String(ss.size) : '';
      if (vlcSubScale) vlcSubScale.value = (typeof ss.scale === 'number') ? String(ss.scale) : '';
      // v2.22.0：续播开关（默认开）
      if (vlcResume) {
        vlcResume.checked = v.resume !== false;
        if (vlcResumeLabel) vlcResumeLabel.textContent = vlcResume.checked ? '已开启' : '已关闭';
      }
      refreshVlcRuntime();
    } catch (err) {
      showError(vlcError, err?.message || '加载播放器设置失败');
    }
  }

  // 默认用 MPV 时，兜底开关不再生效 → 置灰 + 说明
  function syncVlcAlways() {
    const always = !!(vlcAlways && vlcAlways.checked);
    if (vlcFallbackRow) {
      vlcFallbackRow.style.opacity = always ? '0.45' : '';
    }
    if (vlcEnabled) vlcEnabled.disabled = always;
    // 被接管时勾选框是禁用空框，文案同步为「已停用」避免与状态自相矛盾
    if (vlcEnabledLabel) vlcEnabledLabel.textContent = always
      ? '已停用'
      : (vlcEnabled && vlcEnabled.checked ? '已启用' : '已关闭');
    if (vlcFallbackSub) {
      vlcFallbackSub.textContent = always
        ? '已由「默认用 MPV 播放」接管，本项不再生效'
        : '网页内核无法解码（HEVC/10bit/4K/MKV 等）时自动切到 MPV；开启「默认用 MPV 播放」后本项不再生效';
    }
  }

  if (vlcAlways) {
    vlcAlways.addEventListener('change', () => {
      if (vlcAlwaysLabel) vlcAlwaysLabel.textContent = vlcAlways.checked ? '已开启' : '已关闭';
      syncVlcAlways();
    });
  }

  if (vlcEnabled) {
    vlcEnabled.addEventListener('change', () => {
      if (vlcEnabledLabel) vlcEnabledLabel.textContent = vlcEnabled.checked ? '已启用' : '已关闭';
    });
  }

  // v2.22.0：续播开关文案
  if (vlcResume) {
    vlcResume.addEventListener('change', () => {
      if (vlcResumeLabel) vlcResumeLabel.textContent = vlcResume.checked ? '已开启' : '已关闭';
    });
  }

  // v2.22.0：清除本机播放记录
  if (vlcClearProgress) {
    vlcClearProgress.addEventListener('click', async () => {
      const ask = window.confirm('清除本机记录的所有播放进度？清除后各剧集下次将从头发起播放。');
      if (!ask) return;
      vlcClearProgress.disabled = true;
      try {
        const r = await fnosSettings.clearProgress();
        if (r && r.ok) {
          showError(vlcError, '');
          if (vlcProgressCount) vlcProgressCount.textContent = '已清除 ' + (r.cleared || 0) + ' 条记录。';
          refreshVlcRuntime();
        } else {
          showError(vlcError, (r && r.error) || '清除失败');
        }
      } catch (err) {
        showError(vlcError, err?.message || '清除失败');
      } finally {
        vlcClearProgress.disabled = false;
      }
    });
  }

  // v2.21.0：选择自定义 MPV 内核（mpv.exe）
  if (vlcExeBrowse) {
    vlcExeBrowse.addEventListener('click', async () => {
      try {
        const r = await fnosSettings.pickMpvExe();
        if (r && r.ok && r.path) {
          if (vlcExe) vlcExe.value = r.path;
        } else if (r && r.error) {
          showError(vlcError, r.error);
        }
      } catch (err) {
        showError(vlcError, err?.message || '选择文件失败');
      }
    });
  }

  if (vlcSave) {
    vlcSave.addEventListener('click', async () => {
      vlcSave.disabled = true;
      vlcSave.textContent = '保 存 中';
      showError(vlcError, '');
      try {
        const alwaysOn = vlcAlways ? !!vlcAlways.checked : false;
        const res = await fnosSettings.setVlc({
          // v2.20.0：默认用 MPV 播放时，兜底开关不再生效，enabled 一律视为开启
          enabled: alwaysOn ? true : (vlcEnabled ? !!vlcEnabled.checked : true),
          alwaysUse: alwaysOn,
          hwDecode: vlcHw ? vlcHw.value : 'auto',
          cacheLevel: vlcCache ? vlcCache.value : 'smooth',
          // v2.21.0：自定义内核 / 附加参数 / 配置来源 / 低功耗预设 / 字幕样式
          exePath: vlcExe ? String(vlcExe.value || '').trim() : '',
          extraArgs: vlcArgs ? String(vlcArgs.value || '').trim() : '',
          coreMode: (mpvCore && mpvCore.value === 'custom') ? 'custom' : 'builtin',
          configMode: (vlcConfig && vlcConfig.value === 'own') ? 'own' : 'builtin',
          perfPreset: (vlcPerf ? vlcPerf.value : 'auto') === 'on' ? true
            : ((vlcPerf ? vlcPerf.value : 'auto') === 'off' ? false : null),
          subStyle: { pos: vlcNum(vlcSubPos), size: vlcNum(vlcSubSize), scale: vlcNum(vlcSubScale) },
          // v2.22.0：本地续播
          resume: vlcResume ? !!vlcResume.checked : true,
        });
        if (res && res.ok) {
          if (res.warning) showError(vlcError, res.warning);
          vlcSave.textContent = '已保存';
          refreshVlcRuntime();
          setTimeout(() => { vlcSave.textContent = '保存播放器设置'; }, 1800);
        } else {
          showError(vlcError, (res && res.error) || '保存失败');
        }
      } catch (err) {
        showError(vlcError, err?.message || '保存失败');
      } finally {
        vlcSave.disabled = false;
      }
    });
  }


  // --------- 自动锁定时长（v1.16.1） ---------
  if (autoLockSel) {
    autoLockSel.addEventListener('change', async () => {
      try {
        const mins = Number(autoLockSel.value) || 0;
        const res = await fnosSettings.setAutoLock(mins);
        if (res && res.ok) {
          updateAutoLockHint(true, mins);
        } else {
          if (autoLockHint) autoLockHint.textContent = (res && res.error) || '保存失败';
        }
      } catch (err) {
        if (autoLockHint) autoLockHint.textContent = err?.message || '保存失败';
      }
    });
  }

  // v2.1.11：快捷方式打开应用后主程序后台化方式（tray 隐藏到托盘 / minimize 最小化到任务栏）
  if (shortcutHideSel) {
    shortcutHideSel.addEventListener('change', async () => {
      try {
        const mode = shortcutHideSel.value === 'minimize' ? 'minimize' : 'tray';
        const res = await fnosSettings.setShortcutHideMode(mode);
        const statusEl = document.getElementById('app-action-status');
        if (res && res.ok) {
          if (statusEl) statusEl.textContent = '已保存：打开应用后主程序' + (mode === 'minimize' ? '最小化到任务栏' : '隐藏到托盘');
        } else {
          shortcutHideSel.value = mode === 'minimize' ? 'tray' : 'minimize'; // 回滚
          if (statusEl) statusEl.textContent = '保存失败: ' + ((res && res.error) || '未知错误');
        }
      } catch (err) {
        const statusEl = document.getElementById('app-action-status');
        if (statusEl) statusEl.textContent = '保存失败: ' + (err?.message || '未知错误');
      }
    });
  }

  // v2.0.0：开机自启动开关（v2.4.0 需求 2.2/2.5-3）：经 set-auto-launch({ enable }) 写注册表
  // （HKCU Run，开机命令不带应用 ID，禁用打包工具自启能力）；autostart-text 文字按需求固定不动，
  // 状态反馈写 autostart-hint。开关状态持久化于主进程 settings JSON（autoLaunch 字段）。
  if (autostartToggle) {
    autostartToggle.addEventListener('change', async () => {
      const enabled = autostartToggle.checked;
      if (autostartHint) { autostartHint.textContent = enabled ? '开启中...' : '关闭中...'; autostartHint.style.color = '#f0b429'; }
      try {
        const res = await fnosSettings.setAutoLaunch(enabled);
        if (res && res.success) {
          if (autostartHint) {
            autostartHint.textContent = res.msg || (enabled ? '已开启开机自启' : '已关闭开机自启');
            autostartHint.style.color = '#4ade80';
          }
        } else {
          autostartToggle.checked = !enabled; // 回滚
          if (autostartHint) {
            autostartHint.textContent = res?.msg || '操作失败';
            autostartHint.style.color = '#f87171';
          }
        }
      } catch (err) {
        autostartToggle.checked = !enabled;
        if (autostartHint) {
          autostartHint.textContent = err?.message || '操作失败';
          autostartHint.style.color = '#f87171';
        }
      }
    });
  }

  // v4.0.48：保持登录开关——立即生效（主进程广播给所有窗口 + 改写会话级 Cookie）
  if (keepLoginToggle) {
    keepLoginToggle.addEventListener('change', async () => {
      const on = keepLoginToggle.checked;
      if (keepLoginHint) { keepLoginHint.textContent = on ? '已开启：正在保持登录…' : '已关闭：退出后将不再保持登录'; keepLoginHint.style.color = '#f0b429'; }
      try {
        const res = await fnosSettings.setKeepLogin(on);
        if (keepLoginHint) {
          keepLoginHint.textContent = on
            ? '已开启：主程序、桌面内应用、快捷方式应用都会保持登录'
            : '已关闭：下次退出客户端后需要重新登录';
          keepLoginHint.style.color = '#4ade80';
        }
        if (!res || res.ok !== true) throw new Error('保存失败');
      } catch (err) {
        keepLoginToggle.checked = !on;
        if (keepLoginHint) { keepLoginHint.textContent = (err && err.message) || '操作失败'; keepLoginHint.style.color = '#f87171'; }
      }
    });
  }

  // F5/Esc/右键阻断
  document.addEventListener('keydown', (e) => {
    if (e.key === 'F5' || (e.ctrlKey && e.key === 'r')) e.preventDefault();
  });
  window.addEventListener('contextmenu', (e) => e.preventDefault());

  // 初始化
  (async () => {
    try {
      const info = await fnosSettings.getSettings();
      versionLine.textContent = `v${info?.version || ''}`;
      setPwdStatus(!!info?.hasPassword);
      hkLock.value = info?.shortcuts?.lockApp || '';
      hkHide.value = info?.shortcuts?.hideAll || '';
      const rewrites = info?.urlRewrites || [];
      if (rewrites.length === 0) rwTpl();
      else rewrites.forEach((r) => rwTpl(r.match || '', r.replace || ''));
      if (autoLockSel) {
        const mins = Number(info?.autoLockMinutes) || 0;
        autoLockSel.value = String(mins);
        updateAutoLockHint(!!info?.hasPassword, mins);
      }
      // v2.1.11：加载快捷方式后台化方式
      if (shortcutHideSel) {
        shortcutHideSel.value = info?.shortcutHideMode === 'minimize' ? 'minimize' : 'tray';
      }
      loadLiveConfig();
      loadVlcConfig();
      // v2.0.0：加载开机自启动状态（v2.4.0 需求 2.2：autostart-text 文字按需求固定不动，
      // 状态反馈写 autostart-hint）
      if (autostartToggle && fnosSettings.getAutoStart) {
        try {
          const res = await fnosSettings.getAutoStart();
          if (res && res.success) {
            autostartToggle.checked = !!res.data;
            if (autostartHint) {
              autostartHint.textContent = res.data ? '已开启开机自启' : '未开启开机自启';
              autostartHint.style.color = res.data ? '#4ade80' : '#f0b429';
            }
          } else {
            if (autostartHint) {
              autostartHint.textContent = res?.msg || '无法获取自启状态';
              autostartHint.style.color = '#f0b429';
            }
          }
        } catch (err) {
          if (autostartHint) {
            autostartHint.textContent = err?.message || '加载失败';
            autostartHint.style.color = '#f0b429';
          }
        }
      }
      // v2.0.0：加载账号管理
      loadAccountManager();
    } catch (err) {
      showError(hkError, err?.message || '加载设置失败');
    }
  })();

  // ============ v2.0.0 多账号管理 ============
  async function loadAccountManager() {
    const listEl = document.getElementById('account-list');
    const loadingEl = document.getElementById('account-loading');
    const addBtn = document.getElementById('account-add-btn');
    if (!listEl) return;

    async function refreshAccounts() {
      try {
        let accounts = [];
        try {
          const res = await fnosSettings.listAccounts();
          if (res && res.success) accounts = res.data || [];
        } catch (_) {
          try {
            const res = await fnosSettings._ipcInvoke && fnosSettings._ipcInvoke('account:list');
            if (res && res.success) accounts = res.data || [];
          } catch (_) {}
        }

        listEl.innerHTML = '';
        if (!accounts.length) {
          listEl.innerHTML = '<div class="account-empty">暂无已登录账号，请先连接 NAS 服务器</div>';
          return;
        }

        accounts.forEach(acct => {
          const item = document.createElement('div');
          item.className = 'account-item' + (acct.isActive ? ' active' : '');
          item.innerHTML = `
            <span class="acct-status" style="width:8px;height:8px;border-radius:50%;flex-shrink:0;background:${acct.isActive ? '#4ade80' : 'rgba(255,255,255,0.2)'}"></span>
            <div class="acct-info">
              <div class="acct-name">${acct.label || acct.origin || '未知账号'}${acct.isActive ? ' <span style="font-size:11px;color:#4ade80">(当前)</span>' : ''}</div>
              <div class="acct-addr">${acct.origin || ''}</div>
            </div>
            <div class="acct-actions">
              ${!acct.isActive ? '<button class="acct-btn primary" data-action="switch">切换</button>' : ''}
              <button class="acct-btn danger" data-action="remove">移除</button>
            </div>
          `;

          const switchBtn = item.querySelector('[data-action="switch"]');
          if (switchBtn) {
            switchBtn.addEventListener('click', async () => {
              try {
                const res = await fnosSettings.switchAccount(acct.origin);
                if (res && res.success) {
                  refreshAccounts();
                } else {
                  alert('切换失败: ' + (res?.msg || '未知错误'));
                }
              } catch (e) { alert('切换失败: ' + e.message); }
            });
          }

          item.querySelector('[data-action="remove"]').addEventListener('click', async () => {
            if (!confirm('确定移除账号 "' + (acct.label || acct.origin) + '"？\n这将清除该账号的登录状态。')) return;
            try {
              const res = await fnosSettings.removeAccount(acct.id);
              if (res && res.success) {
                refreshAccounts();
              } else {
                alert('移除失败: ' + (res?.msg || '未知错误'));
              }
            } catch (e) { alert('移除失败: ' + e.message); }
          });

          listEl.appendChild(item);
        });
      } catch (_) {
        listEl.innerHTML = '<div class="account-empty">加载失败</div>';
      }
    }

    if (addBtn) {
      addBtn.addEventListener('click', () => {
        try { fnosSettings.backToConnect(); } catch (_) {}
      });
    }

    refreshAccounts();
  }

  // v2.1.10：应用快捷方式创建
  const appListEl = document.getElementById('app-list');
  const appListLoading = document.getElementById('app-list-loading');
  const appListEmpty = document.getElementById('app-list-empty');
  const appActionStatus = document.getElementById('app-action-status');
  // v3.0.25（需求 2）：列表签名——用于静默比对，避免每次轮询都重绘导致闪烁/按钮状态丢失
  let __appListSig = null;

  // ==================================================================
  // v5.1.33（用户任务 1/1-B/2）：快捷方式面板的配套件
  //   · 图标：原生代取 → data URI（页面 CSP 只放行 data:，直连 http 会被拦）
  //   · 一键添加：先弹我们自己的确认框，再调 requestPinShortcut
  //   · 手动兜底：图文指引 + 应用宫格（长按设为常用/置顶）
  // ==================================================================
  let __apps = [];

  // ---- 弹窗外壳（只建一次）----
  function dshModal(id, inner) {
    let el = document.getElementById(id);
    if (!el) {
      el = document.createElement('div');
      el.id = id;
      el.className = 'dsh-mask';
      el.innerHTML = '<div class="dsh-modal">' + inner + '</div>';
      document.body.appendChild(el);
      el.addEventListener('click', function (e) { if (e.target === el) el.classList.remove('show'); });
    }
    return el;
  }

  /** 我们自己的确认框（MIUI 的系统框可能不出现，所以先问一次） */
  function askConfirm(title, text) {
    return new Promise(function (resolve) {
      const el = dshModal('dsh-confirm',
        '<div class="dsh-modal-title"></div>' +
        '<div class="dsh-modal-body"></div>' +
        '<div class="dsh-modal-foot">' +
          '<button class="dsh-btn" data-act="cancel">取消</button>' +
          '<button class="dsh-btn primary" data-act="ok">钉到桌面</button>' +
        '</div>');
      el.querySelector('.dsh-modal-title').textContent = title;
      el.querySelector('.dsh-modal-body').textContent = text;
      el.classList.add('show');
      const okBtn = el.querySelector('[data-act="ok"]');
      const noBtn = el.querySelector('[data-act="cancel"]');
      function finish(v) {
        el.classList.remove('show');
        okBtn.onclick = null; noBtn.onclick = null;
        resolve(v);
      }
      okBtn.onclick = function () { finish(true); };
      noBtn.onclick = function () { finish(false); };
    });
  }

  // ---- 图标：原生代取 → data URI ----
  const __iconCache = Object.create(null);
  async function iconDataFor(url) {
    if (!url) return '';
    if (__iconCache[url]) return __iconCache[url];
    if (!window.fnosSettings || !window.fnosSettings.getIconData) return '';
    try {
      // 先吃缓存（不联网、立即返回）
      const r = await window.fnosSettings.getIconData(url, false);
      if (r && r.ok && r.dataUri) { __iconCache[url] = r.dataUri; return r.dataUri; }
      // 缓存没有 → 让原生联网取一次（JavaBridge 线程，最多 3s）
      const r2 = await window.fnosSettings.getIconData(url, true);
      if (r2 && r2.ok && r2.dataUri) { __iconCache[url] = r2.dataUri; return r2.dataUri; }
    } catch (_) {}
    return '';
  }
  async function fillIcons() {
    const imgs = Array.prototype.slice.call(document.querySelectorAll('img[data-icon-url]'));
    const queue = imgs.filter(function (i) { return i.dataset.iconUrl && !i.dataset.iconDone; });
    async function worker() {
      while (queue.length) {
        const img = queue.shift();
        const uri = await iconDataFor(img.dataset.iconUrl);
        if (uri) {
          img.src = uri;
          img.dataset.iconDone = '1';
          img.style.display = '';
        }
      }
    }
    await Promise.all([worker(), worker(), worker(), worker()]);
  }

  // ---- 桌面能力探测 ----
  async function loadPinSupport() {
    const el = document.getElementById('pin-support');
    if (!el) return;
    if (!window.fnosSettings || !window.fnosSettings.getPinSupport) { el.textContent = ''; return; }
    try {
      const r = await window.fnosSettings.getPinSupport();
      if (!r || !r.ok) { el.textContent = '桌面能力：未知'; return; }
      let txt = r.supported
        ? '桌面：' + (r.launcher || '未知') + ' · 支持一键添加 · 已钉 ' + r.pinned + ' 个'
        : '桌面：' + (r.launcher || '未知') + ' · 不支持一键添加（可直接用应用宫格打开）';
      // v5.1.33：MIUI 的「桌面快捷方式」app-op（10017）没开时，系统会收下请求但不落图标
      if (r.supported && r.opAllowed === false) {
        txt += ' · ⚠ 桌面快捷方式权限未开启（点此开启）';
      }
      el.textContent = txt;
      el.className = 'pin-support ' + (r.supported && r.opAllowed !== false ? 'ok' : 'warn');
      el.style.cursor = (r.opAllowed === false) ? 'pointer' : '';
      el.onclick = (r.opAllowed === false) ? function () { openShortcutPerm(); } : null;
    } catch (_) { el.textContent = ''; }
  }

  // ---- 一键添加 ----
  function openShortcutPerm() {
    try {
      if (window.fnosSettings && window.fnosSettings.openShortcutPerm) {
        window.fnosSettings.openShortcutPerm({});
      }
    } catch (_) {}
  }

  async function pinApp(appData, btn) {
    if (!window.fnosSettings || !window.fnosSettings.createDesktopShortcut) return false;
    const old = btn ? btn.textContent : '';
    if (btn) { btn.disabled = true; btn.textContent = '请求中...'; }
    let r = null;
    try {
      r = await window.fnosSettings.createDesktopShortcut({
        appId: appData.appId,
        appName: appData.appName || appData.appId,
        iconPath: appData.iconPath || '',
        nasAddress: appData.nasAddress || '',
      });
    } catch (e) {
      r = { success: false, msg: e.message };
    }
    if (r && r.success) {
      // v5.1.33：桌面"受理"不等于"落图标"。MIUI 会返回 true 然后静默丢弃
      // （系统日志：add shortcut failed, <pkg> has no permission）。
      // 所以这里先挂「等待确认」，2.5 秒后回读真实 pinnedShortcuts 再说话。
      const appName = appData.appName || appData.appId;
      const pinId = 'fn-pin-' + appData.appId;
      if (btn) { btn.textContent = '等待桌面确认…'; btn.disabled = true; }
      if (appActionStatus) {
        appActionStatus.textContent = '已请求添加到桌面：' + appName +
          '（系统桌面若弹出「添加」，请点一下）';
      }
      setTimeout(async function () {
        let landed = false;
        try {
          const s = await window.fnosSettings.getPinnedIds({});
          const ids = (s && s.ids) || [];
          landed = ids.indexOf(pinId) >= 0;
        } catch (_) {}
        if (landed) {
          if (btn) {
            btn.textContent = '✓ 已钉到桌面';
            btn.classList.add('success');
            btn.disabled = true;
            btn.title = '已钉到桌面';
          }
          // 宫格里的「✓ 已钉」标记同步
          (__apps || []).forEach(function (a) { if (a.appId === appData.appId) a.pinned = true; });
          try { if (document.getElementById('dsh-grid')) renderGrid(); } catch (_) {}
          if (appActionStatus) appActionStatus.textContent = '已钉到桌面：' + appName;
          loadPinSupport();
        } else {
          if (btn) { btn.textContent = old || '钉到桌面'; btn.disabled = false; }
          if (appActionStatus) {
            appActionStatus.textContent = '桌面未确认添加：' + appName +
              ' —— 检查顶部「桌面快捷方式权限」，开启后重试';
          }
          loadPinSupport();
        }
      }, 2500);
      return true;
    }
    const msg = (r && r.msg) || '未知错误';
    if (btn) { btn.textContent = old || '钉到桌面'; btn.disabled = false; }
    if (appActionStatus) {
      appActionStatus.textContent = '未能钉到桌面：' + msg +
        '（若提示权限未开启，点上方状态条跳转开启后重试）';
    }
    return false;
  }

  // ---- v5.1.35（用户需求）：全部钉到桌面 / 清理全部快捷方式 ----
  const pinAllBtn = document.getElementById('pin-all');
  const pinClearBtn = document.getElementById('pin-clear');
  const pinAllStatus = document.getElementById('pin-all-status');

  function pinAllSay(text) {
    if (!pinAllStatus) return;
    pinAllStatus.style.display = text ? 'block' : 'none';
    pinAllStatus.textContent = text || '';
  }

  // 原生批量钉的进度回调（SettingsBridge 用 evaluateJavascript 推过来）
  window.__fnosPinAllProgress = function (s) {
    if (!s) return;
    const cur = s.current ? '：' + s.current : '';
    pinAllSay('正在钉 ' + s.done + '/' + s.total + ' …' + cur);
  };

  window.__fnosPinAllDone = function (r) {
    if (pinAllBtn) {
      pinAllBtn.dataset.running = '';
      pinAllBtn.disabled = false;
      pinAllBtn.textContent = '全部钉到桌面';
    }
    if (!r) { pinAllSay('批量钉到桌面已结束'); return; }
    let txt = '✓ 已完成：成功 ' + (r.ok || 0) + ' 个，已存在 ' + (r.exists || 0) +
      ' 个，失败 ' + (r.fail || 0) + ' 个';
    const fails = r.failures || [];
    if (fails.length) {
      txt += '\n失败原因：' + fails.slice(0, 10).map(function (f) {
        return (f.name || '?') + ' → ' + (f.reason || '未知');
      }).join('；') + (fails.length > 10 ? '；…共 ' + fails.length + ' 项（详见日志）' : '');
    }
    pinAllSay(txt);
    // 完成后自动刷新面板上的「✓ 已钉到桌面」标记
    if (__apps && __apps.length) { try { renderGrid(); } catch (_) {} }
    __appListSig = null;
    loadAppList(true);
    loadPinSupport();
  };

  if (pinAllBtn) {
    pinAllBtn.addEventListener('click', async function () {
      if (pinAllBtn.dataset.running === '1') return;
      if (!window.fnosSettings || !window.fnosSettings.pinAll) {
        pinAllSay('当前版本不支持批量钉到桌面');
        return;
      }
      pinAllBtn.dataset.running = '1';
      pinAllBtn.disabled = true;
      pinAllBtn.textContent = '钉到桌面中…';
      pinAllSay('正在准备应用清单…');
      try {
        const r = await window.fnosSettings.pinAll({});
        if (!r || !r.ok) {
          pinAllBtn.dataset.running = '';
          pinAllBtn.disabled = false;
          pinAllBtn.textContent = '全部钉到桌面';
          pinAllSay('无法开始：' + ((r && (r.error || r.msg)) || '未知错误'));
          return;
        }
        pinAllSay('正在钉 0/' + (r.total || 0) + ' …');
      } catch (e) {
        pinAllBtn.dataset.running = '';
        pinAllBtn.disabled = false;
        pinAllBtn.textContent = '全部钉到桌面';
        pinAllSay('无法开始：' + (e && e.message));
      }
    });
  }

  if (pinClearBtn) {
    pinClearBtn.addEventListener('click', async function () {
      if (!window.fnosSettings || !window.fnosSettings.clearShortcuts) {
        pinAllSay('当前版本不支持清理快捷方式');
        return;
      }
      const yes = await askConfirm(
        '清理全部快捷方式',
        '将清除本应用注册的动态快捷方式，并把已钉到桌面的图标置灰。\n\n' +
        'Android 不允许应用直接删除桌面图标，灰色图标需要你在桌面上长按 → 移除。\n\n确定继续？'
      );
      if (!yes) return;
      pinClearBtn.disabled = true;
      pinClearBtn.textContent = '清理中…';
      try {
        const r = await window.fnosSettings.clearShortcuts({});
        if (r && r.ok) {
          pinAllSay('✓ ' + (r.msg ||
            ('已清理动态快捷方式 ' + (r.dynamicRemoved || 0) + ' 个')));
        } else {
          pinAllSay('清理失败：' + ((r && (r.error || r.msg)) || '未知错误'));
        }
      } catch (e) {
        pinAllSay('清理失败：' + (e && e.message));
      }
      pinClearBtn.disabled = false;
      pinClearBtn.textContent = '清理全部快捷方式';
      __appListSig = null;
      loadAppList(true);
      loadPinSupport();
    });
  }

  // v5.1.34（用户 Bug B）：原来的「手动添加指引」（长按桌面图标 → 拖到桌面）已整段删除。
  // 真机实测 MIUI Pad 5 Pro 长按「飞牛平板」图标不弹任何菜单（截图 /tmp/h1.png），
  // 那条路径在本机不成立，留着只会误导用户。现在唯一的入口是上面的应用宫格。

  // ==================================================================
  // v5.1.34（用户 Bug B）：应用宫格 —— App 内唯一的快捷方式入口
  //   · 单击 = 直接打开该应用的应用窗（不再"点一下就钉桌面"）
  //   · 长按 = 操作菜单：打开 / 钉到桌面 / 设为常用（置顶）
  //   · 搜索 + 按 FPK 分组（第三方 / 官方 / 系统），38 个应用可查
  //   · 图标走原生代取的 data URI（页面 CSP 只放行 data:）
  // 注：MIUI Pad 实测「长按桌面应用图标」不弹快捷方式列表，所以不再提供那条路径。
  // ==================================================================
  const GRID_GROUPS = [
    { key: 'thirdparty', title: '第三方应用' },
    { key: 'official', title: '官方应用' },
    { key: 'system', title: '系统应用' }
  ];
  let __gridQuery = '';

  function openGrid() {
    const el = dshModal('dsh-grid',
      '<div class="dsh-modal-title">应用宫格</div>' +
      '<div class="dsh-modal-body">' +
        '<p class="dsh-hint"><b>单击</b> = 打开应用；<b>长按</b> = 操作菜单（打开 / 钉到桌面 / 设为常用）。共 <b class="dsh-grid-count">0</b> 个。</p>' +
        '<input class="dsh-grid-search" type="search" placeholder="搜索应用（名称 / 标识）" />' +
        '<div class="dsh-grid"></div>' +
      '</div>' +
      '<div class="dsh-modal-foot"><button class="dsh-btn" data-act="close">关闭</button></div>');
    el.querySelector('[data-act="close"]').onclick = function () { el.classList.remove('show'); };
    const q = el.querySelector('.dsh-grid-search');
    q.value = __gridQuery;
    q.oninput = function () { __gridQuery = q.value || ''; renderGrid(); };
    el.classList.add('show');
    renderGrid();
    // 打开时静默刷新一次清单（新增应用/图标立刻可见）
    loadAppList(true);
  }

  function gridGroupOf(app) {
    const g = String(app.group || '').toLowerCase();
    if (g === 'thirdparty' || g === 'official' || g === 'system') return g;
    return 'other';
  }

  function renderGrid() {
    const el = document.getElementById('dsh-grid');
    if (!el) return;
    const box = el.querySelector('.dsh-grid');
    if (!box) return;
    const q = (__gridQuery || '').trim().toLowerCase();
    const list = (__apps || []).filter(function (a) {
      if (!q) return true;
      return String(a.appName || '').toLowerCase().indexOf(q) >= 0 ||
        String(a.appId || '').toLowerCase().indexOf(q) >= 0;
    });
    const cnt = el.querySelector('.dsh-grid-count');
    if (cnt) cnt.textContent = String(list.length);

    const fav = list.filter(function (a) { return !!a.fav; });
    const groups = [];
    if (fav.length) groups.push({ title: '常用（置顶）', apps: fav });
    GRID_GROUPS.forEach(function (g) {
      const arr = list.filter(function (a) { return !a.fav && gridGroupOf(a) === g.key; });
      if (arr.length) groups.push({ title: g.title, apps: arr });
    });
    const other = list.filter(function (a) { return !a.fav && gridGroupOf(a) === 'other'; });
    if (other.length) groups.push({ title: '其它', apps: other });

    box.innerHTML = '';
    if (!groups.length) {
      const empty = document.createElement('p');
      empty.className = 'dsh-hint';
      empty.textContent = (__apps || []).length ? '没有匹配的应用' : '应用清单还没拉到：请确认已连接到 NAS';
      box.appendChild(empty);
    }
    groups.forEach(function (g) {
      const sec = document.createElement('div');
      sec.className = 'dsh-group';
      const h = document.createElement('div');
      h.className = 'dsh-group-title';
      h.textContent = g.title + '（' + g.apps.length + '）';
      const wrap = document.createElement('div');
      wrap.className = 'dsh-grid-wrap';
      g.apps.forEach(function (app) { wrap.appendChild(gridTile(app)); });
      sec.appendChild(h);
      sec.appendChild(wrap);
      box.appendChild(sec);
    });
    fillIcons();
  }

  function gridTile(app) {
    const tile = document.createElement('div');
    tile.className = 'dsh-tile' + (app.fav ? ' fav' : '');
    const iconUrl = app.iconUrl || '';
    tile.innerHTML =
      '<img class="dsh-tile-icon" alt="" data-icon-url="' + iconUrl + '"' +
        (iconUrl ? '' : ' style="display:none"') + ' />' +
      '<div class="dsh-tile-ph"' + (iconUrl ? ' style="display:none"' : '') + '></div>' +
      '<div class="dsh-tile-name"></div>' +
      '<div class="dsh-tile-tag">' + (app.fav ? '★ 常用' : (app.pinned ? '✓ 已钉' : '')) + '</div>';
    tile.querySelector('.dsh-tile-name').textContent = app.appName || app.appId;
    tile.querySelector('.dsh-tile-ph').textContent =
      String(app.appName || app.appId || '?').trim().slice(0, 1);

    let timer = null, longFired = false;
    const cancel = function () { if (timer) { clearTimeout(timer); timer = null; } };
    const start = function () {
      longFired = false;
      cancel();
      timer = setTimeout(function () {
        timer = null;
        longFired = true;
        openAppActions(app);
      }, 600);
    };
    tile.addEventListener('touchstart', start, { passive: true });
    tile.addEventListener('touchend', cancel);
    tile.addEventListener('touchmove', cancel, { passive: true });
    tile.addEventListener('touchcancel', cancel);
    tile.addEventListener('mousedown', start);
    tile.addEventListener('mouseup', cancel);
    tile.addEventListener('mouseleave', cancel);
    tile.addEventListener('contextmenu', function (e) {
      e.preventDefault(); cancel(); longFired = true; openAppActions(app);
    });
    tile.addEventListener('click', function () {
      if (longFired) { longFired = false; return; }
      openAppNow(app);
    });
    return tile;
  }

  /** 单击宫格 = 直接打开该应用窗 */
  async function openAppNow(app) {
    if (!window.fnosSettings || !window.fnosSettings.openApp) {
      if (appActionStatus) appActionStatus.textContent = '打开失败：当前版本不支持';
      return;
    }
    const grid = document.getElementById('dsh-grid');
    if (grid) grid.classList.remove('show');
    try {
      const r = await window.fnosSettings.openApp({
        appId: app.appId,
        appName: app.appName || app.appId,
        anchor: app.anchor || app.appId,
        path: app.path || '',
        url: app.url || '',
        nasAddress: app.nasAddress || ''
      });
      if (r && r.ok) {
        if (appActionStatus) appActionStatus.textContent = '已打开：' + (app.appName || app.appId);
      } else if (appActionStatus) {
        appActionStatus.textContent = '打开失败：' + ((r && r.error) || '未知错误');
      }
    } catch (e) {
      if (appActionStatus) appActionStatus.textContent = '打开失败：' + (e && e.message);
    }
  }

  /** 长按宫格 = 操作菜单：打开 / 钉到桌面 / 设为常用（置顶） */
  function openAppActions(app) {
    const el = dshModal('dsh-app-actions',
      '<div class="dsh-modal-title"></div>' +
      '<div class="dsh-modal-body"><p class="dsh-hint dsh-actions-state"></p></div>' +
      '<div class="dsh-modal-foot">' +
        '<button class="dsh-btn" data-act="cancel">取消</button>' +
        '<button class="dsh-btn" data-act="open">打开</button>' +
        '<button class="dsh-btn" data-act="fav"></button>' +
        '<button class="dsh-btn primary" data-act="pin">钉到桌面</button>' +
      '</div>');
    el.querySelector('.dsh-modal-title').textContent = app.appName || app.appId;
    el.querySelector('.dsh-actions-state').textContent =
      (app.fav ? '已在常用（置顶）' : '未置顶') + ' · ' + (app.pinned ? '已在桌面' : '未钉到桌面');
    const openBtn = el.querySelector('[data-act="open"]');
    const favBtn = el.querySelector('[data-act="fav"]');
    const pinBtn = el.querySelector('[data-act="pin"]');
    el.querySelector('[data-act="cancel"]').onclick = function () { el.classList.remove('show'); };
    openBtn.onclick = function () { el.classList.remove('show'); openAppNow(app); };
    favBtn.textContent = app.fav ? '取消常用' : '设为常用（置顶）';
    favBtn.onclick = async function () { el.classList.remove('show'); await toggleFav(app.appId); };
    pinBtn.onclick = async function () {
      pinBtn.disabled = true;
      pinBtn.textContent = '请求中…';
      await pinApp(app, null);
      el.classList.remove('show');
    };
    el.classList.add('show');
  }

  async function toggleFav(appId) {
    if (!window.fnosSettings || !window.fnosSettings.setFavApps) return;
    const s = new Set();
    __apps.forEach(function (a) { if (a.fav) s.add(a.appId); });
    if (s.has(appId)) s.delete(appId); else s.add(appId);
    __apps.forEach(function (a) { a.fav = s.has(a.appId); });
    try {
      const r = await window.fnosSettings.setFavApps(Array.from(s));
      if (r && r.ok) {
        const name = (__apps.find(a => a.appId === appId) || {}).appName || appId;
        if (appActionStatus) appActionStatus.textContent = (s.has(appId) ? '已设为常用：' : '已取消常用：') + name;
      }
    } catch (_) {}
    __appListSig = null;      // 让主列表重绘（★ 标记变了）
    renderGrid();
    loadAppList(true);
  }

  /**
   * v5.1.31-tablet17（用户任务 1 第 3/4 条）：原生 onResume 调用。
   * 回读系统**真实**的 pinnedShortcuts（getPinnedShortcuts），把宫格与设置页列表里的
   * 「✓ 已钉」状态一次性校准 —— 用户可能在系统桌面手动删掉图标，这里也要跟着变。
   */
  window.__fnosRefreshPinned = async function () {
    try {
      if (!window.fnosSettings || !window.fnosSettings.getPinnedIds) return;
      const s = await window.fnosSettings.getPinnedIds({});
      const ids = new Set((s && s.ids) || []);
      (__apps || []).forEach(function (a) {
        a.pinned = ids.has('fn-pin-' + a.appId);
      });
      try { if (document.getElementById('dsh-grid')) renderGrid(); } catch (_) {}
      __appListSig = null;      // 强制主列表按真实 pinned 状态重绘
      loadAppList(true);
    } catch (_) {}
  };

  loadPinSupport();
  try {
    const gb = document.getElementById('open-app-grid');
    if (gb) gb.addEventListener('click', openGrid);
  } catch (_) {}
  // v5.1.34（用户 Bug B）：AppsActivity 是宫格的唯一入口，启动即弹出
  (async function openGridIfRequested() {
    try {
      const lm = await window.fnosSettings.getLaunchMode({});
      if (lm && lm.openGrid) setTimeout(openGrid, 250);
    } catch (_) {}
  })();

  async function loadAppList(silent) {
    try {
      if (!window.fnosSettings || !window.fnosSettings.getInstalledApps) {
        if (appListLoading) appListLoading.textContent = '应用快捷方式创建功能不可用';
        return;
      }
      const res = await window.fnosSettings.getInstalledApps();
      if (appListLoading) appListLoading.style.display = 'none';
      if (!res.success || !res.data || !res.data.length) {
        if (appListEmpty) appListEmpty.style.display = 'block';
        // v3.0.25（需求 2）：应用被卸载后自动清空旧卡片（此前只能靠关掉再打开设置页）
        if (__appListSig !== null && __appListSig !== '') {
          appListEl.innerHTML = '';
          appListEl.style.display = 'none';
          __appListSig = '';
        }
        return;
      }
      if (appListEmpty) appListEmpty.style.display = 'none';
      // v3.0.25（需求 2）：自动感知——应用增删/改名/换图标都会改变签名；签名未变则静默跳过重绘
      // v5.1.33：签名按 appId 排序后再拼，避免后端返回顺序抖动导致每 10 秒重建一次
      //          DOM（重建会打断用户点击、并把页面滚动位置拉回顶部）。
      const __sig = res.data.slice()
        .sort(function (x, y) { return String(x.appId || '').localeCompare(String(y.appId || '')); })
        .map(function (a) {
          return [a.appId, a.appName || a.name || a.title || '', a.iconPath || '', a.iconUrl || '',
            a.pinned ? 1 : 0, a.fav ? 1 : 0].join('~');
        }).join('|');
      if (silent && __appListSig === __sig) {
        try { if (appActionStatus && !appActionStatus.textContent) appActionStatus.textContent = ''; } catch (_) {}
        return;
      }
      __appListSig = __sig;
      appListEl.innerHTML = '';
      appListEl.style.display = 'grid';
      // 用户要求（第 5 项）：界面注明清单来源（FPK / app-center 回退 / 内置兜底）
      if (appListLoading) {
        const srcLabel = res.source === 'fpk' ? 'FPK 图标管理器'
          : (res.source === 'app-center' ? 'app-center（FPK 不可用，已回退）' : '内置清单（离线兜底）');
        appListLoading.textContent = '应用来源：' + srcLabel + ' · ' + res.data.length + ' 个';
        appListLoading.style.display = 'block';
      }
      __apps = res.data;
      // 宫格正开着时同步刷新（搜索/分组/已钉标记跟着变）
      try { if (document.getElementById('dsh-grid')) renderGrid(); } catch (_) {}
      res.data.forEach((app) => {
        const card = document.createElement('div');
        card.className = 'app-card';
        const pinned = !!app.pinned;
        const fav = !!app.fav;
        // v5.1.33（用户任务 2）：图标不再直接写 http 地址 —— 设置页 CSP 是
        //   img-src 'self' data: blob:
        // 直接 <img src="http://<nas>:18080/api/icons/…"> 会被浏览器拦掉
        // （webconsole: Refused to load … violates CSP）。现在先留空，
        // 渲染完由 fillIcons() 向原生要 data URI（原生代取，带内存+磁盘缓存）。
        const iconUrl = app.iconUrl || '';
        card.innerHTML =
          '<img class="app-card-icon" alt="" data-icon-url="' + iconUrl + '"' +
            (iconUrl ? '' : ' style="display:none"') + ' />' +
          '<div class="app-card-info">' +
            '<div class="app-card-name"></div>' +
            '<div class="app-card-addr"></div>' +
          '</div>' +
          '<div class="app-card-actions">' +
            '<button class="app-card-btn open-btn" data-app-id="' + app.appId + '" title="打开该应用（不经过主界面）">打开</button>' +
            '<button class="app-card-btn shortcut-btn' + (pinned ? ' success' : '') + '" data-app-id="' + app.appId + '" title="' +
              (pinned ? '已钉到桌面' : '钉到桌面（走系统桌面）') + '"' + (pinned ? ' disabled' : '') + '>' +
              (pinned ? '✓ 已钉到桌面' : '钉到桌面') + '</button>' +
          '</div>';
        // 文本一律走 textContent：应用名来自 NAS，不拼进 HTML
        card.querySelector('.app-card-name').textContent =
          (fav ? '★ ' : '') + (app.appName || app.appId || '未命名');
        card.querySelector('.app-card-addr').textContent = (function () {
          const g = String(app.group || '').toLowerCase();
          const gname = g === 'thirdparty' ? '第三方应用'
            : (g === 'official' ? '官方应用' : (g === 'system' ? '系统应用' : ''));
          const src = app.source === 'fpk' ? 'FPK 图标管理器'
            : (app.source === 'app-center' ? 'app-center' : (app.nasAddress || ''));
          return (gname ? gname + ' · ' : '') + src;
        })();
        appListEl.appendChild(card);
      });
      fillIcons();
      // 绑定事件
      appListEl.querySelectorAll('.shortcut-btn').forEach(btn => {
        btn.addEventListener('click', async () => {
          const appId = btn.dataset.appId;
          const appData = res.data.find(a => a.appId === appId);
          if (!appData) return;
          // v5.1.33（用户任务 1 第 3 步）：MIUI 会在调用后静默拒绝，
          // 所以先弹**我们自己的**确认框，用户心里有数，再调 requestPinShortcut。
          const yes = await askConfirm(
            '添加到桌面',
            '把「' + (appData.appName || appId) + '」的快捷方式钉到桌面？\n\n' +
            '随后系统桌面可能会再弹一次「是否添加」，需要你点「添加」。'
          );
          if (!yes) return;
          await pinApp(appData, btn);
        });
      });
      // v5.1.34（用户 Bug B）：每行两个按钮 —— 打开 / 钉到桌面
      appListEl.querySelectorAll('.open-btn').forEach(btn => {
        btn.addEventListener('click', () => {
          const appData = res.data.find(a => a.appId === btn.dataset.appId);
          if (appData) openAppNow(appData);
        });
      });
    } catch (e) {
      if (appListLoading) appListLoading.textContent = '加载失败: ' + e.message;
    }
  }
  loadAppList();
  // v3.0.25（需求 2）：自动感知与联动改造——
  //   以前只在设置页初始化时拉一次列表，新增应用后必须反复开关偏好设置才能看到；
  //   现在：① 打开后 1.2s / 3.5s 补偿刷新（首次数据常未就绪）；
  //        ② 每 10s 静默轮询（面板可见时）；③ 窗口重新聚焦 / 从隐藏恢复时立即刷新。
  //   签名未变时不会重绘，因此轮询对用户完全无感。
  try {
    setTimeout(function () { loadAppList(true); }, 1200);
    setTimeout(function () { loadAppList(true); }, 3500);
    setInterval(function () { try { if (!document.hidden) loadAppList(true); } catch (_) {} }, 10000);
    window.addEventListener('focus', function () { try { loadAppList(true); } catch (_) {} });
    document.addEventListener('visibilitychange', function () { try { if (!document.hidden) loadAppList(true); } catch (_) {} });
  } catch (_) {}

  // ---- v2.1.17: FPK 图标管理器 API 配置 ----
  const fpkEnabled = document.getElementById('fpk-enabled');
  const fpkHost = document.getElementById('fpk-host');
  const fpkPort = document.getElementById('fpk-port');
  const fpkStatus = document.getElementById('fpk-status');
  const fpkSave = document.getElementById('fpk-save');
  const fpkTest = document.getElementById('fpk-test');
  // 加载 FPK 配置
  (async () => {
    try {
      const s = await fnosSettings.getSettings();
      if (s.fpkApi && fpkEnabled) {
        fpkEnabled.checked = !!s.fpkApi.enabled;
        fpkHost.value = s.fpkApi.host || '';
        fpkPort.value = s.fpkApi.port || 18080;
      }
    } catch (_) {}
  })();
  fpkTest?.addEventListener('click', async () => {
    // 先临时保存当前输入值用于测试
    await fnosSettings.setFpkApi({ enabled: fpkEnabled.checked, host: fpkHost.value.trim(), port: Number(fpkPort.value) || 18080 });
    fpkStatus.textContent = '正在测试连接...';
    fpkStatus.style.color = '#94a3b8';
    const result = await fnosSettings.testFpkApi();
    if (result.ok) {
      fpkStatus.textContent = `连接成功！FPK 版本: ${result.version}`;
      fpkStatus.style.color = '#4ade80';
    } else {
      fpkStatus.textContent = `连接失败: ${result.error}`;
      fpkStatus.style.color = '#f87171';
    }
  });
  fpkSave?.addEventListener('click', async () => {
    const host = fpkHost.value.trim();
    if (!host) {
      fpkStatus.textContent = '请填写 FPK 服务地址';
      fpkStatus.style.color = '#f87171';
      return;
    }
    await fnosSettings.setFpkApi({
      enabled: fpkEnabled.checked,
      host: host,
      port: Number(fpkPort.value) || 18080,
    });
    fpkStatus.textContent = '配置已保存';
    fpkStatus.style.color = '#4ade80';
  });

  // v4.0.36：清理应用缓存（设置 → 维护）。只清缓存，不动 Cookie/localStorage，登录态保留。
  (function initClearCache() {
    const btn = document.getElementById('btn-clear-cache');
    const hint = document.getElementById('cache-hint');
    if (!btn || !window.fnosSettings || !window.fnosSettings.clearCache) return;
    btn.addEventListener('click', async () => {
      const old = btn.textContent;
      btn.disabled = true;
      btn.textContent = '清理中…';
      try {
        const r = await window.fnosSettings.clearCache();
        const mb = (r && r.bytes) ? (r.bytes / 1048576).toFixed(1) : '0';
        if (hint) hint.textContent = (r && r.ok) ? ('已清理（约 ' + mb + ' MB），重新打开应用即可') : '清理失败，请重试';
      } catch (_) {
        if (hint) hint.textContent = '清理失败，请重试';
      } finally {
        btn.disabled = false;
        btn.textContent = old;
      }
    });
  })();
})();
