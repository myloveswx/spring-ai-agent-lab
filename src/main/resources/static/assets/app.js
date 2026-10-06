/* ==========================================================================
   app.js —— 主驱动
   把 registry.js 里的声明渲染成可交互页面，并负责请求编排与状态同步。
   ========================================================================== */

(function (global) {
  'use strict';

  var C = global.Core;
  var h = C.h;

  var state = {
    currentKb: 'default',
    kbs: [],
    defaultId: 'default',
    ready: false
  };

  var cards = {};        // id -> { ep, form, resultEl }
  var kbSelectEl = null; // 顶部的「当前知识库」下拉

  /* ================================================================ 上下文 */

  var ctx = {
    currentKb: function () { return state.currentKb; },
    setKb: function (id) {
      if (!id || id === state.currentKb) return;
      state.currentKb = id;
      syncKbUi();
      C.toast('已切换到知识库：' + id, 'ok');
      refreshDocOptions();
    },
    runEndpoint: function (id) {
      var card = cards[id];
      if (!card) return;
      openCard(card, true);
      setTimeout(function () { execute(card); }, 80);
    }
  };

  /* ================================================================ 顶栏探测 */

  function probeHealth() {
    var box = C.$('#health');
    C.api('GET', '/stage8/kb').then(function (res) {
      box.innerHTML = '';
      var ok = res.ok;
      // 404 不代表服务挂了 —— 很可能是 Stage 8 被 agentlab.rag.enabled=false 关掉了。
      var ragOff = res.status === 404;
      var cls = ok ? 'dot-ok' : (ragOff ? 'dot-wait' : 'dot-err');
      var text = ok
        ? ('后端已连接 · ' + location.host + ' · ' + res.ms + ' ms')
        : (ragOff
          ? '后端已连接，但 Stage 8 未启用（agentlab.rag.enabled=false）'
          : '后端未就绪 · HTTP ' + res.status + '（确认应用已在 8090 端口启动）');
      box.appendChild(h('span', { class: 'dot ' + cls }));
      box.appendChild(h('span', { class: 'health-text', text: text }));
      if (ok && res.data) {
        state.kbs = res.data.knowledgeBases || [];
        state.defaultId = res.data.defaultId || 'default';
        if (!state.kbs.some(function (k) { return k.id === state.currentKb; })) {
          state.currentKb = state.defaultId;
        }
        syncKbUi();
      }
    });
  }

  /* ================================================================ 阶段导航 */

  function renderStageNav() {
    var nav = C.$('#stageNav');
    nav.innerHTML = '';
    (global.STAGES || []).forEach(function (s) {
      var item = h('button', {
        class: 'stage-item' + (s.ready ? (s.key === 'stage8' ? ' active' : '') : ' disabled'),
        type: 'button',
        onclick: function () {
          if (!s.ready) { C.toast('Stage ' + s.no + ' 的界面还没做，接口本身是可用的（见 README / Swagger）', ''); return; }
          C.$$('.stage-item', nav).forEach(function (x) { x.classList.remove('active'); });
          item.classList.add('active');
          renderStage(s.key);
        }
      }, [
        h('span', { class: 'stage-no', text: s.no }),
        h('span', { class: 'stage-label', text: s.label }),
        h('span', { class: 'stage-tag', text: s.tag })
      ]);
      nav.appendChild(item);
    });
  }

  /* ================================================================ 阶段页面 */

  function renderStage(key) {
    var def = (global.LAB || {})[key];
    var main = C.$('#main');
    main.innerHTML = '';
    if (!def) {
      main.appendChild(h('div', { class: 'placeholder' }, [h('h2', { text: '这个阶段还没实现界面' })]));
      return;
    }

    var epCount = def.groups.reduce(function (n, g) { return n + g.items.length; }, 0);

    var head = h('div', { class: 'page-head' }, [
      h('h2', { text: def.title }),
      h('p', { text: def.intro }),
      h('div', { class: 'meta-row' }, [
        h('span', { class: 'pill' }, [h('b', { text: String(def.groups.length) }), ' 个学习分组']),
        h('span', { class: 'pill' }, [h('b', { text: String(epCount) }), ' 个接口']),
        h('span', { class: 'pill' }, [h('b', { text: '不调模型' }), ' 的接口可离线验证']),
        h('span', { class: 'pill' }, [h('b', { text: '同源' }), ' 请求，无跨域配置'])
      ]),
      buildCtxBar()
    ]);

    if (def.notes && def.notes.length) {
      head.appendChild(h('div', { class: 'note-bar', style: 'margin-top:14px' }, [
        h('div', { text: def.notes[0] }),
        h('div', { style: 'margin-top:5px', text: def.notes[1] }),
        h('div', { style: 'margin-top:5px', text: def.notes[2] })
      ]));
    }

    main.appendChild(head);
    def.groups.forEach(function (g) { main.appendChild(renderGroup(g, def.groups)); });

    refreshDocOptions();
    openFromHash();
  }

  /**
   * 支持 #ep-<id> 直接展开某个接口卡片（便于分享链接与截图核对）。
   * 加 !run 后缀可顺便自动执行一次 —— 只对 GET 生效，避免一个链接就能删掉数据。
   * 加 ?shot=1 会关掉吸顶布局，方便无头浏览器一次性截全页。
   */
  function shotMode() { return /[?&]shot=1/.test(location.search); }

  function openFromHash() {
    if (shotMode()) document.body.classList.add('no-sticky');
    var m = (location.hash || '').match(/^#ep-([A-Za-z0-9_-]+)(!run)?$/);
    if (!m) return;
    var card = cards[m[1]];
    if (!card) return;
    openCard(card, true);
    if (!shotMode()) {
      setTimeout(function () { card.el.scrollIntoView({ block: 'center' }); }, 60);
    }
    if (m[2] && card.ep.method === 'GET') {
      setTimeout(function () { execute(card); }, 220);
    }
  }

  /** 顶部的「当前知识库」控制条。 */
  function buildCtxBar() {
    kbSelectEl = h('select', { style: 'max-width:320px' });
    var bar = h('div', {
      style: 'display:flex;align-items:center;gap:9px;flex-wrap:wrap;margin-top:14px;'
           + 'padding:10px 12px;background:#fff;border:1px solid var(--line);border-radius:12px'
    }, [
      h('span', { style: 'font-size:12.5px;color:var(--text-2)', text: '当前知识库' }),
      kbSelectEl,
      h('button', {
        class: 'btn ghost mini', text: '刷新库列表', type: 'button',
        onclick: function () { loadKbs(); }
      }),
      h('button', {
        class: 'btn ghost mini', text: '新建一个库', type: 'button',
        onclick: function () {
          var card = cards['kb-create'];
          if (card) { openCard(card, true); card.el.scrollIntoView({ behavior: 'smooth', block: 'center' }); }
        }
      }),
      h('span', { style: 'font-size:11.5px;color:var(--text-3)', text: '带 {kbId} 的接口都会用这个值' })
    ]);
    kbSelectEl.addEventListener('change', function () { ctx.setKb(kbSelectEl.value); });
    setTimeout(syncKbUi, 0);
    return bar;
  }

  function syncKbUi() {
    if (!kbSelectEl) return;
    kbSelectEl.innerHTML = '';
    if (!state.kbs.length) {
      kbSelectEl.appendChild(h('option', { value: '', text: '（还没有可用的库）' }));
      return;
    }
    state.kbs.forEach(function (k) {
      var opt = h('option', { value: k.id, text: k.id + '　—　' + (k.name || '未命名') });
      if (k.id === state.currentKb) opt.selected = true;
      kbSelectEl.appendChild(opt);
    });
  }

  function loadKbs() {
    return C.api('GET', '/stage8/kb').then(function (res) {
      if (!res.ok) { C.toast('读取知识库列表失败：HTTP ' + res.status, 'bad'); return res; }
      state.kbs = (res.data && res.data.knowledgeBases) || [];
      state.defaultId = (res.data && res.data.defaultId) || 'default';
      if (!state.kbs.some(function (k) { return k.id === state.currentKb; })) {
        state.currentKb = state.defaultId;
      }
      syncKbUi();
      return res;
    });
  }

  /* ================================================================ 分组卡片 */

  function renderGroup(group, allGroups) {
    var wrap = h('div', { class: 'group-wrap' });
    wrap.appendChild(h('div', { class: 'group-head' }, [
      h('span', { class: 'group-no', text: '0' + group.no }),
      h('h3', { text: group.title }),
      h('span', { class: 'group-why', text: group.why })
    ]));
    group.items.forEach(function (ep) { wrap.appendChild(renderEndpoint(ep)); });
    return wrap;
  }

  function renderPath(path) {
    var node = h('span', { class: 'ep-path' });
    var parts = String(path).split(/(\{[^}]+\})/);
    parts.forEach(function (p) {
      if (/^\{[^}]+\}$/.test(p)) node.appendChild(h('span', { class: 'var', text: p }));
      else node.appendChild(document.createTextNode(p));
    });
    return node;
  }

  var CARET = '<svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" '
            + 'stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M6 3l5 5-5 5"/></svg>';

  function renderEndpoint(ep) {
    var head = h('div', { class: 'ep-head' }, [
      h('span', { class: 'method m-' + ep.method, text: ep.method }),
      renderPath(ep.path),
      h('span', { class: 'ep-title', text: ep.title }),
      h('span', { class: 'ep-caret', html: CARET })
    ]);

    var teach = h('div', { class: 'teach' }, [
      h('div', { class: 'teach-label', text: '这个接口在做什么' }),
      h('p', { text: ep.why }),
      ep.watch ? h('p', { class: 'watch', text: ep.watch }) : null
    ]);

    var form = C.buildForm(ep.params || [], null);
    var actions = h('div', { class: 'actions' });
    var runBtn = h('button', {
      class: 'btn', type: 'button', text: '调用这个接口',
      onclick: function () { execute(cards[ep.id]); }
    });
    actions.appendChild(runBtn);
    if (ep.params && ep.params.length) {
      actions.appendChild(h('button', {
        class: 'btn ghost', type: 'button', text: '恢复默认值',
        onclick: function () { form.reset(); }
      }));
    }

    var left = h('div', null, [form.el, actions]);

    var resultEl = h('div', { class: 'result' }, [
      h('div', { class: 'result-empty', text: '还没有调用过。点左边的按钮试试。' })
    ]);

    var grid = h('div', { class: 'ep-grid' }, [left, resultEl]);
    var body = h('div', { class: 'ep-body' }, [teach, grid]);
    var el = h('div', { class: 'ep', id: 'ep-' + ep.id }, [head, body]);

    head.addEventListener('click', function () {
      var willOpen = !el.classList.contains('open');
      el.classList.toggle('open', willOpen);
      if (willOpen) refreshDocOptions();
    });

    cards[ep.id] = { id: ep.id, ep: ep, form: form, resultEl: resultEl, el: el };
    return el;
  }

  function openCard(card, open) {
    if (!card) return;
    card.el.classList.toggle('open', open !== false);
  }

  /* ================================================================ 动态选项 */

  function refreshDocOptions() {
    var docParamed = Object.keys(cards).map(function (k) { return cards[k]; })
      .filter(function (c) { return (c.ep.params || []).some(function (p) { return p.source === 'docs'; }); });
    if (!docParamed.length) return;

    C.api('GET', '/stage8/kb/' + encodeURIComponent(state.currentKb) + '/docs').then(function (res) {
      var docs = Array.isArray(res.data) ? res.data : [];
      var opts = docs.map(function (d) {
        return { value: d.docId, label: d.title + '　·　' + d.docId.slice(0, 8) + '…' + '（' + d.chunks + ' 块）' };
      });
      if (!opts.length) {
        opts = [{ value: '', label: '（这个库还没有文档 —— 先执行第 2 组的「载入内置示例语料」）' }];
      }
      docParamed.forEach(function (c) {
        (c.ep.params || []).forEach(function (p) {
          if (p.source === 'docs') c.form.setOptions(p.name, opts);
        });
      });
    });
  }

  /* ================================================================ 请求执行 */

  function execute(card) {
    if (!card) return;
    var ep = card.ep;

    var missing = card.form.firstMissing();
    if (missing) { C.toast('请先填写：' + missing, 'bad'); return; }
    if (ep.confirm && !global.confirm(ep.confirm)) return;

    var values = card.form.values();
    var path = String(ep.path).replace('{kbId}', encodeURIComponent(state.currentKb));
    var query = {};
    var body = null;

    (ep.params || []).forEach(function (p) {
      var v = values[p.name];
      if (p.in === 'path') {
        if (v !== undefined) path = path.replace('{' + p.name + '}', encodeURIComponent(v));
      } else if (p.in === 'body') {
        if (v !== undefined) { body = body || {}; body[p.name] = v; }
      } else {
        if (v !== undefined) query[p.name] = v;
      }
    });

    var preview = h('div', { class: 'req-line' }, [
      h('span', { class: 'm', text: ep.method + ' ' }),
      h('span', { class: 'u', text: C.composeUrl(path, query) }),
      body ? h('span', { text: '　+ JSON body ' + JSON.stringify(body).length + ' 字符' }) : null
    ]);

    var btn = C.$('button.btn', card.el);
    var oldText = btn.textContent;
    btn.disabled = true;
    btn.innerHTML = '';
    btn.appendChild(h('span', { class: 'spin' }));
    btn.appendChild(document.createTextNode('调用中…'));

    card.resultEl.innerHTML = '';
    card.resultEl.appendChild(h('div', { class: 'result-empty', text: '请求中…' }));

    C.api(ep.method, path, { query: query, body: body }).then(function (res) {
      btn.disabled = false;
      btn.textContent = oldText;
      renderResult(card, res, preview);
      handleAfter(ep, res);
    });
  }

  function renderResult(card, res, preview) {
    var box = card.resultEl;
    box.innerHTML = '';
    box.appendChild(C.resultHead(res));
    if (preview) box.appendChild(preview);

    if (!res.ok) {
      box.appendChild(C.errorBlock(res));
      box.appendChild(rawToggle(res));
      C.toast(card.ep.method + ' ' + card.ep.path + ' → HTTP ' + res.status, 'bad');
      return;
    }

    var renderer = (global.Renderers && global.Renderers[card.ep.renderer]) || global.Renderers.json;
    try {
      box.appendChild(renderer(res.data, ctx));
    } catch (e) {
      box.appendChild(h('div', { class: 'res-error', text: '渲染出错：' + e.message }));
      box.appendChild(C.jsonView(res.data));
    }
    box.appendChild(rawToggle(res));
    C.toast('调用成功 · ' + res.ms + ' ms', 'ok');
  }

  function rawToggle(res) {
    var d = h('details', { style: 'margin-top:11px' });
    d.appendChild(h('summary', { style: 'cursor:pointer;font-size:11.5px;color:var(--text-3)',
      text: '展开原始 JSON 响应（' + (res.raw ? res.raw.length : 0) + ' 字符）' }));
    d.appendChild(h('div', { style: 'margin-top:8px' }, [C.jsonView(res.data)]));
    return d;
  }

  function handleAfter(ep, res) {
    if (!res.ok) return;
    if (ep.after === 'reloadKbs') {
      loadKbs().then(refreshDocOptions);
    } else if (ep.after === 'reloadDocs') {
      refreshDocOptions();
    }
  }

  /* ================================================================ 启动 */

  function boot() {
    renderStageNav();
    renderStage('stage8');
    probeHealth();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})(window);
