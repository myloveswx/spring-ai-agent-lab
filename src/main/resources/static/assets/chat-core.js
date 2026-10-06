/* ==========================================================================
 * chat-core.js —— 智能助手的地基层
 *
 * 只做四件事，不含任何业务知识：
 *   1. DOM 构建（h / esc / clear）
 *   2. 统一的请求封装（GET/POST/PUT/DELETE，自动识别 JSON 与纯文本，带耗时）
 *   3. SSE 流式读取（用 fetch+reader 手工解析，而不是 EventSource）
 *   4. 极简 Markdown → HTML（先转义再渲染，避免 XSS）
 *
 * 为什么不用 EventSource 读流：Spring 的 Flux 正常结束时会关闭连接，
 * 而 EventSource 把「连接被服务端关闭」当成错误，会自动重连 ——
 * 结果就是同一个问题被反复问、token 反复烧。用 fetch reader 能拿到明确的结束点。
 * ========================================================================== */
window.C = (function () {
  'use strict';

  /* ---------------------------------------------------------------- DOM --- */

  /**
   * 建元素。children 可以是字符串 / Node / 数组 / null。
   * attrs 里 `text` 走 textContent（安全），`html` 走 innerHTML（仅用于我们自造的片段）。
   */
  function h(tag, attrs, children) {
    var el = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (k) {
        var v = attrs[k];
        if (v === null || v === undefined || v === false) return;
        if (k === 'text') el.textContent = v;
        else if (k === 'html') el.innerHTML = v;
        else if (k === 'class') el.className = v;
        else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
        else if (k.slice(0, 2) === 'on' && typeof v === 'function') el.addEventListener(k.slice(2), v);
        else if (k === 'dataset' && typeof v === 'object') Object.keys(v).forEach(function (d) { el.dataset[d] = v[d]; });
        else el.setAttribute(k, v === true ? '' : v);
      });
    }
    append(el, children);
    return el;
  }

  function append(el, children) {
    if (children === null || children === undefined || children === false) return;
    if (Array.isArray(children)) { children.forEach(function (c) { append(el, c); }); return; }
    el.appendChild(children instanceof Node ? children : document.createTextNode(String(children)));
  }

  function $(sel, root) { return (root || document).querySelector(sel); }

  function clear(el) { while (el.firstChild) el.removeChild(el.firstChild); return el; }

  function esc(s) {
    return String(s === null || s === undefined ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  /* -------------------------------------------------------------- 请求 --- */

  /**
   * 拼查询串。空值（null / undefined / ''）直接跳过 ——
   * 「不传」和「传空串」在有些接口里语义不同（例如「不传就不改」）。
   * 逐段 encodeURIComponent 保证中文按 UTF-8 发出。
   */
  function buildUrl(path, query) {
    var url = path, parts = [];
    if (query) {
      Object.keys(query).forEach(function (k) {
        var v = query[k];
        if (v === undefined || v === null || v === '') return;
        parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(v));
      });
    }
    if (parts.length) url += (url.indexOf('?') >= 0 ? '&' : '?') + parts.join('&');
    return url;
  }

  /** 把结果统一成 { ok, status, data, text, ms, contentType, url }。 */
  async function req(method, path, opts) {
    opts = opts || {};
    var url = buildUrl(path, opts.query);
    var init = { method: method, headers: {} };
    if (opts.json !== undefined) {
      init.headers['Content-Type'] = 'application/json;charset=UTF-8';
      init.body = JSON.stringify(opts.json);
    }
    var t0 = performance.now();
    var res;
    try {
      res = await fetch(url, init);
    } catch (e) {
      return {
        ok: false, status: 0, ms: Math.round(performance.now() - t0),
        data: null, text: '', contentType: '', url: url, method: method,
        error: '网络错误：' + (e && e.message ? e.message : e)
      };
    }
    var raw = await res.text();
    var ct = res.headers.get('content-type') || '';
    var data = raw;
    if (/json/i.test(ct) || /^\s*[[{]/.test(raw)) {
      try { data = JSON.parse(raw); } catch (e) { /* 保持原文 */ }
    }
    return {
      ok: res.ok, status: res.status, data: data, text: raw,
      ms: Math.round(performance.now() - t0), contentType: ct,
      url: url, method: method
    };
  }

  /** 从任意响应体里榨出人能看的错误信息。 */
  function errorInfo(res) {
    var info = { title: '', detail: '', hint: '' };
    var d = res.data;
    if (res.status === 0) { info.title = '请求没发出去'; info.detail = res.error || ''; return info; }
    info.title = 'HTTP ' + res.status + '（' + res.method + ' ' + res.url + '）';
    if (d && typeof d === 'object') {
      // 优先用业务接口自己给的 message；Spring 默认错误体只有 error + path，
      // 那两个拼起来比单看一句「Internal Server Error」有用得多
      var own = d.message || d.detail;
      if (own) info.detail = own;
      else if (d.error) info.detail = d.error + (d.path ? '  ——  ' + d.path : '');
      if (!info.detail) info.detail = '服务端返回了空错误体（' + res.status + '）。看应用控制台日志的最后一行 Caused by。';
      if (d.hint) info.hint = d.hint;
    } else if (typeof d === 'string' && d.trim()) {
      info.detail = d.length > 600 ? d.slice(0, 600) + '…' : d;
    }
    if (!info.detail) info.detail = '服务端没有返回任何内容（HTTP ' + res.status + '）。';

    var text = (info.detail || '') + ' ' + (info.title || '');
    if (res.status === 401 || /invalid api key|authentication|unauthor/i.test(text)) {
      info.hint = '模型调用鉴权失败 —— 当前多半是假 Key。用真实 Key 重启：'
        + 'DEEPSEEK_API_KEY=sk-xxx mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8090';
    } else if (res.status === 404) {
      info.hint = '这个地址不存在。Stage 7 的接口默认不创建（spring.ai.mcp.client.enabled=false），'
        + '需要加 -Dspring-boot.run.profiles=mcp 启动。';
    } else if (/JSON parse|Invalid UTF-8|Cannot deserialize/i.test(info.detail)) {
      info.hint = '模型返回的内容不是合法 JSON —— 这正是 /stage5/analyze 与 /stage5/analyze/validated 要对照的场景；'
        + '换成「自纠错」那条链路它会自动重试。';
    } else if (res.status === 500) {
      info.hint = '模型调用失败。最常见的原因是 DEEPSEEK_API_KEY 无效或网络不通 —— '
        + '看应用控制台最后一行 Caused by 就能定位。';
    } else if (res.status === 400) {
      info.hint = '参数不合法或缺了必填项。对照 Java 控制台日志里的 MethodArgumentNotValid / MissingServletRequestParameter 提示。';
    }
    return info;
  }

  /* ---------------------------------------------------------------- SSE --- */

  /**
   * 流式读取 text/event-stream。
   *
   * 手工解析而不是 EventSource，理由见文件头。
   * handlers: { onDelta(text), onDone({ms}), onError(info, res) }
   * 返回 { cancel() }。
   */
  function stream(path, query, handlers) {
    var url = buildUrl(path, query);
    var ctrl = new AbortController();
    var t0 = performance.now();

    (async function () {
      var res;
      try {
        res = await fetch(url, { headers: { Accept: 'text/event-stream' }, signal: ctrl.signal });
      } catch (e) {
        if (ctrl.signal.aborted) return;
        handlers.onError({ title: '流式请求失败', detail: String(e), hint: '', url: url, method: 'GET', status: 0 });
        return;
      }
      var ct = res.headers.get('content-type') || '';

      // 服务端没按 SSE 应答（多半是 4xx/5xx 的 JSON 错误体）→ 当普通错误处理
      if (!res.ok || !/event-stream/i.test(ct)) {
        var text = await res.text();
        var data = text;
        try { data = JSON.parse(text); } catch (e) { /* 原文 */ }
        handlers.onError(errorInfo({ status: res.status, ok: false, data: data, text: text,
                                     contentType: ct, url: url, method: 'GET', ms: 0 }));
        return;
      }

      var reader = res.body.getReader();
      var decoder = new TextDecoder('utf-8');
      var buf = '';
      try {
        for (;;) {
          var step = await reader.read();
          if (step.done) break;
          buf += decoder.decode(step.value, { stream: true });
          var idx;
          while ((idx = buf.indexOf('\n\n')) >= 0) {
            var block = buf.slice(0, idx);
            buf = buf.slice(idx + 2);
            var payload = parseSseBlock(block);
            if (payload !== null) handlers.onDelta(payload);
          }
        }
        var tail = parseSseBlock(buf);
        if (tail !== null) handlers.onDelta(tail);
        handlers.onDone({ ms: Math.round(performance.now() - t0), url: url });
      } catch (e) {
        if (ctrl.signal.aborted) { handlers.onDone({ ms: Math.round(performance.now() - t0), url: url, aborted: true }); return; }
        handlers.onError({ title: '流读取中断', detail: String(e), hint: '', url: url, method: 'GET', status: 0 });
      }
    })();

    return { cancel: function () { ctrl.abort(); } };
  }

  /** 解析一个 SSE 事件块：收集所有 data: 行，忽略注释与其它字段。无 data 返回 null。 */
  function parseSseBlock(block) {
    if (!block) return null;
    var lines = block.split('\n'), out = [], has = false;
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i];
      if (line.charAt(0) === ':') continue;           // 心跳注释
      if (line.slice(0, 5) !== 'data:') continue;     // event: / id: / retry: 不关心
      has = true;
      out.push(line.charAt(5) === ' ' ? line.slice(6) : line.slice(5));
    }
    return has ? out.join('\n') : null;
  }

  /* ----------------------------------------------------------- Markdown --- */

  function inline(t) {
    return esc(t)
      .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
      .replace(/`([^`]+)`/g, '<code>$1</code>');
  }

  /**
   * 行级渲染。支持：# 标题、- / 1. 列表、``` 代码块、**粗体**、`行内代码`。
   * 故意做得很小 —— 只为把模型回复读起来舒服，不做完整 CommonMark。
   */
  function md(src) {
    var lines = String(src === null || src === undefined ? '' : src).split(/\r?\n/);
    var out = [], list = [], code = [], inCode = false;

    function flushList() { if (list.length) { out.push('<ul>' + list.join('') + '</ul>'); list = []; } }
    function flushCode() { out.push('<pre class="code"><code>' + esc(code.join('\n')) + '</code></pre>'); code = []; }

    for (var i = 0; i < lines.length; i++) {
      var line = lines[i];
      if (/^\s*```/.test(line)) {
        if (inCode) { flushCode(); inCode = false; } else { flushList(); inCode = true; }
        continue;
      }
      if (inCode) { code.push(line); continue; }
      if (!line.trim()) { flushList(); continue; }
      var li = line.match(/^\s*(?:[-*+]|\d+\.)\s+(.*)$/);
      if (li) { list.push('<li>' + inline(li[1]) + '</li>'); continue; }
      flushList();
      var hd = line.match(/^\s*#{1,4}\s+(.*)$/);
      if (hd) { out.push('<div class="md-h">' + inline(hd[1]) + '</div>'); continue; }
      out.push('<p>' + inline(line) + '</p>');
    }
    flushList();
    if (inCode && code.length) flushCode();   // 流式渲染时可能还没等到收尾的 ```
    return out.join('');
  }

  /* ------------------------------------------------------------- 工具 --- */

  function fmtMs(ms) {
    if (ms === null || ms === undefined) return '';
    if (ms < 1000) return ms + ' ms';
    return (ms / 1000).toFixed(ms < 10000 ? 2 : 1) + ' s';
  }

  function shortPath(url) {
    if (!url) return '';
    var i = url.indexOf('://');
    if (i >= 0) url = url.slice(url.indexOf('/', i + 3));
    return url.length > 64 ? url.slice(0, 61) + '…' : url;
  }

  function toast(text, kind) {
    var box = $('#toasts');
    if (!box) return;
    var el = h('div', { class: 'toast' + (kind === 'err' ? ' err' : ''), text: text });
    box.appendChild(el);
    setTimeout(function () { el.remove(); }, 2600);
  }

  /** 可折叠的原始 JSON 视图（所有富渲染都留一个「看原始数据」的出口）。 */
  function jsonDetails(label, obj) {
    return h('details', { class: 'json' }, [
      h('summary', { text: label || '查看原始 JSON' }),
      h('pre', { text: JSON.stringify(obj, null, 2) })
    ]);
  }

  function randomId(prefix) {
    var s = Math.random().toString(36).slice(2, 8);
    return (prefix || '') + s;
  }

  return {
    h: h, append: append, $: $, clear: clear, esc: esc,
    req: req, buildUrl: buildUrl, errorInfo: errorInfo, stream: stream,
    md: md, fmtMs: fmtMs, shortPath: shortPath, toast: toast,
    jsonDetails: jsonDetails, randomId: randomId
  };
})();
