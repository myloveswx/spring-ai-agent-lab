/* ==========================================================================
 * chat-stages.js —— 场景声明
 *
 * 这是唯一需要改动的业务文件：一个场景 = 一个「学习阶段」，声明了
 *   · 它绑定哪个（些）接口        call(cfg, text)
 *   · 场景内有哪些可调参数        config[]
 *   · 有哪些一键动作（不占用输入框） actions[]
 *   · 推荐问法                    samples[]
 *   · 结果怎么渲染                render(res, msg, app)
 *
 * 加一个新 stage，只需要在 SCENES 里补一条；界面（导航、配置条、输入区）会自动长出来。
 * ========================================================================== */
window.SCENES = (function () {
  'use strict';

  var h = C.h;

  /* ------------------------------------------------------------ 共享渲染 --- */

  /** 检索命中列表（stage8 的 Hit 与 stage6 lab 的 LabToolHit 结构接近，统一处理）。 */
  function hitsView(hits, opts) {
    opts = opts || {};
    if (!hits || !hits.length) return h('div', { class: 'hit-text', text: '（没有命中任何内容）' });
    var list = hits.map(function (it) {
      var title = it.title || it.toolName || '(未命名)';
      var src = it.source ? '来源 ' + it.source : '';
      if (it.chunkIndex !== undefined && it.chunkIndex !== null) src += (src ? ' · ' : '') + '第 ' + (it.chunkIndex + 1) + ' 块';
      if (it.category) src += (src ? ' · ' : '') + '分类 ' + it.category;
      var text = it.text || it.description || '';
      var score = typeof it.score === 'number' ? it.score : null;
      var bar = score === null ? null
        : h('div', { class: 'score-bar' }, h('i', { style: { width: Math.max(2, Math.min(100, score * 100)) + '%' } }));
      return h('div', { class: 'hit' }, [
        h('div', { class: 'hit-head' }, [
          h('span', { class: 'hit-title', text: title }),
          src ? h('span', { class: 'hit-src', text: src }) : null,
          score === null ? null : h('span', { class: 'hit-score', text: score.toFixed(4) })
        ]),
        text ? h('div', { class: 'hit-text', text: text }) : null,
        bar
      ]);
    });
    return h('div', { class: 'hits' }, list);
  }

  /** 结构化对象 → 定义列表。 */
  function fieldsView(obj, order) {
    var keys = order || Object.keys(obj || {});
    var dl = h('dl', { class: 'fields' });
    keys.forEach(function (k) {
      var v = obj ? obj[k] : undefined;
      if (v === undefined) return;
      var dd;
      if (Array.isArray(v)) dd = h('ul', { style: { margin: '0', paddingLeft: '18px' } }, v.map(function (x) { return h('li', { text: String(x) }); }));
      else dd = h('dd', { text: String(v) });
      dl.appendChild(h('dt', { text: k }));
      dl.appendChild(Array.isArray(v) ? h('dd', null, dd) : dd);
    });
    return dl;
  }

  /** 平铺键值网格，用于 stats 这类「一堆数字」。 */
  function kvView(obj) {
    var box = h('div', { class: 'kv' });
    Object.keys(obj || {}).forEach(function (k) {
      var v = obj[k];
      if (v !== null && typeof v === 'object') return;   // 嵌套的单独处理
      box.appendChild(h('div', null, [h('span', { text: k }), h('b', { text: String(v) })]));
    });
    return box;
  }

  function tableView(rows, cols) {
    if (!rows || !rows.length) return h('div', { class: 'hit-text', text: '（空）' });
    var head = h('tr', null, cols.map(function (c) { return h('th', { text: c.label }); }));
    var body = rows.map(function (r) {
      return h('tr', null, cols.map(function (c) {
        var v = r[c.key];
        if (Array.isArray(v)) v = v.join(' / ') || '—';
        return h('td', { class: c.mono ? 'mono' : (c.cls || ''), text: v === undefined || v === null || v === '' ? '—' : String(v) });
      }));
    });
    return h('table', { class: 'tbl' }, [h('thead', null, head), h('tbody', null, body)]);
  }

  /** 涨跌用 A 股口径：涨红、跌绿。 */
  function trendPill(text) {
    var cls = /涨|上升|上行/.test(text) ? 'up' : (/跌|下降|下行/.test(text) ? 'down' : 'flat');
    return h('span', { class: 'pill ' + cls, text: text });
  }

  /** 「无 RAG vs 有 RAG」对照。 */
  function compareView(res) {
    var d = res.data || {};
    function col(title, answer, kind) {
      return h('div', { class: 'compare-col ' + kind }, [
        h('header', null, [
          h('span', { text: title }),
          h('span', { text: answer ? C.fmtMs(answer.costMillis) : '' })
        ]),
        h('div', { class: 'cc-body', html: C.md(answer ? answer.text : '（无内容）') })
      ]);
    }
    return h('div', null, [
      h('div', { class: 'hit-src', style: { marginBottom: '7px' } , text:
        '知识库 ' + (d.knowledgeBase || '-') + ' · 检索到 ' + (d.hitCount || 0) + ' 个片段' }),
      h('div', { class: 'compare' }, [
        col('无知识库（模型只能编）', d.withoutRag, ''),
        col('有知识库（挂 QuestionAnswerAdvisor）', d.withRag, 'rag')
      ]),
      d.retrieved && d.retrieved.length ? h('details', { class: 'json' }, [
        h('summary', { text: '查看注入 Prompt 的 ' + d.retrieved.length + ' 个片段' }),
        h('div', { style: { marginTop: '7px' } }, hitsView(d.retrieved))
      ]) : null
    ]);
  }

  /** 统一的原始数据出口。 */
  /**
   * 上传入库的「三步链路」视图：切片 → 向量化 → 存入内存。
   *
   * 为什么值得单独做一屏：RAG 里最抽象的一步就是「一篇文档怎么变成向量」。
   * 后端把切片正文一起带出来了（VectorStore 接口没有 get，入库后就取不回来），
   * 这里按链路排开 —— 调 agentlab.rag.chunk-size 时对着它看，
   * 比盯一个「切成了 4 块」的数字有用得多。
   */
  function pipelineView(res) {
    var d = res.data || {};
    var box = h('div');

    box.appendChild(h('div', { class: 'pipe-flow' }, [
      pipeStep('① 切片', 'TokenTextSplitter 按标点断句', '共 ' + d.chunksAdded + ' 块'),
      pipeStep('② 向量化', '本地 ONNX（bge-small-zh）', '每块 ' + d.dimensions + ' 维'),
      pipeStep('③ 存入内存', 'SimpleVectorStore.add()', '耗时 ' + C.fmtMs(d.costMillis))
    ]));

    (Array.isArray(d.uploaded) ? d.uploaded : []).forEach(function (doc) {
      box.appendChild(pipeDoc(doc));
    });

    var skipped = Array.isArray(d.skipped) ? d.skipped : [];
    if (skipped.length) {
      box.appendChild(h('div', { class: 'pipe-skip' }, [
        h('div', { text: '跳过了 ' + skipped.length + ' 个文件（不影响同批其它文件）：' }),
        h('ul', { style: { margin: '4px 0 0', paddingLeft: '18px' } },
          skipped.map(function (s) { return h('li', { text: s }); }))
      ]));
    }

    if (d.stats) {
      box.appendChild(h('div', { class: 'hit-src', style: { marginTop: '10px' },
        text: '入库后该库共 ' + d.stats.documents + ' 篇 / ' + d.stats.chunks
          + ' 个片段（kbId = ' + d.stats.id + '）—— 现在回上面问一句，它就能答出你刚才灌进去的内容了' }));
    }
    box.appendChild(raw(res));
    return box;
  }

  function pipeStep(title, sub, value) {
    return h('div', { class: 'pipe-step' }, [
      h('b', { text: title }),
      h('span', { text: sub }),
      h('span', { text: value })
    ]);
  }

  function pipeDoc(doc) {
    var previews = Array.isArray(doc.previews) ? doc.previews : [];
    return h('div', { class: 'pipe-doc' }, [
      h('div', { class: 'pipe-doc-head' }, [
        h('b', { text: doc.title }),
        h('span', { class: 'hit-src', text: doc.source + ' · ' + doc.chars + ' 字 → ' + doc.chunks + ' 块' })
      ]),
      previews.length
        ? h('details', { class: 'pipe-details' }, [
            h('summary', { text: '看这 ' + previews.length + ' 块被切成了什么样' }),
            h('div', { class: 'act-hint', style: { margin: '5px 0 3px' },
              text: '每块开头重复的那句标题是入库时特意拼进正文的 —— 让片段自带「我属于哪篇」的语义，'
                + '否则一块正文里可能完全没出现主题词，向量就会漂。' }),
            h('div', { class: 'pipe-chunks' }, previews.map(function (p) {
              return h('div', { class: 'pipe-chunk' }, [
                h('div', { class: 'pipe-chunk-head' }, [
                  h('span', { class: 'pipe-chunk-idx', text: '#' + p.index }),
                  h('span', { class: 'hit-src', text: p.chars + ' 字' })
                ]),
                h('div', { class: 'pipe-chunk-text', text: p.text })
              ]);
            }))
          ])
        : h('div', { class: 'hit-src', text: '切块后为空 —— 内容太短，检查 agentlab.rag.chunk-size 与 min-chunk-length-to-embed' })
    ]);
  }

  function raw(res) { return C.jsonDetails('查看原始响应', res.data); }

  /* -------------------------------------------------------------- 场景集 --- */

  var SCENES = [

    /* ================================================================ 1 === */
    {
      id: 'stage1', badge: '1', name: '基础对话', tagline: 'ChatClient 一问一答',
      needsKey: true,
      endpointLabel: function (cfg) {
        if (cfg.mode === 'template') return 'GET /stage1/chat/template';
        return cfg.stream ? 'GET /stage1/stream（SSE）' : 'GET /stage1/chat';
      },
      intro: {
        title: 'Stage 1 · ChatClient 是唯一推荐的用户入口',
        bullets: [
          '同一个 <code>ChatClient</code> 既能 <code>call()</code> 同步拿完整回答，也能 <code>stream()</code> 逐字返回。',
          '把「流式输出」开关打开，再问同样的问题 —— 前端会真实逐字渲染（走 SSE，不是假动画）。',
          '切到「占位符模板」模式，体会 <code>{topic}</code> + <code>param()</code> 组织 Prompt 的写法。'
        ]
      },
      config: [
        { key: 'mode', label: '模式', type: 'select', default: 'chat',
          options: [{ value: 'chat', label: '直接对话' }, { value: 'template', label: '占位符模板' }] },
        { key: 'level', label: '难度', type: 'select', default: '高级', showIf: function (c) { return c.mode === 'template'; },
          options: [{ value: '入门', label: '入门' }, { value: '中级', label: '中级' }, { value: '高级', label: '高级' }] },
        { key: 'stream', label: '流式输出', type: 'switch', default: false, showIf: function (c) { return c.mode === 'chat'; } }
      ],
      samples: ['什么是虚拟线程？', '用三句话解释 Spring AI 的 Advisor 模型', '写一首关于编译器的五言绝句'],
      call: function (cfg, text) {
        if (cfg.mode === 'template') {
          return { method: 'GET', path: '/stage1/chat/template', query: { topic: text, level: cfg.level }, note: '占位符 {topic} / {level}' };
        }
        if (cfg.stream) return { method: 'GET', path: '/stage1/stream', query: { message: text }, sse: true, note: 'SSE 流式' };
        return { method: 'GET', path: '/stage1/chat', query: { message: text } };
      }
    },

    /* ================================================================ 2 === */
    {
      id: 'stage2', badge: '2', name: '会话记忆', tagline: '多轮对话 · 服务端记住上下文',
      needsKey: true,
      endpointLabel: 'GET /stage2/chat?conversationId=…',
      intro: {
        title: 'Stage 2 · 记忆的开关是 conversationId',
        bullets: [
          '先问「我叫追光者」，再问「我叫什么名字？」—— 同一个会话 ID 就能记住。',
          '把会话 ID 改成别的值再问一次，模型立刻「失忆」—— 这就是多用户隔离的原理。',
          '右侧「查看记忆条数」直接读 <code>ChatMemory</code>：窗口只有 20 条，超出的会被淘汰。',
          '历史真的落到了 MySQL（<code>SPRING_AI_CHAT_MEMORY</code> 表），重启应用依然在。'
        ]
      },
      config: [
        { key: 'conversationId', label: '会话 ID', type: 'text', default: '' }
      ],
      actions: [
        { label: '查看记忆条数',
          call: function (c) { return { method: 'GET', path: '/stage2/history/size', query: { conversationId: c.conversationId } }; },
          render: function (res) { return h('div', { text: '记忆层当前保留 ' + res.data + ' 条消息（MessageWindowChatMemory 窗口上限 20）' }); } },
        { label: '打印消息列表',
          call: function (c) { return { method: 'GET', path: '/stage2/history', query: { conversationId: c.conversationId } }; },
          render: function (res) {
            var arr = Array.isArray(res.data) ? res.data : [];
            if (!arr.length) return h('div', { text: '这个会话还没有任何消息。' });
            return h('div', null, [
              h('div', { class: 'hit-src', style: { marginBottom: '6px' }, text: arr.length + ' 条（受窗口裁剪，可能与数据库总行数不同）' }),
              h('ul', { style: { margin: '0', paddingLeft: '18px' } }, arr.map(function (s) {
                return h('li', { class: 'mono', style: { fontFamily: 'var(--mono)', fontSize: '11.5px' }, text: s });
              }))
            ]);
          } },
        { label: '清空记忆', danger: true,
          call: function (c) { return { method: 'DELETE', path: '/stage2/history', query: { conversationId: c.conversationId } }; },
          render: function (res) { return h('div', { text: String(res.data) + ' —— 已从数据库真实删除，不可撤销。' }); } }
      ],
      samples: ['我叫追光者', '我叫什么名字？', '我们刚才聊了什么？'],
      call: function (cfg, text) {
        return { method: 'GET', path: '/stage2/chat', query: { conversationId: cfg.conversationId, message: text } };
      }
    },

    /* ================================================================ 3 === */
    {
      id: 'stage3', badge: '3', name: '工具调用', tagline: '模型自己决定什么时候查工具',
      needsKey: true,
      endpointLabel: 'GET /stage3/chat',
      intro: {
        title: 'Stage 3 · 工具循环已经上移到 Advisor 链',
        bullets: [
          '挂了三个工具：当前时间、日期推算、指数行情。</b>问「现在几点」会触发工具，闲聊则不会 —— 后者明显更快。',
          '工具返回的数据就是唯一事实来源，所以模型编不出假数字。',
          '试着问「上证指数和创业板指现在多少点，哪个涨得多？」，观察它连续调用两次工具再综合。'
        ]
      },
      config: [],
      samples: ['现在几点？', '今天往后 10 天是几号？', '查一下上证指数和创业板指现在多少点，哪个涨得多？'],
      call: function (cfg, text) { return { method: 'GET', path: '/stage3/chat', query: { message: text } }; },
      footer: '工具往返的细节只在控制台日志里'
    },

    /* ================================================================ 4 === */
    {
      id: 'stage4', badge: '4', name: 'Advisor 链', tagline: 'order 决定谁包着谁',
      needsKey: true,
      endpointLabel: 'GET /stage4/chat',
      intro: {
        title: 'Stage 4 · 返回值与 Stage 3 完全一样，价值全在日志里',
        bullets: [
          '外层 <code>TimingAdvisor</code> 只打印一次：一次完整对话（含多轮工具往返）的总耗时。',
          '内层 <code>ToolLoopObserverAdvisor</code> 每轮工具迭代打印一次 —— 嵌套关系由 <code>order</code> 决定。',
          '用一个需要多次工具往返的问题，就能在控制台看到两层的时间对不上（外层 ≥ 内层之和）。'
        ]
      },
      config: [],
      samples: [
        '先告诉我今天日期，再查上证指数和深证成指的行情，最后比较涨跌幅',
        '现在几点？上证指数多少点？'
      ],
      call: function (cfg, text) { return { method: 'GET', path: '/stage4/chat', query: { message: text } }; },
      footer: '打开控制台日志才能看到 Timing / ToolLoop 的输出'
    },

    /* ================================================================ 5 === */
    {
      id: 'stage5', badge: '5', name: '结构化输出', tagline: '让模型直接产出 Java 对象',
      needsKey: true,
      composer: 'picker',
      picker: [
        { value: '000001', label: '上证指数', hint: '000001' },
        { value: '399001', label: '深证成指', hint: '399001' },
        { value: '399006', label: '创业板指', hint: '399006' }
      ],
      endpointLabel: function (cfg) { return cfg.validated ? 'GET /stage5/analyze/validated' : 'GET /stage5/analyze'; },
      intro: {
        title: 'Stage 5 · 点一个指数就开始分析',
        bullets: [
          '返回的不再是字符串，而是 <code>IndexAnalysis</code> 对象：趋势、信心度、驱动因素、一句话总结。',
          '「自纠错」开关挂上 <code>StructuredOutputValidationAdvisor</code>：模型输出不合规时回喂错误并重试（最多 2 次）。',
          '关掉它反复点同一个指数 —— 偶发的 <code>JSON parse error</code> 就是这个对照组的意义。'
        ]
      },
      config: [
        { key: 'validated', label: '自纠错校验', type: 'switch', default: true }
      ],
      call: function (cfg, code) {
        var path = cfg.validated ? '/stage5/analyze/validated' : '/stage5/analyze';
        return { method: 'GET', path: path, query: { indexCode: code }, note: 'prompt = 指数 ' + code };
      },
      render: function (res) {
        if (!res.ok || !res.data || typeof res.data !== 'object') return null;
        var d = res.data;
        var dl = h('dl', { class: 'fields' }, [
          h('dt', { text: 'indexCode' }), h('dd', { text: String(d.indexCode || '—') }),
          h('dt', { text: 'indexName' }), h('dd', { text: String(d.indexName || '—') }),
          h('dt', { text: 'trend' }), h('dd', null, trendPill(String(d.trend || '—'))),
          h('dt', { text: 'confidence' }), h('dd', { text: d.confidence === undefined ? '—' : String(d.confidence) })
        ]);
        return h('div', null, [
          dl,
          d.drivers && d.drivers.length ? h('div', { style: { marginTop: '9px' } }, [
            h('div', { class: 'hit-src', text: 'drivers' }),
            h('ul', { style: { margin: '3px 0 0', paddingLeft: '18px' } }, d.drivers.map(function (x) { return h('li', { text: String(x) }); }))
          ]) : null,
          d.summary ? h('div', { style: { marginTop: '9px', fontSize: '12.5px' } }, [h('span', { class: 'hit-src', text: 'summary ' }), d.summary]) : null,
          raw(res)
        ]);
      }
    },

    /* ================================================================ 6 === */
    {
      id: 'stage6', badge: '6', name: '多工具披露', tagline: '13 个工具，每轮只下发几个',
      needsKey: true,
      endpointLabel: 'GET /stage6/chat?conversationId=…',
      intro: {
        title: 'Stage 6 · 渐进式工具披露',
        bullets: [
          'CRM 一共注册了 13 个工具。如果把定义全塞进 Prompt，光工具描述就吃掉大量 token。',
          '<code>ToolSearchToolCallingAdvisor</code> 每轮先检索出最相关的少数几个，只把它们下发给模型。',
          '<b>这个接口必须传会话 ID</b>，否则 Advisor 取不到会话标识会直接 500 —— 那是它的缓存分区键。',
          '想看「到底下发了哪几个工具」，去左侧「6L 检索策略实验」的场景，那里把检索这一步单独暴露出来了。'
        ]
      },
      config: [
        { key: 'conversationId', label: '会话 ID', type: 'text', default: '' }
      ],
      samples: [
        '客户 C1001 还有多少积分？',
        '客户 C1001 的订单 SO202610010001 到哪了？顺便看看他有哪些优惠券',
        '帮 C1001 查一下最近的订单、物流、发票状态和账户余额'
      ],
      call: function (cfg, text) {
        return { method: 'GET', path: '/stage6/chat', query: { message: text, conversationId: cfg.conversationId } };
      }
    },

    /* ===============================================================6L === */
    {
      id: 'stage6lab', badge: '6L', name: '检索策略实验', tagline: '不调模型，只看选对了哪些工具',
      needsKey: false,
      endpointLabel: function (cfg) {
        if (cfg.mode === 'search') return 'GET /stage6/lab/search';
        if (cfg.mode === 'compare') return 'GET /stage6/lab/compare';
        return 'GET /stage6/lab/chat';
      },
      intro: {
        title: 'Stage 6L · 把「检索」从模型链路里剥出来单独看',
        bullets: [
          '默认的 regex 索引纯靠词法：工具名权重 2、描述权重 1。中文没有空格，长口语句会整句变成一个 token。',
          '切到「纯检索」模式输入一句口语，对比四条策略命中差多少 —— 几百微秒出结果、完全确定、不花 token。',
          '切到「四策略对比」一次看齐：中文分词、业务词典、分类约束各自贡献了多少召回。',
          '口径提醒：索引只拿得到工具名和描述，<b>参数 Schema 不参与检索</b>。'
        ]
      },
      config: [
        { key: 'mode', label: '模式', type: 'select', default: 'search',
          options: [
            { value: 'search', label: '纯检索（不调模型）' },
            { value: 'compare', label: '四策略对比（不调模型）' },
            { value: 'chat', label: '真实对话（调模型）' }
          ] },
        { key: 'strategy', label: '策略', type: 'select', default: 'synonym',
          showIf: function (c) { return c.mode !== 'compare'; },
          options: [
            { value: 'regex', label: 'regex 正则' },
            { value: 'keyword', label: 'keyword 分词' },
            { value: 'synonym', label: 'synonym 同义词' },
            { value: 'category', label: 'category 分类' }
          ] },
        { key: 'category', label: '分类过滤', type: 'select', default: '',
          options: [
            { value: '', label: '不限' },
            { value: 'customer', label: 'customer 客户' },
            { value: 'order', label: 'order 订单' },
            { value: 'logistics', label: 'logistics 物流' },
            { value: 'aftersales', label: 'aftersales 售后' },
            { value: 'finance', label: 'finance 财务' },
            { value: 'product', label: 'product 产品' }
          ] },
        { key: 'conversationId', label: '会话 ID', type: 'text', default: '', showIf: function (c) { return c.mode === 'chat'; } }
      ],
      actions: [
        { label: '工具业务画像',
          call: function () { return { method: 'GET', path: '/stage6/lab/catalog' }; },
          render: function (res) {
            var rows = Array.isArray(res.data) ? res.data : [];
            return h('div', null, [
              h('div', { class: 'hit-src', style: { marginBottom: '6px' },
                text: rows.length + ' 个工具 · 未登记同义词的工具在 synonym 策略下只能靠描述分词兜底' }),
              tableView(rows, [
                { key: 'toolName', label: '工具', mono: true },
                { key: 'categoryLabel', label: '业务域' },
                { key: 'synonyms', label: '口语同义词', cls: 'syn' },
                { key: 'dictionaryRegistered', label: '已登记' }
              ])
            ]);
          } }
      ],
      samples: ['钱什么时候能退回来', '帮 C1001 查一下物流到哪了', '我要开发票'],
      call: function (cfg, text) {
        if (cfg.mode === 'search') {
          return { method: 'GET', path: '/stage6/lab/search',
            query: { q: text, strategy: cfg.strategy, max: 5, category: cfg.category }, note: 'query 直接喂给 ToolIndex' };
        }
        if (cfg.mode === 'compare') {
          return { method: 'GET', path: '/stage6/lab/compare', query: { q: text, max: 5, category: cfg.category }, note: '四策略并排' };
        }
        return { method: 'GET', path: '/stage6/lab/chat',
          query: { message: text, strategy: cfg.strategy, conversationId: cfg.conversationId } };
      },
      render: function (res, msg, app) {
        if (!res.ok) return null;
        var mode = app.cfg('stage6lab').mode;
        if (mode === 'search' && res.data && res.data.hits) {
          return h('div', null, [
            h('div', { class: 'hit-src', style: { marginBottom: '7px' },
              text: '策略 ' + res.data.strategyLabel + '（' + res.data.searchType + '）· 命中 ' + res.data.totalMatches
                + ' 个 · 耗时 ' + (res.data.tookMs === null || res.data.tookMs === undefined ? '—' : res.data.tookMs + ' ms') }),
            hitsView(res.data.hits),
            raw(res)
          ]);
        }
        if (mode === 'compare' && res.data && res.data.results) {
          var blocks = res.data.results.map(function (r) {
            return h('div', { style: { marginBottom: '13px' } }, [
              h('div', { style: { display: 'flex', gap: '8px', alignItems: 'baseline', marginBottom: '6px' } }, [
                h('b', { style: { fontSize: '12.5px' }, text: r.strategyLabel }),
                h('span', { class: 'hit-src', text: r.searchType + ' · 命中 ' + r.totalMatches + ' · ' + (r.tookMs === null ? '—' : r.tookMs + 'ms') })
              ]),
              hitsView(r.hits)
            ]);
          });
          return h('div', null,
            [h('div', { class: 'hit-src', style: { marginBottom: '8px' },
              text: '「' + res.data.query + '」· 共 ' + res.data.toolCount + ' 个工具' })]
              .concat(blocks).concat([raw(res)]));
        }
        return null;
      }
    },

    /* ================================================================ 7 === */
    {
      id: 'stage7', badge: '7', name: 'MCP 工具', tagline: '远端工具当本地工具用',
      needsKey: true,
      endpointLabel: 'GET /stage7/chat',
      intro: {
        title: 'Stage 7 · MCP：不写适配代码就接入外部系统',
        bullets: [
          'MCP Server 暴露的工具被自动包装成 <code>ToolCallback</code>，业务代码一行都不用改。',
          '<b>本场景默认不可用</b>：<code>spring.ai.mcp.client.enabled=false</code>，整个 Controller 都不会被创建（返回 404）。',
          '想体验就用 <code>mcp</code> profile 启动：<code>mvn spring-boot:run -Dspring-boot.run.profiles=mcp -Dspring-boot.run.arguments=--server.port=8090</code>，'
            + '并先手动跑一次 <code>npx.cmd</code> 预热 npm 缓存（stdio server 起不来会拖垮整个应用）。'
        ],
        warn: '当前大概率返回 404 —— 这是预期行为，不是 bug。'
      },
      config: [],
      actions: [
        { label: '列出 MCP 工具',
          call: function () { return { method: 'GET', path: '/stage7/tools' }; },
          render: function (res) {
            var arr = Array.isArray(res.data) ? res.data : [];
            if (!arr.length) return h('div', { text: '没有发现任何 MCP 工具（MCP 客户端未启用）。' });
            return h('ul', { style: { margin: '0', paddingLeft: '18px' } }, arr.map(function (s) { return h('li', { text: s }); }));
          } }
      ],
      samples: ['列出 D:/workspace 下的文件'],
      call: function (cfg, text) { return { method: 'GET', path: '/stage7/chat', query: { message: text } }; }
    },

    /* ================================================================ 8 === */
    {
      id: 'stage8', badge: '8', name: 'RAG 问答', tagline: '先检索知识库，再让模型回答',
      needsKey: true,
      endpointLabel: function (cfg) {
        return cfg.mode === 'compare'
          ? 'GET /stage8/kb/' + (cfg.kbId || 'default') + '/chat/compare'
          : 'GET /stage8/kb/' + (cfg.kbId || 'default') + '/chat';
      },
      intro: {
        title: 'Stage 8 · 朴素 RAG（L1）',
        bullets: [
          '链路：提问 → 向量检索 topK → 片段注入 Prompt → DeepSeek 生成。检索用的是本地 ONNX 模型 <code>bge-small-zh-v1.5</code>（512 维）。',
          '示例语料里「值班补贴」两处口径故意矛盾（200 元 vs 300 元），问它就能看到模型被自己的知识库割裂。',
          '切到「对照实验」模式：同一个问题问两次，并排展示「没有知识库时模型只能编」和「有知识库时出现了编不出来的具体条款」。',
          '带上自己的 .md：点「上传 md 文档」选文件 → 后端切片 → 本地 ONNX 向量化 → 存进当前库。入库后会把三步的产物摊开：切成哪几块（含正文预览）、多少维、耗时多少。',
          '换个知识库再问同一个问题 —— 这就是多知识库的意义。建库用「新建知识库」，灌数据用「上传 md 文档」或「载入示例语料」。'
        ]
      },
      config: [
        { key: 'kbId', label: '知识库', type: 'select', default: 'default',
          optionsProvider: function (app) { return app.kbOptions(); } },
        { key: 'mode', label: '模式', type: 'select', default: 'chat',
          options: [{ value: 'chat', label: 'RAG 问答' }, { value: 'compare', label: '对照实验（无 RAG vs 有 RAG）' }] }
      ],
      actions: [
        { label: '载入示例语料',
          call: function (c) { return { method: 'POST', path: '/stage8/kb/' + c.kbId + '/ingest-sample' }; },
          render: function (res, ctx) {
            var arr = Array.isArray(res.data) ? res.data : [];
            ctx.app.refreshKbs();
            return h('div', null, [
              h('div', { text: '已向「' + ctx.cfg.kbId + '」载入 ' + arr.length + ' 篇文档' }),
              h('ul', { style: { margin: '5px 0 0', paddingLeft: '18px' } },
                arr.map(function (r) { return h('li', { text: r.title + '（' + (r.chunkIds ? r.chunkIds.length : 0) + ' 块）' }); }))
            ]);
          } },
        { label: '库现状',
          call: function (c) { return { method: 'GET', path: '/stage8/kb/' + c.kbId + '/stats' }; },
          render: function (res) {
            var d = res.data || {};
            return h('div', null, [
              h('div', { style: { marginBottom: '9px' } }, kvView({
                库: d.id, 名称: d.name, 文档: d.documents, 片段: d.chunks, 维度: d.dimensions,
                topK: d.topK, 阈值: d.similarityThreshold, 切块: d.chunkSize, 已落盘: d.storeFileExists
              })),
              raw(res)
            ]);
          } },
        { label: '列出文档',
          call: function (c) { return { method: 'GET', path: '/stage8/kb/' + c.kbId + '/docs' }; },
          render: function (res) {
            var rows = Array.isArray(res.data) ? res.data : [];
            return h('div', null, [
              h('div', { class: 'hit-src', style: { marginBottom: '6px' }, text: rows.length + ' 篇文档' }),
              tableView(rows, [
                { key: 'title', label: '标题' },
                { key: 'source', label: '来源', mono: true },
                { key: 'chunks', label: '块数' },
                { key: 'docId', label: 'docId', mono: true }
              ])
            ]);
          } },
        { label: '纯检索（用输入框内容）', useText: true,
          // 注意参数名：这里是 query，而 stage6 lab 那个纯检索参数叫 q —— 别混
          call: function (c, text) { return { method: 'GET', path: '/stage8/kb/' + c.kbId + '/search', query: { query: text } }; },
          render: function (res, ctx) {
            return h('div', null, [
              h('div', { class: 'hit-src', style: { marginBottom: '7px' }, text: '命中 ' + (Array.isArray(res.data) ? res.data.length : 0) + ' 个片段 —— 这一步不经过大模型' }),
              hitsView(res.data),
              raw(res)
            ]);
          } },
        { label: '上传 md 文档',
          submitLabel: '上传并入库',
          form: [
            { key: 'files', type: 'file', label: '选择 .md 文件', required: true,
              multiple: true, accept: '.md,.markdown,.txt',
              hint: '可一次多选。标题优先取正文第一个「# 标题」，没有就用文件名。' },
            { key: 'mode', type: 'select', label: '重复上传同一份时', default: 'append',
              options: [
                { value: 'append', label: 'append 再存一份（默认）' },
                { value: 'upsert', label: 'upsert 按文件名覆盖旧的' }
              ] }
          ],
          submit: function (v, c) {
            var fd = new FormData();
            v.files.forEach(function (f) { fd.append('files', f, f.name); });
            return {
              method: 'POST', path: '/stage8/kb/' + c.kbId + '/upload',
              query: { mode: v.mode }, form: fd,
              after: function (a) { a.refreshKbs(); }
            };
          },
          render: function (res) { return pipelineView(res); } },
        { label: '新建知识库',
          submitLabel: '创建',
          form: [
            { key: 'id', label: '库 id', required: true,
              default: function () { return C.randomId('kb-web-'); },
              placeholder: '字母 / 数字 / 下划线 / 连字符',
              hint: '不能取 docs、search、stats 这类保留字 —— 它们会被路径字面量抢走（见接口实验室的说明）' },
            { key: 'name', label: '显示名', placeholder: '可以中文，留空则同 id' },
            { key: 'description', label: '备注', placeholder: '可选' }
          ],
          submit: function (v) {
            return { method: 'POST', path: '/stage8/kb',
              json: { id: v.id, name: v.name || v.id, description: v.description || '从智能助手页面创建' },
              after: function (a) { a.refreshKbs(); a.setCfg('stage8', { kbId: v.id }); } };
          },
          render: function (res) {
            var d = res.data || {};
            return h('div', null, [
              h('div', { text: '已创建「' + d.id + '」（' + d.name + '）并切到它。'
                + '空库检索不到东西是正常的 —— 先用「上传 md 文档」或「载入示例语料」灌数据。' }),
              raw(res)
            ]);
          } }
      ],
      samples: ['追光科技的年假是怎么规定的？', '值班补贴多少钱一天？', '报销流程需要几天？'],
      call: function (cfg, text) {
        var base = '/stage8/kb/' + cfg.kbId;
        return cfg.mode === 'compare'
          ? { method: 'GET', path: base + '/chat/compare', query: { message: text }, note: '两次模型调用，耗时翻倍' }
          : { method: 'GET', path: base + '/chat', query: { message: text } };
      },
      render: function (res, msg, app) {
        if (!res.ok) return null;
        if (app.cfg('stage8').mode === 'compare') return compareView(res);
        return null;
      },
      setup: function (app) { app.refreshKbs(); }
    },

    /* =============================================================== D === */
    {
      id: 'diagnostics', badge: 'D', name: '编码自检', tagline: '不花 token 的链路自检',
      needsKey: false,
      endpointLabel: 'GET /diagnostics/encoding/json',
      intro: {
        title: '编码自检 · 判断乱码到底出在哪一层',
        bullets: [
          '这两个端点不调模型，纯本地字符串回显，专门用来验证请求/响应链路的字符集。',
          '重点看响应头 <code>Content-Type</code> 是否带 <code>charset=UTF-8</code>（下面每条回复的脚注里会显示）。',
          '本项目踩过的坑：Git Bash 把中文按 GBK 传给原生 curl.exe → 服务端收到非法 UTF-8 直接 400。'
            + '而浏览器统一按 UTF-8 发出，所以在这个页面里中文参数永远不会出问题。'
        ]
      },
      config: [],
      actions: [
        { label: '看纯文本响应（最易暴露乱码）', useText: true,
          call: function (c, text) { return { method: 'GET', path: '/diagnostics/encoding/text', query: { text: text } }; },
          render: function (res) { return h('pre', { style: { whiteSpace: 'pre-wrap', fontFamily: 'var(--mono)', fontSize: '12px', margin: '0' }, text: res.text }); } }
      ],
      samples: ['测试中文', '春眠不觉晓，处处闻啼鸟'],
      call: function (cfg, text) { return { method: 'GET', path: '/diagnostics/encoding/json', query: { text: text } }; },
      render: function (res) {
        if (!res.ok || !res.data || typeof res.data !== 'object') return null;
        return h('div', null, [
          kvView(res.data),
          h('div', { style: { marginTop: '9px', fontSize: '12px' } },
            res.data.expectedCharset === 'UTF-8'
              ? h('span', { class: 'pill up', text: '字符集正常' })
              : h('span', { class: 'pill down', text: '字符集异常' })),
          raw(res)
        ]);
      }
    }
  ];

  return SCENES;
})();
