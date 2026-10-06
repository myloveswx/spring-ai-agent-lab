/* ==========================================================================
   registry.js —— 接口的声明式描述
   界面完全由这里的数据驱动：想加一个接口，只在这里加一条声明即可。
   ========================================================================== */

window.STAGES = [
  { no: '1', key: 'stage1', label: '基础对话',       tag: '规划中', ready: false },
  { no: '2', key: 'stage2', label: '对话记忆',       tag: '规划中', ready: false },
  { no: '3', key: 'stage3', label: '工具调用',       tag: '规划中', ready: false },
  { no: '4', key: 'stage4', label: 'Advisor 链',     tag: '规划中', ready: false },
  { no: '5', key: 'stage5', label: '结构化输出',     tag: '规划中', ready: false },
  { no: '6', key: 'stage6', label: '渐进式工具披露', tag: '规划中', ready: false },
  { no: '7', key: 'stage7', label: 'MCP 客户端',     tag: '默认关闭', ready: false },
  { no: '8', key: 'stage8', label: 'RAG 知识库',     tag: '可用',   ready: true  }
];

/* 参数类型速查（供读代码时对照）
     in    : 'query' | 'body' | 'path'，默认 query
     type  : 'text' | 'textarea' | 'number' | 'select'
     source: 'docs' —— select 的选项运行时从当前库的文档清单拉取            */

