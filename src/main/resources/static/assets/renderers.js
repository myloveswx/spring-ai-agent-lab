/* ==========================================================================
   renderers.js —— 富结果渲染
   每个渲染器签名统一为 fn(data, ctx)，返回一个 DOM 节点。
   data 是后端返回的 JSON（已解析），ctx 提供与页面交互的能力。
   没写渲染器的接口回退到 json 渲染器。
   ========================================================================== */

(function (global) {
  'use strict';

  var C = global.Core;
  var h = C.h;

  /* ------------------------------------------------------------ 小工具 --- */

  function kv(pairs) {
    var dl = h('dl', { class: 'kv' });
    pairs.forEach(function (p) {
      if (p[1] === undefined || p[1] === null || p[1] === '') return;
      dl.appendChild(h('dt', { text: p[0] }));
      dl.appendChild(h('dd', { text: String(p[1]) }));
    });
    return dl;
  }

  function sectionTitle(text) {
    return h('div', { class: 'section-mini-title', text: text });
  }

  function num(v) { return typeof v === 'number' ? v : Number(v); }

  function toFixed(v, n) {
    var x = num(v);
    return isNaN(x) ? String(v) : x.toFixed(n);
  }

  /* -------------------------------------------------------- 知识库列表 --- */

  function kbList(data, ctx) {
    var box = h('div');
    if (!data || !Array.isArray(data.knowledgeBases)) return C.jsonView(data);

    box.appendChild(h('div', { class: 'field-hint',
      text: '存储根目录：' + data.root + '　·　共 ' + data.count + ' 个库　·　默认库 id：' + data.defaultId }));

    var cards = h('div', { class: 'kb-cards', style: 'margin-top:9px' });
    data.knowledgeBases.forEach(function (kb) {
      var isCur = kb.id === ctx.currentKb();
      var card = h('div', { class: 'kb-card' + (isCur ? ' current' : '') });
      card.appendChild(h('div', { class: 'kb-id',
        text: kb.id + (kb.id === data.defaultId ? '　·　默认' : '') }));
      card.appendChild(h('div', { class: 'kb-name', text: kb.name || '（未命名）' }));
      if (kb.description) card.appendChild(h('div', { class: 'kb-name', text: kb.description }));
      card.appendChild(h('div', { class: 'kb-stat',
        text: kb.loaded
          ? ('已加载 · ' + kb.documents + ' 篇 / ' + kb.chunks + ' 块')
          : '未加载 · 文档数未知（懒加载，首次访问才读盘）' }));
      card.appendChild(h('div', { class: 'kb-stat', text: '创建于 ' + kb.createdAt }));

      var acts = h('div', { class: 'kb-acts' });
      acts.appendChild(h('button', {
        class: 'btn ghost mini',
        text: isCur ? '当前选中' : '设为当前库',
        disabled: isCur,
        onclick: function () { ctx.setKb(kb.id); }
      }));
      acts.appendChild(h('button', {
        class: 'btn ghost mini', text: '查库内文档',
        onclick: function () { ctx.setKb(kb.id); ctx.runEndpoint('docs-list'); }
      }));
      card.appendChild(acts);
      cards.appendChild(card);
    });
    box.appendChild(cards);
    return box;
  }

  /* ---------------------------------------------------- 知识库单项详情 --- */

  function kbDetail(data) {
    var box = h('div');
    box.appendChild(kv([
      ['库 id', data.id],
      ['名称', data.name],
      ['备注', data.description || '（无）'],
      ['创建时间', data.createdAt],
      ['磁盘目录', data.dir],
      ['文档数', data.documents],
      ['片段数', data.chunks],
      ['向量文件', data.storeFileExists ? '已落盘 ✓' : '尚未落盘']
    ]));
    return box;
  }

  /* ---------------------------------------------------------- 入库结果 --- */

  function ingestRecords(data) {
    var box = h('div');
    var list = Array.isArray(data) ? data : [data];
    list = list.filter(Boolean);
    if (!list.length) return C.jsonView(data);

    var total = 0;
    var rows = list.map(function (r) {
      var chunks = (r.chunkIds || []).length;
      total += chunks;
      return h('tr', null, [
        h('td', { text: r.title || '—' }),
        h('td', { class: 'mono', text: r.source || '—' }),
        h('td', { text: String(chunks) }),
        h('td', { class: 'mono', text: (r.id || '').slice(0, 8) }),
        h('td', { class: 'mono', text: r.ingestedAt || '—' })
      ]);
    });

    box.appendChild(h('div', { class: 'field-hint',
      text: '本次共 ' + list.length + ' 篇 / ' + total + ' 个片段入库。片段数正是判断切块粒度是否合理的直接依据。' }));
    box.appendChild(h('table', { class: 'tbl', style: 'margin-top:8px' }, [
      h('thead', null, [h('tr', null, [
        h('th', { text: '标题' }), h('th', { text: '来源' }), h('th', { text: '片段数' }),
        h('th', { text: 'docId' }), h('th', { text: '入库时间' })
      ])]),
      h('tbody', null, rows)
    ]));
    return box;
  }

  /* ---------------------------------------------------------- 文档清单 --- */

  function docList(data, ctx) {
    var box = h('div');
    if (!Array.isArray(data)) return C.jsonView(data);
    if (!data.length) {
      box.appendChild(h('div', { class: 'result-empty',
        text: '这个库还是空的。先回到第 2 组「载入内置示例语料」，或手动粘一段文本入库。' }));
      return box;
    }
    var rows = data.map(function (d) {
      return h('tr', null, [
        h('td', { class: 'mono', text: d.docId }),
        h('td', { text: d.title }),
        h('td', { class: 'mono', text: d.source }),
        h('td', { text: String(d.chunks) }),
        h('td', { class: 'mono', text: d.ingestedAt })
      ]);
    });
    box.appendChild(h('div', { class: 'field-hint',
      text: '共 ' + data.length + ' 篇。第一个字段就是 docId —— 上面「查详情 / 更新 / 删除」三个接口的文档下拉框会自动带上它们。' }));
    box.appendChild(h('table', { class: 'tbl', style: 'margin-top:8px' }, [
      h('thead', null, [h('tr', null, [
        h('th', { text: 'docId' }), h('th', { text: '标题' }), h('th', { text: '来源' }),
        h('th', { text: '片段数' }), h('th', { text: '入库时间' })
      ])]),
      h('tbody', null, rows)
    ]));
    return box;
  }

  /* ---------------------------------------------------------- 文档详情 --- */

  function docDetail(data) {
    var box = h('div');
    box.appendChild(kv([
      ['标题', data.title],
      ['docId', data.docId],
      ['来源', data.source],
      ['入库时间', data.ingestedAt],
      ['片段数', data.chunks]
    ]));
    if (Array.isArray(data.chunkRefs) && data.chunkRefs.length) {
      box.appendChild(sectionTitle('切片明细（序号 = 它在本篇中的位置）'));
      var rows = data.chunkRefs.map(function (c) {
        return h('tr', null, [
          h('td', { text: '第 ' + c.index + ' 块' }),
          h('td', { class: 'mono', text: c.id })
        ]);
      });
      box.appendChild(h('table', { class: 'tbl' }, [
        h('thead', null, [h('tr', null, [h('th', { text: '序号' }), h('th', { text: '片段 id' })])]),
        h('tbody', null, rows)
      ]));
    }
    box.appendChild(h('div', { class: 'field-hint', style: 'margin-top:10px',
      text: '注意这里没有片段正文 —— VectorStore 接口只有 add / delete / similaritySearch，没有 get(id)。'
          + '想回显原文只能自己再存一份，这是 L2 要补的第一件事。' }));
    return box;
  }

  /* ---------------------------------------------------------- 检索命中 --- */

  function hits(data) {
    var box = h('div');
    if (!Array.isArray(data)) return C.jsonView(data);
    if (!data.length) {
      box.appendChild(h('div', { class: 'result-empty',
        text: '没有命中任何片段。三种常见原因：'
            + '① 库里确实没有相关内容；② 相似度阈值太高（试试降到 0.3 或直接传 0 不过滤）；'
            + '③ 这个库还没入库。' }));
      return box;
    }
    data.forEach(function (hit, i) {
      var score = num(hit.score);
      if (isNaN(score)) score = 0;
      var pct = Math.max(0, Math.min(1, score)) * 100;

      var card = h('div', { class: 'hit' });
      card.appendChild(h('div', { class: 'hit-top' }, [
        h('span', { class: 'hit-rank' + (i === 0 ? ' r1' : ''), text: String(i + 1) }),
        h('span', { class: 'hit-name', text: hit.title || '（无标题）' }),
        h('span', { class: 'hit-src',
          text: '第 ' + hit.chunkIndex + ' 块 · ' + (hit.source || '未知来源') }),
        h('span', { class: 'hit-score', text: 'score ' + score.toFixed(4) })
      ]));
      card.appendChild(h('div', { class: 'hit-bar' }, [
        h('i', { style: 'width:' + pct.toFixed(1) + '%' })
      ]));
      card.appendChild(h('div', { class: 'hit-text', text: hit.text }));
      box.appendChild(card);
    });
    return box;
  }

  /* ---------------------------------------------------------- 纯文本答 --- */

  function answer(data) {
    var box = h('div');
    if (typeof data !== 'string') return C.jsonView(data);
    box.appendChild(h('div', { class: 'hit-text', style: 'max-height:none', text: data }));
    return box;
  }

  /* ---------------------------------------------------------- 对照实验 --- */

  function compare(data) {
    var box = h('div');
    if (!data || typeof data !== 'object') return C.jsonView(data);

    box.appendChild(kv([
      ['知识库', data.knowledgeBase],
      ['问题', data.question],
      ['检索命中', data.hitCount + ' 段']
    ]));

    box.appendChild(sectionTitle('第一步 · 检索到了什么（这部分完全决定后面答得好不好）'));
    box.appendChild(hits(data.retrieved || []));

    box.appendChild(sectionTitle('第二步 · 两版回答对照'));

    var cmp = h('div', { class: 'cmp' });
    [
      { key: 'withoutRag', cls: 'without', name: '没有知识库（纯模型回答）' },
      { key: 'withRag', cls: 'with', name: '挂了 RAG（基于上面检索到的资料）' }
    ].forEach(function (spec) {
      var ans = data[spec.key] || {};
      var col = h('div', { class: 'cmp-col ' + spec.cls });
      col.appendChild(h('h4', null, [
        h('span', { text: spec.name }),
        h('span', { class: 'cmp-cost', text: (ans.costMillis || 0) + ' ms' })
      ]));
      col.appendChild(h('div', { class: 'ans', text: ans.text || '（无内容）' }));
      cmp.appendChild(col);
    });
    box.appendChild(cmp);

    box.appendChild(h('div', { class: 'field-hint', style: 'margin-top:10px',
      text: '看点：左边通常会给一个「像是那么回事」的通用答案；右边应该出现具体条款或数字。'
          + '如果库里对同一件事有两处矛盾口径，右边还应该把矛盾指出来而不是自己挑一个。' }));

    return box;
  }

  /* ---------------------------------------------------------- 库现状 --- */

  function stats(data) {
    var box = h('div');
    if (!data || typeof data !== 'object') return C.jsonView(data);

    box.appendChild(kv([
      ['库 id', data.id],
      ['名称', data.name],
      ['备注', data.description || '（无）'],
      ['创建时间', data.createdAt],
      ['文档数', data.documents],
      ['片段数', data.chunks],
      ['向量维度', data.dimensions],
      ['嵌入模型目录', data.modelDir],
      ['落盘文件', data.storePath + (data.storeFileExists ? '　✓ 存在' : '　✗ 不存在')]
    ]));

    box.appendChild(sectionTitle('当前生效的切块与检索参数'));
    box.appendChild(kv([
      ['topK（召回条数）', data.topK],
      ['similarityThreshold', data.similarityThreshold],
      ['chunkSize（token 上限）', data.chunkSize],
      ['minChunkSizeChars', data.minChunkSizeChars],
      ['minChunkLengthToEmbed', data.minChunkLengthToEmbed]
    ]));

    if (Array.isArray(data.catalog) && data.catalog.length) {
      box.appendChild(sectionTitle('已入库文档清单（应用自己记账，向量库本身没有 count/list）'));
      var rows = data.catalog.map(function (d) {
        return h('tr', null, [
          h('td', { text: d.title }),
          h('td', { class: 'mono', text: d.source }),
          h('td', { text: String(d.chunks) })
        ]);
      });
      box.appendChild(h('table', { class: 'tbl' }, [
        h('thead', null, [h('tr', null, [
          h('th', { text: '标题' }), h('th', { text: '来源' }), h('th', { text: '片段数' })
        ])]),
        h('tbody', null, rows)
      ]));
    }
    return box;
  }

  global.Renderers = {
    kbList: kbList,
    kbDetail: kbDetail,
    ingestRecords: ingestRecords,
    docList: docList,
    docDetail: docDetail,
    hits: hits,
    answer: answer,
    compare: compare,
    stats: stats,
    json: function (data) { return C.jsonView(data); }
  };
})(window);
