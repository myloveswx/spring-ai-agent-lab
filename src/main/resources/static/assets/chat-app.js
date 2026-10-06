/* ==========================================================================
 * chat-app.js —— 驱动层
 *
 * 把 SCENES 里的声明渲染成可交互的助手界面：
 *   左栏选场景 → 头部显示「当前会打哪个接口」→ 配置条调参 → 对话区收发消息。
 *
 * 每个场景各自保留一份对话（DOM 复用），切换来回不丢上下文；
 * 场景内的参数（会话 ID、检索策略、知识库…）会存进 localStorage。
 * ========================================================================== */
(function () {
  'use strict';

  var h = C.h;
  var LS_KEY = 'agentlab.assistant.v1';

  var chat = C.$('#chat');
  var activeConvEl = null;

  var state = {
    sceneId: 'stage1',
    cfg: {},
    convs: {},
    kbs: [],
    defaultKb: 'default',
    busy: false
  };

  /* ------------------------------------------------------------ 基础工具 --- */

  function scene(id) {
    for (var i = 0; i < SCENES.length; i++) if (SCENES[i].id === id) return SCENES[i];
    return SCENES[0];
  }
  function cfg(id) { return state.cfg[id]; }

  function defaultCfg(sc) {
    var o = {};
    (sc.config || []).forEach(function (f) {
      if (f.default !== undefined) o[f.key] = f.default;
      else o[f.key] = f.type === 'switch' ? false : '';
    });
    return o;
  }

  /** 会话 ID 的默认值：每个场景一套，避免 stage2 / stage6 互相污染记忆。 */
  var CONV_SEED = { stage2: 'u1:web-', stage6: 'web-', stage6lab: 'lab-web-' };

  function persist() {
    try { localStorage.setItem(LS_KEY, JSON.stringify({ sceneId: state.sceneId, cfg: state.cfg })); } catch (e) { /* 隐私模式忽略 */ }
  }
  function restore() {
    try { return JSON.parse(localStorage.getItem(LS_KEY) || 'null'); } catch (e) { return null; }
  }

  function initCfg() {
    var saved = restore();
    SCENES.forEach(function (sc) {
      var base = defaultCfg(sc);
      var kept = saved && saved.cfg && saved.cfg[sc.id];
      if (kept) Object.keys(base).forEach(function (k) { if (kept[k] !== undefined) base[k] = kept[k]; });
      var seed = CONV_SEED[sc.id];
      if (seed && !base.conversationId) base.conversationId = seed + C.randomId('');
      state.cfg[sc.id] = base;
    });
    if (saved && saved.sceneId && scene(saved.sceneId)) state.sceneId = saved.sceneId;
  }

  function convOf(id) {
    if (!state.convs[id]) {
      state.convs[id] = {
        el: h('div', { style: { display: 'flex', flexDirection: 'column', gap: '16px' } }),
        msgs: []
      };
    }
    return state.convs[id];
  }

  function scrollToEnd(force) {
    var near = chat.scrollHeight - chat.scrollTop - chat.clientHeight < 140;
    if (force || near) chat.scrollTop = chat.scrollHeight;
  }

  /* ---------------------------------------------------------------- 头部 --- */

  function renderHead(sc) {
    C.$('#sceneNo').textContent = sc.badge || '';
    C.$('#sceneName').textContent = sc.name;
    C.$('#sceneTagline').textContent = sc.tagline || '';
    var label = typeof sc.endpointLabel === 'function' ? sc.endpointLabel(cfg(sc.id)) : (sc.endpointLabel || '');
    C.$('#sceneEndpoint').textContent = label;
  }

  function renderNav() {
    var nav = C.$('#sceneNav');
    C.clear(nav);
    SCENES.forEach(function (sc) {
      var btn = h('button', {
        class: 'scene-item', type: 'button', dataset: { scene: sc.id },
        onclick: function () { switchScene(sc.id); }
      }, [
        h('span', { class: 'scene-badge', text: sc.badge || '' }),
        h('span', { class: 'scene-meta' }, [
          h('b', { text: sc.name }),
          h('span', { text: sc.tagline || '' })
        ]),
        sc.needsKey ? h('span', { class: 'scene-flag', text: '需 Key' }) : null
      ]);
      nav.appendChild(btn);
    });
    updateNav();
  }

  function updateNav() {
    var items = document.querySelectorAll('.scene-item');
    for (var i = 0; i < items.length; i++) {
      items[i].classList.toggle('active', items[i].dataset.scene === state.sceneId);
    }
  }

  /* ------------------------------------------------------------ 配置与动作 --- */

  function afterCfgChange(sc) {
    renderHead(sc);
    renderConfig(sc);
    renderFoot(sc);
  }

  function renderConfig(sc) {
    var box = C.$('#sceneConfig');
    C.clear(box);
    var c = cfg(sc.id);

    (sc.config || []).forEach(function (f) {
      if (f.showIf && !f.showIf(c)) return;

      if (f.type === 'select') {
        var opts = f.optionsProvider ? f.optionsProvider(api) : (f.options || []);
        if (!opts.length) opts = [{ value: c[f.key] || '', label: '（暂无选项）' }];
        var sel = h('select', null, opts.map(function (o) {
          return h('option', { value: o.value, selected: String(c[f.key]) === String(o.value) }, o.label);
        }));
        sel.addEventListener('change', function () { c[f.key] = sel.value; persist(); afterCfgChange(sc); });
        box.appendChild(h('div', { class: 'cfg' }, [h('label', { text: f.label }), sel]));
        return;
      }

      if (f.type === 'switch') {
        var inp = h('input', { type: 'checkbox', checked: !!c[f.key] });
        inp.addEventListener('change', function () { c[f.key] = inp.checked; persist(); afterCfgChange(sc); });
        box.appendChild(h('label', { class: 'switch' }, [inp, h('span', { text: f.label })]));
        return;
      }

      var t = h('input', { type: 'text', value: c[f.key] || '', placeholder: f.placeholder || '' });
      // 文本类参数不整行重绘（否则每敲一个字符就丢焦点），只更新头部接口提示
      t.addEventListener('input', function () { c[f.key] = t.value; persist(); renderHead(sc); });
      t.addEventListener('keydown', function (e) { if (e.key === 'Enter') { e.preventDefault(); send(); } });
      box.appendChild(h('div', { class: 'cfg' }, [h('label', { text: f.label }), t]));
    });

    (sc.actions || []).forEach(function (act) {
      // 需要填参数的动作用内联表单：window.prompt 弹窗既不能选文件，
      // 也没法预填默认值和做必填校验，「上传 md」「新建知识库」这类动作必须走表单。
      if (act.form && act.form.length) {
        box.appendChild(actionFormSlot(sc, act));
        return;
      }
      var btn = h('button', {
        class: 'act-btn', type: 'button', text: act.label,
        onclick: function () { runAction(sc, act); }
      });
      act._btn = btn;
      box.appendChild(btn);
    });
  }

  /** 带表单的动作：先放按钮，点开才展开表单 —— 首屏仍然一眼能扫完所有动作。 */
  function actionFormSlot(sc, act) {
    var wrap = h('div', { class: 'act-form-slot' });
    var btn = h('button', {
      class: 'act-btn', type: 'button', text: act.label,
      onclick: function () { toggleActForm(sc, act, wrap); }
    });
    act._btn = btn;
    wrap.appendChild(btn);
    return wrap;
  }

  function toggleActForm(sc, act, wrap) {
    var opened = wrap.querySelector('.act-form');
    if (opened) { opened.remove(); return; }        // 再点一次收起
    if (state.busy) { C.toast('上一个请求还在跑，稍等'); return; }

    var fields = {};
    var panel = h('div', { class: 'act-form' });

    (act.form || []).forEach(function (f) {
      if (f.showIf && !f.showIf(cfg(sc.id))) return;

      var ctrl;
      if (f.type === 'file') {
        ctrl = h('input', { type: 'file', multiple: f.multiple !== false, accept: f.accept || '' });
      } else if (f.type === 'select') {
        ctrl = h('select', null, (f.options || []).map(function (o) {
          return h('option', { value: o.value, selected: String(f.default) === String(o.value) }, o.label);
        }));
      } else {
        // default 允许是函数：每次展开表单重新求值，才能给出「每次都不一样」的随机 id
        var def = typeof f.default === 'function' ? f.default() : (f.default || '');
        ctrl = h('input', { type: 'text', value: def, placeholder: f.placeholder || '' });
      }
      fields[f.key] = { spec: f, el: ctrl };
      panel.appendChild(h('div', { class: 'act-form-row' }, [
        h('label', { text: f.label + (f.required ? ' *' : '') }),
        ctrl,
        f.hint ? h('div', { class: 'act-hint', text: f.hint }) : null
      ]));
    });

    var submit = h('button', {
      class: 'act-go', type: 'button', text: act.submitLabel || '执行',
      onclick: function () {
        var values = {}, missing = null;
        Object.keys(fields).forEach(function (k) {
          var f = fields[k];
          values[k] = f.spec.type === 'file'
            ? Array.prototype.slice.call(f.el.files || [])
            : f.el.value;
          var empty = values[k] === '' || values[k] === null
            || (Array.isArray(values[k]) && !values[k].length);
          if (f.spec.required && empty && !missing) missing = f.spec.label;
        });
        if (missing) { C.toast('「' + missing + '」是必填的'); return; }
        panel.remove();
        runAction(sc, act, values);
      }
    });
    var cancel = h('button', {
      class: 'act-cancel', type: 'button', text: '取消',
      onclick: function () { panel.remove(); }
    });
    panel.appendChild(h('div', { class: 'act-form-actions' }, [submit, cancel]));
    wrap.appendChild(panel);
  }

  /**
   * 一键动作：不占用对话输入，但结果照常落进对话流（带「动作」标签）。
   *
   * @param values 表单动作收集到的值：text/select 是字符串，file 是 File[]。
   *               普通动作不传。
   */
  async function runAction(sc, act, values) {
    if (state.busy) { C.toast('上一个请求还在跑，稍等'); return; }
    var c = cfg(sc.id);
    var text = '';
    if (act.useText) {
      text = currentInputValue().trim();
      if (!text) { C.toast('这个动作要用输入框里的内容，先写点东西'); return; }
    }
    var call = act.submit ? act.submit(values || {}, c, sc)
             : act.custom ? act.custom({ app: api, cfg: c, scene: sc })
             : act.call(c, text);
    if (!call) return;

    var conv = convOf(sc.id);
    var body = h('div', { class: 'an-body' }, h('div', { class: 'typing' }, [h('i'), h('i'), h('i')]));
    var note = h('div', { class: 'action-note' }, [
      h('div', { class: 'an-head' }, [
        h('span', { class: 'an-tag', text: '动作' }),
        h('span', { text: act.label }),
        h('span', { class: 'hit-src', text: call.method + ' ' + C.shortPath(C.buildUrl(call.path, call.query)) })
      ]),
      body
    ]);
    conv.el.appendChild(note);
    conv.msgs.push({ role: 'action', node: note });
    scrollToEnd(true);

    state.busy = true; setBusyUi(true);
    var res = await C.req(call.method, call.path, { query: call.query, json: call.json, form: call.form });
    state.busy = false; setBusyUi(false);

    C.clear(body);
    if (!res.ok) {
      body.appendChild(errorContent(C.errorInfo(res)));
    } else {
      var node = (act.render && act.render(res, { app: api, cfg: c, scene: sc, values: values })) || genericContent(res);
      body.appendChild(node);
      body.appendChild(h('div', { class: 'msg-meta', style: { marginTop: '8px', padding: '0' } }, [
        h('span', { text: C.fmtMs(res.ms) }),
        h('span', { class: 'sep', text: '·' }),
        h('span', { text: String(res.status) })
      ]));
    }
    body.appendChild(C.jsonDetails('查看原始响应', res.data));
    if (call.after) call.after(api);
    scrollToEnd();
    return res;
  }

  /* ---------------------------------------------------------------- 输入 --- */

  function renderChips(sc) {
    var box = C.$('#chips');
    C.clear(box);
    (sc.samples || []).forEach(function (s) {
      var label = typeof s === 'string' ? s : (s.label || s.text);
      var text = typeof s === 'string' ? s : (s.text || s.label);
      box.appendChild(h('button', {
        class: 'chip', type: 'button', text: label, title: '点击直接发送',
        onclick: function () { send(text); }
      }));
    });
  }

  function renderComposer(sc) {
    var box = C.$('#composerBody');
    C.clear(box);

    if (sc.composer === 'picker') {
      box.appendChild(h('div', { class: 'picker' }, (sc.picker || []).map(function (p) {
        return h('button', {
          class: 'picker-btn', type: 'button',
          onclick: function () { send(p.value, '分析 ' + p.label + '（' + p.value + '）'); }
        }, [h('span', { text: p.label }), h('small', { text: p.hint || p.value })]);
      })));
      return;
    }

    var ta = h('textarea', { id: 'input', rows: 1, placeholder: sc.placeholder || '说点什么…' });
    ta.addEventListener('keydown', function (e) {
      // isComposing / keyCode 229：中文输入法候选框里的回车不能当成发送
      if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); send(); }
    });
    ta.addEventListener('input', autosize);
    var btn = h('button', { class: 'send-btn', id: 'sendBtn', type: 'button', text: '发送', onclick: function () { send(); } });
    box.appendChild(h('div', { class: 'composer-box' }, [ta, btn]));
  }

  function renderFoot(sc) {
    var foot = C.$('#composerFoot');
    C.clear(foot);
    var bits = [];
    if (sc.composer !== 'picker') bits.push('Enter 发送 · Shift+Enter 换行');
    bits.push('示例问法点一下就直接发出');
    if (sc.needsKey) bits.push('本场景需要真实 DEEPSEEK_API_KEY');
    if (sc.footer) bits.push(sc.footer);
    bits.forEach(function (b, i) {
      if (i) foot.appendChild(h('span', { text: ' · ' }));
      foot.appendChild(h('span', { text: b }));
    });
    foot.appendChild(h('button', {
      class: 'act-btn', type: 'button', text: '清空本场景对话',
      style: { marginLeft: '10px' },
      onclick: function () { clearConversation(sc); }
    }));
  }

  function autosize() {
    var ta = C.$('#input');
    if (!ta) return;
    ta.style.height = 'auto';
    ta.style.height = Math.min(160, Math.max(22, ta.scrollHeight)) + 'px';
  }

  function currentInputValue() {
    var ta = C.$('#input');
    return ta ? ta.value : '';
  }

  function clearInput() {
    var ta = C.$('#input');
    if (ta) { ta.value = ''; autosize(); }
  }

  function setBusyUi(busy) {
    var btn = C.$('#sendBtn');
    if (btn) btn.disabled = busy;
    document.querySelectorAll('.act-btn, .act-go, .picker-btn').forEach(function (b) { b.disabled = busy; });
    var ta = C.$('#input');
    if (ta) ta.disabled = false;   // 输入框保持可打字，只是发不出去
  }

  /* ---------------------------------------------------------------- 对话 --- */

  function pushUser(sc, text) {
    var conv = convOf(sc.id);
    var node = h('div', { class: 'msg user' }, [
      h('div', { class: 'avatar', text: '我' }),
      h('div', { class: 'msg-body' }, h('div', { class: 'bubble', html: C.md(text) }))
    ]);
    conv.el.appendChild(node);
    conv.msgs.push({ role: 'user', node: node, text: text });
    scrollToEnd(true);
  }

  function beginAssistant(sc) {
    var conv = convOf(sc.id);
    var bubble = h('div', { class: 'bubble' }, h('div', { class: 'typing' }, [h('i'), h('i'), h('i')]));
    var meta = h('div', { class: 'msg-meta' });
    var node = h('div', { class: 'msg assistant' }, [
      h('div', { class: 'avatar', text: 'AI' }),
      h('div', { class: 'msg-body' }, [bubble, meta])
    ]);
    conv.el.appendChild(node);
    var m = { role: 'assistant', node: node, body: bubble, meta: meta, text: '' };
    conv.msgs.push(m);
    scrollToEnd(true);
    return m;
  }

  function metaLine(m, res, call, extra) {
    C.clear(m.meta);
    var parts = [call.method + ' ' + C.shortPath(C.buildUrl(call.path, call.query))];
    if (res && res.ms !== undefined && res.ms !== null) parts.push(C.fmtMs(res.ms));
    if (res && res.status) parts.push('HTTP ' + res.status);
    if (res && res.contentType) parts.push(res.contentType.split(';')[0]);
    if (call.note) parts.push(call.note);
    if (extra) parts.push(extra);
    parts.forEach(function (p, i) {
      if (i) m.meta.appendChild(h('span', { class: 'sep', text: '·' }));
      m.meta.appendChild(h('span', { text: p }));
    });
  }

  /* ------------------------------------------------------------ 发送链路 --- */

  async function send(text, display) {
    var sc = scene(state.sceneId);
    var value = text === undefined ? currentInputValue() : text;
    value = (value === null || value === undefined) ? '' : String(value).trim();
    if (!value) { C.toast('先输入点什么'); return; }
    if (state.busy) { C.toast('上一个请求还在跑，稍等一下'); return; }

    pushUser(sc, display || value);
    clearInput();

    var call = sc.call(cfg(sc.id), value);
    if (call.sse) { sendStream(sc, call); return; }

    var m = beginAssistant(sc);
    state.busy = true; setBusyUi(true);
    var res = await C.req(call.method, call.path, { query: call.query, json: call.json });
    state.busy = false; setBusyUi(false);

    m.body.className = res.ok ? 'bubble' : 'bubble err';
    C.clear(m.body);
    if (!res.ok) {
      m.body.appendChild(errorContent(C.errorInfo(res)));
    } else {
      var custom = sc.render ? sc.render(res, m, api) : null;
      m.body.appendChild(custom || genericContent(res));
    }
    metaLine(m, res, call);
    scrollToEnd();
  }

  /** 流式（SSE）：逐块追加 + 用 requestAnimationFrame 节流重绘，避免每块都重排。 */
  function sendStream(sc, call) {
    var m = beginAssistant(sc);
    m.buf = '';
    var caret = h('span', { class: 'caret' });
    var pending = false;

    function paint() {
      pending = false;
      m.body.innerHTML = C.md(m.buf);
      m.body.appendChild(caret);
      scrollToEnd();
    }

    state.busy = true; setBusyUi(true);

    C.stream(call.path, call.query, {
      onDelta: function (t) {
        m.buf += t;
        if (!pending) { pending = true; requestAnimationFrame(paint); }
      },
      onDone: function (info) {
        state.busy = false; setBusyUi(false);
        if (caret.parentNode) caret.remove();
        m.body.innerHTML = C.md(m.buf || '（没有收到内容）');
        metaLine(m, { ms: info.ms, contentType: 'text/event-stream' }, call, m.buf.length + ' 字符');
        scrollToEnd();
      },
      onError: function (err) {
        state.busy = false; setBusyUi(false);
        if (caret.parentNode) caret.remove();
        m.body.className = 'bubble err';
        C.clear(m.body);
        m.body.appendChild(errorContent(err));
        metaLine(m, null, call);
      }
    });
  }

  /* -------------------------------------------------------------- 结果渲染 --- */

  function errorContent(info) {
    return h('div', null, [
      h('div', { class: 'err-head' }, [h('span', { text: '请求失败' }), info.title ? h('span', { class: 'hit-src', text: info.title }) : null]),
      info.detail ? h('div', { class: 'err-body', text: info.detail }) : null,
      info.hint ? h('div', { class: 'err-hint', text: '试试这样解决：' + info.hint }) : null
    ]);
  }

  /** 默认渲染：纯文本走 Markdown，对象平铺展示 + 折叠原始 JSON。 */
  function genericContent(res) {
    var d = res.data;
    if (typeof d === 'string') {
      return h('div', { html: C.md(d || '（空响应）') });
    }
    if (d && typeof d === 'object') {
      var flat = {};
      Object.keys(d).forEach(function (k) {
        var v = d[k];
        if (v === null || typeof v !== 'object') flat[k] = v;
      });
      var box = h('div');
      if (Object.keys(flat).length) box.appendChild(kvView(flat));
      box.appendChild(C.jsonDetails('查看完整响应', d));
      return box;
    }
    return h('div', { text: '（空响应）' });
  }

  function kvView(obj) {
    var box = h('div', { class: 'kv' });
    Object.keys(obj).forEach(function (k) {
      box.appendChild(h('div', null, [h('span', { text: k }), h('b', { text: String(obj[k]) })]));
    });
    return box;
  }

  /* ---------------------------------------------------------------- 引导 --- */

  function showIntro(sc, conv) {
    var card = h('div', { class: 'intro' });
    var inner = h('div', { class: 'intro-card' }, [h('h3', { text: sc.intro.title })]);
    var ul = h('ul');
    (sc.intro.bullets || []).forEach(function (b) { ul.appendChild(h('li', { html: b })); });
    inner.appendChild(ul);

    var label = typeof sc.endpointLabel === 'function' ? sc.endpointLabel(cfg(sc.id)) : sc.endpointLabel;
    inner.appendChild(h('div', { style: { marginTop: '10px', fontSize: '11.5px', color: 'var(--text-3)' } }, [
      h('span', { text: '顶部「' + sc.badge + '」旁边就是此刻要打的接口，下面的配置条可以现场调参。' }),
      label ? h('div', { style: { marginTop: '4px' } }, h('code', { text: label })) : null
    ]));

    card.appendChild(inner);
    if (sc.intro.warn) card.appendChild(h('div', { class: 'intro-warn', text: sc.intro.warn }));
    conv.el.appendChild(card);
    conv.msgs.push({ role: 'intro', node: card });
  }

  function clearConversation(sc) {
    var conv = state.convs[sc.id];
    if (!conv) return;
    C.clear(conv.el);
    conv.msgs = [];
    showIntro(sc, conv);
    C.toast('已清空本场景的对话（服务端的记忆/知识库不受影响）');
  }

  /* ------------------------------------------------------------ 场景切换 --- */

  function switchScene(id) {
    var sc = scene(id);
    state.sceneId = id;
    persist();

    if (activeConvEl && activeConvEl.parentNode) activeConvEl.remove();
    renderHead(sc);
    renderConfig(sc);
    renderChips(sc);
    renderComposer(sc);
    renderFoot(sc);
    updateNav();

    var conv = convOf(id);
    chat.appendChild(conv.el);
    activeConvEl = conv.el;
    if (!conv.msgs.length) showIntro(sc, conv);
    chat.scrollTop = chat.scrollHeight;

    if (sc.setup) { try { sc.setup(api); } catch (e) { /* 场景自身的准备工作失败不影响切换 */ } }

    var ta = C.$('#input');
    if (ta) ta.focus();
  }

  /* -------------------------------------------------------------- 后端探测 --- */

  async function refreshKbs() {
    var res = await C.req('GET', '/stage8/kb');
    if (res.ok && res.data) {
      state.kbs = res.data.knowledgeBases || [];
      state.defaultKb = res.data.defaultId || 'default';
      var c = cfg('stage8');
      if (c && !state.kbs.some(function (k) { return k.id === c.kbId; })) c.kbId = state.defaultKb;
    }
    if (state.sceneId === 'stage8') { renderConfig(scene('stage8')); renderHead(scene('stage8')); }
    return res;
  }

  function kbOptions() {
    if (!state.kbs.length) return [{ value: cfg('stage8').kbId || state.defaultKb, label: cfg('stage8').kbId || state.defaultKb }];
    return state.kbs.map(function (k) {
      var suffix = (k.documents >= 0) ? '（' + k.documents + ' 篇 / ' + k.chunks + ' 块）' : '（未加载）';
      return { value: k.id, label: k.id + suffix };
    });
  }

  async function probeHealth() {
    var res = await C.req('GET', '/stage8/kb');
    var box = C.$('#health');
    C.clear(box);
    var ok = res.ok;
    box.appendChild(h('span', { class: 'dot ' + (ok ? 'dot-ok' : 'dot-err') }));
    box.appendChild(h('span', {
      class: 'health-text',
      text: ok
        ? ('后端已连接 · ' + location.host + ' · ' + res.ms + ' ms · ' + ((res.data && res.data.count) || 0) + ' 个知识库')
        : ('后端未就绪 · HTTP ' + res.status + '（确认应用已在当前端口启动）')
    }));
    if (ok && res.data) {
      state.kbs = res.data.knowledgeBases || [];
      state.defaultKb = res.data.defaultId || 'default';
    }
  }

  /* ----------------------------------------------------------------- API --- */

  /** 暴露给场景声明用的最小接口面（render / actions 里能用到的东西）。 */
  var api = {
    cfg: function (id) { return cfg(id); },
    setCfg: function (id, patch) {
      Object.assign(cfg(id), patch);
      persist();
      if (state.sceneId === id) { renderConfig(scene(id)); renderHead(scene(id)); }
    },
    send: send,
    toast: C.toast,
    refreshKbs: refreshKbs,
    kbOptions: kbOptions
  };

  /* ------------------------------------------------------------ 深链参数 --- */

  /**
   * 支持 #scene=<id>&run=1&act=<n>&cfg=k:v,k:v
   *   scene  指定场景      run  自动发送第一条示例问法
   *   act    执行第 n 个「动作」按钮（useText 的动作借用第一条示例当输入）
   *   cfg    覆盖场景参数，例如 cfg=mode:compare
   * 用来分享「点开就是某个场景」的链接，也方便批量截图核对。
   */
  function applyHash() {
    var hash = (location.hash || '').replace(/^#/, '');
    if (!hash) return;
    var p = {};
    hash.split('&').forEach(function (kv) {
      var i = kv.indexOf('=');
      if (i > 0) p[kv.slice(0, i)] = decodeURIComponent(kv.slice(i + 1));
    });

    var target = p.scene ? scene(p.scene) : null;
    if (target && p.cfg) {
      p.cfg.split(',').forEach(function (kv) {
        var i = kv.indexOf(':');
        if (i < 0) return;
        var k = kv.slice(0, i), v = kv.slice(i + 1);
        if (state.cfg[target.id][k] === undefined) return;
        state.cfg[target.id][k] = v === 'true' ? true : (v === 'false' ? false : v);
      });
    }
    if (target) {
      if (target.id !== state.sceneId) switchScene(target.id);
      else { renderConfig(target); renderHead(target); }
      persist();
    }

    var sc = scene(state.sceneId);
    var sample = (sc.samples && sc.samples[0]) || '';

    if (p.act !== undefined) {
      var act = (sc.actions || [])[Number(p.act)];
      if (act) {
        if (act.useText) { var ta = C.$('#input'); if (ta) { ta.value = sample; autosize(); } }
        setTimeout(function () { runAction(sc, act); }, 260);
      }
      return;
    }
    if (p.run) {
      setTimeout(function () {
        if (sc.composer === 'picker' && sc.picker && sc.picker.length) {
          send(sc.picker[0].value, '分析 ' + sc.picker[0].label + '（' + sc.picker[0].value + '）');
        } else {
          send(sample);
        }
      }, 320);
    }
  }

  /* ---------------------------------------------------------------- 启动 --- */

  initCfg();
  renderNav();
  switchScene(state.sceneId);
  autosize();
  applyHash();
  probeHealth();

  // 后端起来了但页面先开的情况：隔一会儿再探一次
  setTimeout(probeHealth, 4000);
})();