window.LAB = {

  stage8: {
    title: 'Stage 8 · RAG 知识库',
    intro: 'L1 朴素 RAG。整条链路只有两步：入库（原文 → 切块 → 嵌入 → 向量库）和检索增强'
         + '（问题 → 嵌入 → 相似度取 topK → 拼进 Prompt → 大模型作答）。'
         + '「朴素」指检索只用一次向量相似度，查询原样不改写、不重排、也不判断该不该检索。',
    notes: [
      '所有请求都由本页面同源发出，中文经浏览器 UTF-8 编码，不会出现命令行里那种 GBK 乱码问题。',
      '带 {kbId} 的路径会自动替换成右上角「当前知识库」。不带 kbId 的老接口形态依然存在，只是这里优先展示语义更清晰的多库写法。',
      '第 3 组（检索）不经过大模型，是排查「答非所问」的第一现场；第 4 组才会真正调用 DeepSeek。'
    ],
    groups: [
      /* ================================================================ */
      {
        no: '1',
        title: '知识库管理',
        why: '先看清「多库物理隔离」这件事：每个库在磁盘上占一个独立目录。',
        items: [
          {
            id: 'kb-list', method: 'GET', path: '/stage8/kb',
            title: '列出全部知识库',
            why: '返回每个库的 id / 名称 / 备注 / 创建时间，以及「在内存里加载了吗、有几篇文档几块」。',
            watch: 'loaded=false 时文档数是 -1 —— 向量的加载是懒的（第一次被访问才读盘），列个表不该把 20 个库全读进内存。',
            renderer: 'kbList',
            after: 'reloadKbs'
          },
          {
            id: 'kb-create', method: 'POST', path: '/stage8/kb',
            title: '创建知识库',
            why: 'id 同时是磁盘目录名，所以只允许字母/数字/下划线/连字符，中文请写在 name 里。新建的库在 <store-root>/<id>/ 下拥有独立目录，与其它库物理隔离。',
            watch: '试着用 search / stats / docs 当 id —— 会被 400 拒绝。因为路径字面量会盖过路径变量（/kb/docs 优先于 /kb/{kbId}），这样建出来的库永远访问不到。',
            renderer: 'json',
            after: 'reloadKbs',
            params: [
              { name: 'id', in: 'body', label: '库 id', required: true, placeholder: 'kb1',
                hint: '只允许字母/数字/下划线/连字符；它同时是磁盘目录名' },
              { name: 'name', in: 'body', label: '显示名', placeholder: '知识库1',
                hint: '可以是中文' },
              { name: 'description', in: 'body', label: '备注', type: 'textarea',
                placeholder: '只放 A 线资料' }
            ]
          },
          {
            id: 'kb-detail', method: 'GET', path: '/stage8/kb/{kbId}',
            title: '查看单个知识库',
            why: '只关心一个库时不必自己去数组里翻。返回名称、备注、创建时间、磁盘目录、文档数、片段数。',
            renderer: 'kbDetail'
          },
          {
            id: 'kb-update', method: 'PUT', path: '/stage8/kb/{kbId}',
            title: '更新名称 / 备注',
            why: '补的是 CRUD 里缺的那个 U。name 与 description 都是「不传就不改」—— 只改一个时另一个可以整个省略，不必先读出来再原样写回。',
            watch: '只填 name 提交，看返回结果里的 description 有没有被清空。这就是「不传就不改」和「传空就清空」的区别。',
            renderer: 'kbDetail',
            after: 'reloadKbs',
            params: [
              { name: 'name', in: 'body', label: '新显示名', placeholder: '知识库1（已改名）' },
              { name: 'description', in: 'body', label: '新备注', type: 'textarea',
                placeholder: '留空 = 不修改备注',
                hint: '空值不会被提交，所以「不传就不改」。想清空备注需要显式提交空串，命令行下用 curl 验证即可' }
            ]
          },
          {
            id: 'kb-delete', method: 'DELETE', path: '/stage8/kb/{kbId}',
            title: '删除知识库（连目录）',
            why: '注意与「清空」的区别：删除是连库带数据一起没了，清空只是把库腾空、库还在。',
            watch: '默认库（default）不允许删除，只会返回 deleted=false。因为不带 kbId 的老接口都落在它上面。',
            renderer: 'json',
            after: 'reloadKbs',
            confirm: '确定删除当前知识库吗？这个库的目录与全部向量都会被移除，不可撤销。'
          }
        ]
      },

      /* ================================================================ */
      {
        no: '2',
        title: '入库与切片',
        why: '看一眼「原文怎么变成片段」——每个片段都带着 title / source / chunkIndex 这些用于认出处的元数据。',
        items: [
          {
            id: 'ingest-sample', method: 'POST', path: '/stage8/kb/{kbId}/ingest-sample',
            title: '载入内置示例语料',
            why: '3 篇虚构企业文档：员工手册、产品与定价、运维值班规范。可重复调用，同一来源会先清理旧片段，保证幂等。',
            watch: '之所以用虚构语料：模型对它完全零先验，RAG 生效与否一眼可辨。把它分别灌进两个库，就能观察「两库各自独立」。',
            renderer: 'ingestRecords',
            after: 'reloadDocs'
          },
          {
            id: 'ingest-text', method: 'POST', path: '/stage8/kb/{kbId}/ingest',
            title: '把一段文本切块入库',
            why: '链路：原文 →（标题拼进正文）→ TokenTextSplitter 切块 → 逐块嵌入 → 该库自己的向量库。',
            watch: '默认是追加（append）：同一篇内容灌两次会得到两份。想覆盖传 mode=upsert，它按 source 定位、先删后入 —— 所以必须给一个唯一的 source，否则会连同其它 source 相同的文档一起删掉。',
            renderer: 'ingestRecords',
            after: 'reloadDocs',
            params: [
              { name: 'mode', in: 'query', label: '写入模式', type: 'select', def: 'append',
                options: [
                  { value: 'append', label: 'append —— 追加（默认，安全）' },
                  { value: 'upsert', label: 'upsert —— 按 source 先删后入' }
                ],
                hint: 'upsert 必须配一个唯一的 source，否则会误删同来源的其它文档' },
              { name: 'title', in: 'body', label: '文档标题', required: true,
                placeholder: '差旅报销补充说明', hint: '会写进每个片段的 metadata，检索后用于认出处' },
              { name: 'source', in: 'body', label: '来源标识', placeholder: 'api',
                hint: '留空默认 api。内置示例用的是文件名' },
              { name: 'content', in: 'body', label: '正文', type: 'textarea', required: true,
                placeholder: '出差住宿标准：一线城市 600 元/晚；二线城市 400 元/晚。\n\n凭据留存：需保留发票与行程单，30 天内提交报销。' }
            ]
          },
          {
            id: 'docs-list', method: 'GET', path: '/stage8/kb/{kbId}/docs',
            title: '列出库内全部文档',
            why: '拿到每篇的 docId —— 后续「查/改/删这一篇」的唯一凭据。',
            watch: '为什么用 docId 而不是标题或来源定位：标题可以重复（两篇都叫「会议纪要」很正常）；source 是「批量分组」语义，接口入库默认都是 api，用它定位会一删一大片。',
            renderer: 'docList',
            after: 'reloadDocs'
          },
          {
            id: 'doc-detail', method: 'GET', path: '/stage8/kb/{kbId}/docs/{docId}',
            title: '查看一篇文档的详情',
            why: '返回元信息 + 它被切成了哪几块、块 id 与序号分别是什么。',
            watch: '这里拿不到片段正文 —— 不是偷懒，是 VectorStore 接口根本没有 get(id)。想回显原文，正路只有自己再存一份（这正是 L2 要补的第一件事）。',
            renderer: 'docDetail',
            params: [
              { name: 'docId', in: 'path', label: '文档', type: 'select', source: 'docs', required: true,
                hint: '选项来自当前库的文档清单；库里没文档时先去上面载入示例语料' }
            ]
          },
          {
            id: 'doc-update', method: 'PUT', path: '/stage8/kb/{kbId}/docs/{docId}',
            title: '覆盖更新一篇文档',
            why: '删掉这篇的全部旧片段，用新正文重新切块入库，docId 保持不变 —— 所以「更新」对调用方是幂等的。',
            watch: '正文必传：标题是拼进正文一起嵌入的，而正文原文不由向量库保存，所以「只改标题」在当前实现里做不到。',
            renderer: 'ingestRecords',
            after: 'reloadDocs',
            params: [
              { name: 'docId', in: 'path', label: '文档', type: 'select', source: 'docs', required: true },
              { name: 'title', in: 'body', label: '新标题', placeholder: '差旅报销补充说明（2026 修订）',
                hint: '留空 = 沿用原标题' },
              { name: 'content', in: 'body', label: '新正文', type: 'textarea', required: true,
                placeholder: '（必传）更新后的完整正文' }
            ]
          },
          {
            id: 'doc-delete', method: 'DELETE', path: '/stage8/kb/{kbId}/docs/{docId}',
            title: '删除一篇文档',
            why: '连同它的全部片段一起删除，返回删掉的片段数。',
            renderer: 'json',
            after: 'reloadDocs',
            confirm: '确定删除这篇文档及其全部片段吗？',
            params: [
              { name: 'docId', in: 'path', label: '文档', type: 'select', source: 'docs', required: true }
            ]
          },
          {
            id: 'doc-delete-source', method: 'DELETE', path: '/stage8/kb/{kbId}/docs',
            title: '按来源批量删除',
            why: '用于「这一批资料整个不要了」。与 docId 的分工：docId 删一篇，source 删一批。',
            watch: 'source 必传 —— 不传就等于清空整库，那是另一个接口的语义，不应该在这里悄悄发生。',
            renderer: 'json',
            after: 'reloadDocs',
            params: [
              { name: 'source', in: 'query', label: '来源标识', required: true,
                placeholder: '01-员工手册.md', hint: '内置示例语料的 source 就是文件名' }
            ]
          }
        ]
      },

      /* ================================================================ */
      {
        no: '3',
        title: '检索（不调用大模型）',
        why: 'RAG 调优的第一现场：这里没召回正确片段，再怎么改提示词都没用。几百微秒出结果，且完全确定。',
        items: [
          {
            id: 'search', method: 'GET', path: '/stage8/kb/{kbId}/search',
            title: '纯向量检索',
            why: '把 query 嵌入后与库中所有向量算余弦相似度，按阈值过滤、取 topK。整个过程不经过 DeepSeek。',
            watch: '① 每条命中的 score 是多少，Top1 是不是你以为的那一段；② topK 与 threshold 各调一次，看召回数量怎么变 —— 阈值 0.5 偏保守，调到 0.3 会明显多进来一些。',
            renderer: 'hits',
            params: [
              { name: 'query', in: 'query', label: '查询语句', required: true, def: '年假有几天',
                placeholder: '年假有几天' },
              { name: 'topK', in: 'query', label: '返回条数', type: 'number', def: 4, width: 'half',
                hint: '留空用配置默认值' },
              { name: 'threshold', in: 'query', label: '相似度下限', type: 'number', def: 0.5, width: 'half',
                hint: '[-1,1]；传 0 相当于不过滤' }
            ]
          }
        ]
      },

      /* ================================================================ */
      {
        no: '4',
        title: 'RAG 问答（调用大模型）',
        why: '这两条链路需要真实的 DEEPSEEK_API_KEY。对照实验是整块最有说服力的一个接口。',
        items: [
          {
            id: 'chat', method: 'GET', path: '/stage8/kb/{kbId}/chat',
            title: '带检索的问答',
            why: '链路：提问 → 该库的向量检索 topK → 把片段拼进 Prompt → DeepSeek 生成。',
            watch: '控制台日志里能看到完整 Prompt，也就是「模型到底拿到了什么资料」。',
            renderer: 'answer',
            params: [
              { name: 'message', in: 'query', label: '提问', required: true, def: '追光科技的年假是怎么规定的？',
                hint: '库里有示例语料时可问：年假有几天 / 值班补贴多少钱一天' }
            ]
          },
          {
            id: 'chat-compare', method: 'GET', path: '/stage8/kb/{kbId}/chat/compare',
            title: '对照实验：无 RAG vs 有 RAG',
            why: '同一个问题问两次，并排返回两版回答，同时附上「指定库里检索到了什么」。',
            watch: '① 没有知识库时模型只能编；② 有知识库时回答里出现了不可能编出来的具体条款。示例语料里「值班补贴」被故意写成两处矛盾口径 —— 看模型是指出矛盾，还是自己挑一个。',
            renderer: 'compare',
            params: [
              { name: 'message', in: 'query', label: '提问', required: true, def: '值班补贴多少钱一天？',
                hint: '示例语料对这个问题故意留了矛盾，最能看出 RAG 的引用能力' }
            ]
          }
        ]
      },

      /* ================================================================ */
      {
        no: '5',
        title: '状态与持久化',
        why: 'SimpleVectorStore 是纯内存实现，进程一停向量就没了。这一组回答「磁盘上到底存了什么」。',
        items: [
          {
            id: 'stats', method: 'GET', path: '/stage8/kb/{kbId}/stats',
            title: '知识库现状',
            why: '库 id / 名称 / 文档数 / 片段数 / 向量维度 / 当前切块与检索参数 / 落盘文件状态，以及已入库文档清单。',
            watch: '「清单」是应用自己维护的 —— VectorStore 接口只有 add/delete/similaritySearch，没有 count/list。所以文档与片段数只能靠 manifest.tsv 记账。',
            renderer: 'stats'
          },
          {
            id: 'save', method: 'POST', path: '/stage8/kb/{kbId}/save',
            title: '手动把向量库落盘',
            why: '入库与清空之后应用都会自动落盘（清单 + 向量一起写，保证两者代际一致），所以这个接口主要用于主动确认「磁盘上那份到底是什么」。',
            watch: '返回的 sizeBytes 就是 store.json 的字节数。11 个片段大约几十 KB。',
            renderer: 'json'
          },
          {
            id: 'load', method: 'POST', path: '/stage8/kb/{kbId}/load',
            title: '从磁盘载入向量库',
            why: '清单在库第一次被访问时会自动恢复，所以这里只需把向量本体 load 回来。',
            watch: 'SimpleVectorStore.load() 是「整体替换」内存里的 Map，不是合并。',
            renderer: 'json'
          },
          {
            id: 'clear', method: 'DELETE', path: '/stage8/kb/{kbId}/clear',
            title: '清空库内容（保留库）',
            why: '把库腾空，但库本身（id / 名称 / 目录）都还在，可以立刻重新入库。',
            watch: '清空后再调一次第 3 组的检索，应该什么都检索不到 —— 这验证的是「清单为权威」那条设计：清单里没有的向量一律不认，避免出现删不掉的孤儿向量。',
            renderer: 'json',
            after: 'reloadDocs',
            confirm: '确定清空当前知识库的全部内容吗？库本身会保留。'
          }
        ]
      }
    ]
  }
};
