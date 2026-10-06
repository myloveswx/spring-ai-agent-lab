/* ==========================================================================
   core.js —— 接口实验室的底层工具
   不依赖任何第三方库：DOM 构建、请求封装、表单生成、JSON 折叠视图。
   ========================================================================== */

(function (global) {
  'use strict';

  /* ------------------------------------------------------------ DOM 工具 --- */

  function $(sel, root) { return (root || document).querySelector(sel); }
  function $$(sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); }

  /**
   * DOM 构建器。h('div.cls', {attrs}, [children])
   * 支持 class / text / html / style / dataset / onXxx / 普通属性。
   */
  function h(tag, attrs, children) {
    var el = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (k) {
        var v = attrs[k];
        if (v === null || v === undefined || v === false) return;
        if (k === 'class') el.className = v;
        else if (k === 'text') el.textContent = v;
        else if (k === 'html') el.innerHTML = v;
        else if (k === 'style') el.style.cssText = v;
        else if (k === 'dataset') Object.keys(v).forEach(function (d) { el.dataset[d] = v[d]; });
        else if (k.indexOf('on') === 0 && typeof v === 'function') el.addEventListener(k.slice(2).toLowerCase(), v);
        else el.setAttribute(k, v);
      });
    }
    append(el, children);
    return el;
  }

  function append(el, children) {
    if (children === null || children === undefined || children === false) return;
    if (Array.isArray(children)) { children.forEach(function (c) { append(el, c); }); return; }
    if (children instanceof Node) { el.appendChild(children); return; }
    el.appendChild(document.createTextNode(String(children)));
  }

  function esc(s) {
    return String(s === null || s === undefined ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function fmtBytes(n) {
    if (typeof n !== 'number' || isNaN(n)) return '—';
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
    return (n / 1024 / 1024).toFixed(2) + ' MB';
  }

  function fmtTime(ms) {
    if (ms === null || ms === undefined) return '—';
    if (ms < 1000) return ms + ' ms';
    return (ms / 1000).toFixed(2) + ' s';
  }

  /* ---------------------------------------------------------------- 请求 --- */

  /**
   * 把 method/path/query 拼成一个可读的 URL（用于请求预览，不发送）。
   * query 里 null / undefined / '' 的项会被丢掉 —— 与后端「不传就用默认值」的语义一致。
   */
  function composeUrl(path, query) {
    var url = path;
    if (query) {
      var parts = [];
      Object.keys(query).forEach(function (k) {
        var v = query[k];
        if (v === null || v === undefined || v === '') return;
        parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(v));
      });
      if (parts.length) url += (url.indexOf('?') >= 0 ? '&' : '?') + parts.join('&');
    }
    return url;
  }

  /**
   * 发一个请求，统一返回 { ok, status, ms, data, raw, url, method, error }。
   * 不抛异常 —— 调用方永远拿到一个可渲染的结果对象。
   */
  function api(method, path, opts) {
    opts = opts || {};
    var url = composeUrl(path, opts.query);
    var init = { method: method, headers: {} };
    if (opts.body !== undefined && opts.body !== null) {
      init.headers['Content-Type'] = 'application/json;charset=UTF-8';
      init.body = JSON.stringify(opts.body);
    }

    var t0 = (global.performance && performance.now) ? performance.now() : Date.now();
    return fetch(url, init).then(function (res) {
      return res.text().then(function (text) {
        var ms = Math.round(((global.performance && performance.now) ? performance.now() : Date.now()) - t0);
        var data = null;
        if (text) { try { data = JSON.parse(text); } catch (e) { data = text; } }
        return { ok: res.ok, status: res.status, ms: ms, data: data, raw: text, url: url, method: method };
      });
    }).catch(function (e) {
      var ms = Math.round(((global.performance && performance.now) ? performance.now() : Date.now()) - t0);
      return {
        ok: false, status: 0, ms: ms, data: null, raw: '',
        url: url, method: method,
        error: '请求发不出去：' + (e && e.message ? e.message : e)
          + '（确认后端已在 8090 端口启动）'
      };
    });
  }

  /* ------------------------------------------------------------ 提示浮层 --- */

  function toast(msg, type) {
    var box = $('#toasts');
    if (!box) return;
    var t = h('div', { class: 'toast ' + (type || ''), text: msg });
    box.appendChild(t);
    setTimeout(function () {
      t.style.opacity = '0';
      t.style.transition = 'opacity .3s';
      setTimeout(function () { if (t.parentNode) t.parentNode.removeChild(t); }, 320);
    }, 2800);
  }

  /* ------------------------------------------------------- JSON 折叠视图 --- */

  function span(text, cls) { return h('span', { class: cls, text: text }); }

  function summaryNode(text) {
    return h('summary', null, [span(text, 'p')]);
  }

  function jsonNode(v, depth) {
    if (v === null) return span('null', 'b');
    var t = typeof v;
    if (t === 'string') return span(JSON.stringify(v), 's');
    if (t === 'number') return span(String(v), 'n');
    if (t === 'boolean') return span(String(v), 'b');

    if (Array.isArray(v)) {
      if (!v.length) return span('[]', 'p');
      var da = h('details');
      if (depth < 2) da.open = true;
      da.appendChild(summaryNode('数组 · ' + v.length + ' 项'));
      v.forEach(function (item, i) {
        var row = h('div');
        row.appendChild(span('[' + i + '] ', 'k'));
        row.appendChild(jsonNode(item, depth + 1));
        da.appendChild(row);
      });
      return da;
    }

    if (t === 'object') {
      var keys = Object.keys(v);
      if (!keys.length) return span('{}', 'p');
      var dobj = h('details');
      if (depth < 2) dobj.open = true;
      dobj.appendChild(summaryNode('对象 · ' + keys.length + ' 字段'));
      keys.forEach(function (k) {
        var row = h('div');
        row.appendChild(span(k + ': ', 'k'));
        row.appendChild(jsonNode(v[k], depth + 1));
        dobj.appendChild(row);
      });
      return dobj;
    }
    return span(String(v), 'p');
  }

  /** 顶层对象直接铺开成一列 key: value，嵌套层才折叠。 */
  function jsonView(value) {
    var wrap = h('div', { class: 'json' });
    if (value && typeof value === 'object' && !Array.isArray(value)) {
      Object.keys(value).forEach(function (k) {
        var row = h('div');
        row.appendChild(span(k + ': ', 'k'));
        row.appendChild(jsonNode(value[k], 1));
        wrap.appendChild(row);
      });
    } else if (Array.isArray(value)) {
      if (!value.length) { wrap.appendChild(span('[] 空数组', 'p')); return wrap; }
      value.forEach(function (v, i) {
        var row = h('div');
        row.appendChild(span('[' + i + '] ', 'k'));
        row.appendChild(jsonNode(v, 1));
        wrap.appendChild(row);
      });
    } else {
      wrap.appendChild(jsonNode(value, 1));
    }
    return wrap;
  }

  /* ------------------------------------------------------------ 表单生成 --- */

  /**
   * 按参数声明生成表单。
   * 参数声明形如：
   *   { name, label, type:'text'|'textarea'|'number'|'select'|'checkbox',
   *     required, def, hint, placeholder, options:[{value,label}], width:'half' }
   * 返回 { el, values() } —— values() 只返回「填了的」项，空串会被丢掉。
   */
  function buildForm(params, initial) {
    var wrap = h('div');
    var inputs = {};
    var halfBuf = [];

    function flushHalf() {
      if (!halfBuf.length) return;
      if (halfBuf.length === 1) { wrap.appendChild(halfBuf[0].field); }
      else {
        var row = h('div', { class: 'field-row' }, halfBuf.map(function (x) { return x.field; }));
        wrap.appendChild(row);
      }
      halfBuf = [];
    }

    (params || []).forEach(function (p) {
      var id = 'f_' + p.name + '_' + Math.random().toString(36).slice(2, 7);
      var init = (initial && initial[p.name] !== undefined) ? initial[p.name] : p.def;
      if (init === undefined || init === null) init = '';

      var input;
      if (p.type === 'textarea') {
        input = h('textarea', { id: id, placeholder: p.placeholder || '' });
        input.value = init;
      } else if (p.type === 'select') {
        input = h('select', { id: id });
        (p.options || []).forEach(function (o) {
          var opt = h('option', { value: o.value, text: o.label });
          if (String(o.value) === String(init)) opt.selected = true;
          input.appendChild(opt);
        });
      } else if (p.type === 'checkbox') {
        input = h('input', { id: id, type: 'checkbox' });
        input.checked = !!init;
      } else {
        input = h('input', { id: id, type: p.type === 'number' ? 'number' : 'text',
                              placeholder: p.placeholder || '' });
        input.value = init;
      }
      inputs[p.name] = { input: input, spec: p };

      var label = h('label', { class: 'field-label', for: id }, [
        h('span', { text: p.label || p.name }),
        p.required ? h('span', { class: 'req', text: '必填' }) : h('span', { class: 'opt', text: '可选' })
      ]);

      var field = h('div', { class: 'field' }, [label, input]);
      if (p.hint) field.appendChild(h('div', { class: 'field-hint', text: p.hint }));

      if (p.width === 'half') halfBuf.push({ field: field });
      else { flushHalf(); wrap.appendChild(field); }
    });
    flushHalf();

    return {
      el: wrap,
      values: function () {
        var out = {};
        Object.keys(inputs).forEach(function (name) {
          var it = inputs[name], p = it.spec, v;
          if (p.type === 'checkbox') { out[name] = it.input.checked; return; }
          v = it.input.value;
          if (v === '' || v === null || v === undefined) return;
          if (p.type === 'number') { v = Number(v); if (isNaN(v)) return; }
          out[name] = v;
        });
        return out;
      },
      /** 直接拿原始字符串（含空串），用于「不传就不改」这类需要区分空值的场景。 */
      raw: function (name) {
        var it = inputs[name];
        if (!it) return undefined;
        if (it.spec.type === 'checkbox') return it.input.checked;
        return it.input.value;
      },
      /** 拿到某个字段的输入元素（供动态填充选项等场景使用）。 */
      node: function (name) {
        var it = inputs[name];
        return it ? it.input : null;
      },
      /** 给 select 动态灌选项。第一项不会自动选中，保持「未选择」状态。 */
      setOptions: function (name, options) {
        var it = inputs[name];
        if (!it || it.input.tagName !== 'SELECT') return;
        var sel = it.input;
        while (sel.firstChild) sel.removeChild(sel.firstChild);
        (options || []).forEach(function (o) {
          sel.appendChild(h('option', { value: o.value, text: o.label }));
        });
      },
      /** 恢复到声明里的默认值。 */
      reset: function () {
        Object.keys(inputs).forEach(function (name) {
          var it = inputs[name], p = it.spec;
          var v = (p.def === undefined || p.def === null) ? '' : p.def;
          if (p.type === 'checkbox') it.input.checked = !!v;
          else it.input.value = v;
        });
      },
      /** 校验必填，返回第一个缺失项的 label。 */
      firstMissing: function () {
        var miss = null;
        Object.keys(inputs).forEach(function (name) {
          var it = inputs[name];
          if (miss || !it.spec.required) return;
          var v = it.spec.type === 'checkbox' ? (it.input.checked ? 'y' : '') : it.input.value;
          if (v === '' || v === null || v === undefined) miss = it.spec.label || name;
        });
        return miss;
      }
    };
  }

  /* ------------------------------------------------------------ 结果外壳 --- */

  function resultHead(res) {
    var cls = res.ok ? 'res-ok' : 'res-bad';
    var txt = res.status === 0 ? '网络不可达' : ('HTTP ' + res.status);
    return h('div', { class: 'res-head' }, [
      h('span', { class: 'res-status ' + cls, text: txt }),
      h('span', { class: 'res-time', text: fmtTime(res.ms) })
    ]);
  }

  /** 按状态码给一条「大概是什么问题、去哪里看」的提示。 */
  function statusHint(res) {
    var s = res.status;
    if (s === 0) return '后端没起来，或者端口不是 8090。确认应用已启动，且页面是从同一台机器打开的。';
    if (s === 401 || s === 403) return '鉴权失败。这个接口会调用大模型，请确认 DEEPSEEK_API_KEY 是有效的 —— 无效的 Key 会在第一次调用模型时被拒。';
    if (s === 404) return '资源不存在。多半是知识库 id 或文档 docId 不对 —— 用 GET /stage8/kb 和 GET /stage8/kb/{kbId}/docs 核对一下。';
    if (s === 400) return '参数不合法。看一下 message 里的具体原因（保留字 id、mode 拼写、空正文等）。';
    if (s >= 500) return '服务端异常。若这个接口要调用大模型，最常见的原因是 DEEPSEEK_API_KEY 无效或未配置；否则去看应用控制台日志的最后一行 Caused by。';
    return null;
  }

  function errorBlock(res) {
    var box = h('div', { class: 'res-error' });
    box.appendChild(h('div', { text: res.error || '请求失败' }));

    var body = res.data;
    var shown = false;
    if (body && typeof body === 'object') {
      if (body.message) { box.appendChild(h('div', { class: 'msg', text: body.message })); shown = true; }
      else if (body.error) { box.appendChild(h('div', { class: 'msg', text: String(body.error) })); shown = true; }
      if (body.hint) { box.appendChild(h('div', { class: 'field-hint', text: '提示：' + body.hint })); shown = true; }
      if (!shown) { box.appendChild(h('div', { style: 'margin-top:7px' }, [jsonView(body)])); }
    } else if (typeof body === 'string' && body) {
      box.appendChild(h('div', { class: 'msg', text: body.slice(0, 400) }));
      shown = true;
    }

    var hint = statusHint(res);
    if (hint) box.appendChild(h('div', { class: 'field-hint', style: 'margin-top:7px', text: '可能的原因：' + hint }));
    return box;
  }

  global.Core = {
    $: $, $$: $$, h: h, esc: esc, append: append,
    fmtBytes: fmtBytes, fmtTime: fmtTime,
    composeUrl: composeUrl, api: api, toast: toast,
    jsonView: jsonView, buildForm: buildForm,
    resultHead: resultHead, errorBlock: errorBlock
  };
})(window);
