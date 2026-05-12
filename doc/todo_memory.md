# TODO: 长期记忆 / 向量召回 (Memory) — 详细设计

## 0. 设计目标
1. 让 agent 跨会话记住用户：偏好、事实、过去决策。
2. **统一证据池**：对话摘要、用户操作日志、崩溃事件、API 请求历史最终都进同一张表、走同一套召回。这是本模块的长期使命，第一版就要把数据模型留好口子。
3. 对现有 SDK **零侵入**：不改 `AgentLoop`、不改 `AgentSession.snapshot()` 的对外语义，只通过实现 `MemoryProvider` 接到现有钩子。

## 1. 现状盘点（已经有的，别重复造）
- `MemoryProvider` 接口：`retrieve(query, history): List<MemoryChunk>`，已被 `AgentSession.snapshot()` 在每轮 system prompt 拼装时调用。
- `MemoryChunk(text, source, score)`：召回片段数据类。
- `EmbeddingClient` + `OpenAiCompatibleEmbeddingClient`：批量向量化，OpenAI 协议，复用 `LlmProviderProfile`。
- `RerankClient` + `OpenAiCompatibleRerankClient`：精排，可选第二阶段。
- `StaticMemory`：常量回退实现，单测用。
- `AiAgentConfig.memory`：接入点，默认 `MemoryProvider.EMPTY`。
- `AgentEvent.LoopFinished`：会话结束信号，写入触发点的候选。

**结论**：检索端基础设施齐全，本期重点是「**存储 + 写入策略 + 把三者粘起来**」。

## 2. 架构总览
```
                      ┌────────────────────────────┐
   每轮 system 拼装   │  AgentSession.snapshot()   │
   ────────────────►│  └─ memory.retrieve(query) │
                      └──────────────┬─────────────┘
                                     ▼
                       ┌──────────────────────────┐
                       │  VectorMemoryProvider    │  ← 实现 MemoryProvider
                       │  ┌────────────────────┐  │
                       │  │ 1. embed(query)    │──┼─► EmbeddingClient (已有)
                       │  │ 2. topK 余弦相似度 │  │
                       │  │ 3. (可选) rerank   │──┼─► RerankClient (已有)
                       │  │ 4. 过期/去重过滤   │  │
                       │  └────────────────────┘  │
                       └────────────┬─────────────┘
                                    ▼
                       ┌──────────────────────────┐
                       │      MemoryStore         │  ← Room
                       │  memory_entries 表       │
                       │  (kind 区分来源)         │
                       └────────────▲─────────────┘
                                    │ 写入
        ┌───────────────────────────┴───────────────────────────┐
        │                                                       │
   DialogueSynthesizer                                   MemoryIngest (public API)
   (LoopFinished 触发,                                   .logUserAction / .logCrash /
    LLM 抽取长期事实)                                     .logApiCall / .remember
```

**模块归属**：新建 `aiagent/lib_ai_memory` 模块。理由：Room 依赖 + 一堆数据类，不应该塞进精简的 `lib_ai_agent_sdk`；接入方不想要长期记忆也能不引入。`lib_ai_memory` 依赖 `lib_ai_agent_sdk`（拿 `MemoryProvider` / `EmbeddingClient` 接口），不反向。

## 3. 关键设计决策（这些点你要拍板，文档里先列默认建议）

### D1. Embedding 来源
- **建议**：云端，复用现有 `LlmProviderProfile` 体系。
- 候选 provider + model：
  - SiliconFlow + `BAAI/bge-large-zh-v1.5`（1024 维，中文好，便宜）
  - SiliconFlow + `Qwen/Qwen3-Embedding-8B`（4096 维，效果更强但更贵）
  - DeepSeek 官方目前**不**提供 embedding 端点，需用其它 provider 的 key。
- 不选本地 ONNX 的原因：APK 体积 +50~150MB、首次加载延迟、与现有云端基础设施不一致。等真有离线需求再加。
- **需要你回答**：用哪个 provider + model？是否复用 chat 的同一个 profile，还是另起一个独立 profile？

### D2. 存储方案
- **建议**：Room + 单表 `memory_entries`，embedding 用 `BLOB` 存（FloatArray → ByteBuffer）。
- 不上 `sqlite-vec` / `objectbox`：第一版数据量 < 10k 条，内存里暴力余弦完全够（10k × 1024 维 ≈ 40MB / 10ms 一次相似度）。后续超规模再上向量索引。
- 不用 FTS：直接 BM25 关键词检索的价值，可以通过「lexical 兜底」单独加，不是核心路径。

### D3. 召回策略
- **第一版**：单阶段，纯余弦 top-K（K = 5）。
- **第二版（可选）**：embedding 取 top-30 → `RerankClient` 精排 top-5。是否开启由配置控制。
- **过滤**：
  - 时间衰减：`score * exp(-age_days / halfLife)`，halfLife 默认 30 天。
  - kind 白名单：retrieve 时可指定只召回某些 kind（默认全部）。
  - 重要度门槛：`importance < threshold` 的条目跳过。

### D4. 写入触发
两条独立路径并存：
- **自动总结**（dialogue → facts）：监听 `AgentEvent.LoopFinished`，跑一个独立的、非流式的 LLM 调用，prompt 类似「从下面对话中抽取关于用户的长期事实（偏好/身份/约束），每条一行 JSON：`{fact, importance: 1-5, tags: [...]}`。无可抽取就返回空数组」。
- **主动 ingest**（外部事件 → 证据）：`MemoryIngest` 暴露给宿主 app 的公开 API。这里就是你说的"日志/崩溃/接口历史"的入口。第一版只把 API 接出来，具体调用点由接入方自己决定。

### D5. 去重 & 冲突
- 写入时对 fact 文本做 embedding，与库内同 kind 的 top-1 比较，余弦 > 0.92 视为重复 → 用新条目更新旧条目的 `updated_at` 和 `last_seen`，不新增。
- 冲突（"用户喜欢香菜" vs "用户讨厌香菜"）第一版不处理，后期由 importance / 时间戳让新条目自然盖旧条目（召回时新的排前面）。

### D6. 隐私 & 清理
- 提供 `MemoryStore.clearAll()` / `clearByKind()` / `clearOlderThan(days)`。
- 用户主动调"忘记 X"通过一个 `forget_memory` tool 暴露（可选，第二期）。

---

## 4. 数据模型

### 4.1 `memory_entries` 表
| 字段              | 类型     | 说明 |
|-------------------|----------|------|
| `id`              | TEXT PK  | UUID,跨设备同步时也用得上 |
| `kind`            | TEXT     | 枚举:`USER_FACT` / `DIALOGUE_SUMMARY` / `APP_LOG` / `CRASH` / `API_CALL` / `CUSTOM`。预留扩展。 |
| `text`            | TEXT     | 入 system prompt 的人话内容,**也是 embedding 的输入**。 |
| `embedding`       | BLOB     | FloatArray.toByteArray()。维度由 `embedding_dim` 字段冗余,便于做模型升级时的批量重算。 |
| `embedding_model` | TEXT     | 写入时用的模型 id,模型升级后老数据可识别为"待重算"。 |
| `embedding_dim`   | INT      | 维度,用于校验 + 升级路径。 |
| `source`          | TEXT?    | 自由文本,例如 `session:xxx` / `crash:abc` / `api:POST /v1/order`。会渲染到 `[source]` 前缀。 |
| `tags`            | TEXT?    | JSON Array,用于灵活筛选(如 `["preference","food"]`)。 |
| `importance`      | INT      | 1-5,影响时间衰减后的最终 score 与是否被淘汰。 |
| `created_at`      | INT      | epoch millis |
| `updated_at`      | INT      | 去重命中时更新 |
| `last_seen_at`    | INT      | 最近一次被召回的时间,用于"用进废退"淘汰 |
| `expires_at`      | INT?     | 可选过期时间,API_CALL / APP_LOG 一般设短期 |

### 4.2 `MemoryKind` 枚举(开放扩展)
```kotlin
enum class MemoryKind {
    USER_FACT,         // 用户长期事实(LLM 总结产出)
    DIALOGUE_SUMMARY,  // 整段对话的压缩摘要
    APP_LOG,           // 用户操作日志(未来由接入方 ingest)
    CRASH,             // 崩溃事件(未来)
    API_CALL,          // 接口请求历史(未来)
    CUSTOM,            // 业务方自定义
}
```
`APP_LOG` / `CRASH` / `API_CALL` 第一期**不实现写入路径**,但表结构 / 枚举 / 召回都已经原生支持,接入方未来直接调 `MemoryIngest.log(kind = APP_LOG, ...)` 即可。

## 5. 组件清单(要新建的类 / 文件)

新模块路径:`aiagent/lib_ai_memory/src/main/java/com/aiagent/memory/`

| 文件 | 职责 |
|------|------|
| `db/MemoryDatabase.kt`     | `@Database` 入口,单例,DI 友好 |
| `db/MemoryEntity.kt`       | Room `@Entity`,字段对齐 §4.1 |
| `db/MemoryDao.kt`          | `insert/update/delete/loadAll/loadByKinds/countByKind`;暂不写复杂 SQL,相似度在内存做 |
| `db/Converters.kt`         | FloatArray ↔ ByteArray;List<String> ↔ JSON |
| `MemoryStore.kt`           | DAO 之上的薄封装,提供 `add / upsert / similarTo(query, k) / clear*` |
| `VectorMemoryProvider.kt`  | 实现 `MemoryProvider`;构造参数:`store`, `embeddingClient`, `embeddingModel`, `config: RetrievalConfig` |
| `RetrievalConfig.kt`       | data class:`topK`, `minScore`, `halfLifeDays`, `kinds: Set<MemoryKind>?`, `useRerank: Boolean`, `rerankClient`, `rerankModel` |
| `synth/DialogueSynthesizer.kt` | 监听 `LoopFinished`,拉 history,调一次 LLM 抽事实,批量写入 |
| `synth/FactExtractionPrompt.kt` | system + few-shot 模板,集中放这,方便迭代调参 |
| `ingest/MemoryIngest.kt`   | 公开 API:`logUserFact / logAppEvent / logCrash / logApiCall / remember / forget`。第一期主要给 §6 写入路径用,但接口完整暴露 |
| `MemoryModule.kt`          | 装配入口:`fun installMemory(context, embedding, profile, …): VectorMemoryProvider`;返给接入方塞进 `AiAgentConfig.memory` |
| `internal/Cosine.kt`       | 暴力余弦 + (可选)归一化缓存 |
| `internal/TimeDecay.kt`    | `score * exp(-ageDays / halfLife)` |

### 5.1 现有文件要改动的地方(最小)
- `AiAgentConfig`:**不改**。`memory` 字段已经是 `MemoryProvider`,接入方在 `DemoApp` 里把 `StaticMemory(...)` 换成 `MemoryModule.installMemory(...)` 即可。
- `AgentLoop` / `AgentSession`:**不改**。retrieve 路径已经接好。
- 监听 `LoopFinished` 的 hook:不直接改 `AgentLoop`,而是在 `DemoApp` 接入处包一层 —— 让 `DialogueSynthesizer` 通过外部观察 `loop.run(...).collect` 的方式注入(`AgentChatViewModel` / `HeadlessRunner` 各调一次)。这样 SDK 主循环零侵入。

### 5.2 配置接入示例(预览,不是最终代码)
```kotlin
// DemoApp.kt
val memoryProfile = LlmProviderProfile.siliconFlow(
    baseUrl = "https://api.siliconflow.cn",
    apiKey  = BuildConfig.SILICONFLOW_KEY,
    model   = "BAAI/bge-large-zh-v1.5",
)
val memory = MemoryModule.installMemory(
    context = this,
    embeddingClient = OpenAiCompatibleEmbeddingClient(memoryProfile),
    embeddingModel  = "BAAI/bge-large-zh-v1.5",
    chatLlm         = AiAgentRuntime.llmClient,   // 用于事实抽取
    config          = RetrievalConfig(topK = 5, halfLifeDays = 30),
)
AiAgentRuntime.install(AiAgentConfig(..., memory = memory))
```

## 6. 召回链路(伪代码)

```kotlin
// VectorMemoryProvider.retrieve(query, history)
1. if query.isBlank() return []
2. val qVec = embeddingClient.embed(model, [query]).first()
3. val candidates = store.loadAll(kinds = config.kinds)   // O(N) 进内存
4. val scored = candidates.map { e ->
       val cos = cosine(qVec, e.embedding)
       val ageDays = (now - e.createdAt) / DAY
       val decayed = cos * exp(-ageDays / config.halfLifeDays)
       val final   = decayed * importanceFactor(e.importance)
       e to final
   }.filter { it.second >= config.minScore }
    .sortedByDescending { it.second }
    .take(if (config.useRerank) 30 else config.topK)
5. val top = if (config.useRerank) {
       rerankClient.rerank(model, query, scored.map { it.first.text }, topN = config.topK)
           .map { scored[it.index].first to it.score }
   } else scored.take(config.topK)
6. top.forEach { (e, _) -> store.touchLastSeen(e.id, now) }
7. return top.map { (e, s) -> MemoryChunk(text=e.text, source=e.source, score=s) }
```

**性能预算**:N = 10k、dim = 1024 时,余弦全跑 ≈ 8ms(JVM,FloatArray dot)。embedding 端 1 次网络请求 ~200ms。总耗时 retrieve ≈ 250ms,与 LLM 首字延迟同量级,可接受。后期变慢再加内存索引。

## 7. 写入链路(伪代码)

### 7.1 自动总结(LoopFinished 触发)
```kotlin
// DialogueSynthesizer.onLoopFinished(session: AgentSession)
1. val history = session.snapshotHistory()         // 需要 AgentSession 新加只读 getter
2. if history.userMessages < 2 return              // 单轮不抽,信噪比低
3. val prompt = FactExtractionPrompt.build(history)
4. val raw    = chatLlm.chatStream(prompt).collectToString()
5. val facts  = parseJsonArray(raw)                 // [{fact, importance, tags}, ...]
6. for f in facts:
       val vec = embed([f.fact]).first()
       val dup = store.similarTo(vec, kind=USER_FACT, k=1).firstOrNull()
       if (dup != null && cosine(vec, dup.embedding) > 0.92) {
           store.updateTimestamps(dup.id, updatedAt=now, lastSeen=now)
       } else {
           store.add(MemoryEntity(kind=USER_FACT, text=f.fact, ...))
       }
```

`AgentSession` 需要补一个 `fun snapshotHistory(): List<Message>` 的只读 getter(目前 history 是 private 的)。这是本项目唯一需要碰 SDK 内部的地方。

### 7.2 主动 ingest(给宿主 app 用)
```kotlin
object MemoryIngest {
    suspend fun remember(text: String, importance: Int = 3, tags: List<String> = emptyList())
    suspend fun logAppEvent(text: String, source: String, expiresInDays: Int? = 7)
    suspend fun logCrash(stackTrace: String, source: String)
    suspend fun logApiCall(summary: String, source: String, expiresInDays: Int? = 3)
    suspend fun forget(id: String)
    suspend fun forgetByKind(kind: MemoryKind)
}
```

第一期把 API 完整暴露,但**不主动在任何地方调用**(避免误埋点污染数据)。接入方按需埋。

## 8. 实施分期(Milestones)

### M1 — 接口与存储骨架(本期,2~3 天)
- [ ] 新建 `aiagent/lib_ai_memory` 模块,Gradle 配好(Room + KSP)
- [ ] `MemoryEntity` / `MemoryDao` / `MemoryDatabase` / `Converters`
- [ ] `MemoryStore` 薄封装
- [ ] `internal/Cosine.kt` + `internal/TimeDecay.kt`(纯函数,JVM 单测)
- [ ] **单元测试**:用 in-memory Room + mock `EmbeddingClient`(返回确定向量),验证 add / topK / 去重逻辑

### M2 — VectorMemoryProvider 接通主流程(2 天)
- [ ] `VectorMemoryProvider` 实现 `MemoryProvider`
- [ ] `RetrievalConfig` + `MemoryModule.installMemory(...)` 装配 API
- [ ] `DemoApp` 替换 `StaticMemory`,跑一条 chat 验证 system prompt 里出现 `## 相关记忆`
- [ ] **集成测试**:用 mock embedding(确定向量),验证「写入 3 条 → query 命中 1 条」

### M3 — 自动总结写入(2 天)
- [ ] `FactExtractionPrompt` 模板调优(中文 few-shot)
- [ ] `DialogueSynthesizer.observe(loopFlow)`,在 `AgentChatViewModel` 与 `HeadlessRunner` 各接一次
- [ ] `AgentSession` 加 `snapshotHistory()` 只读 getter(SDK 侧唯一改动)
- [ ] **端到端 demo**:对话里说"我讨厌香菜",第二轮 query"晚饭吃什么",看到 system prompt 召回这条

### M4 — 公开 ingest API + 隐私管理(1 天)
- [ ] `MemoryIngest` 全套方法
- [ ] `MemoryStore.clearAll / clearByKind / clearOlderThan`
- [ ] Demo 设置页加"清空记忆"按钮(可选,演示用)

### M5(未来,不在本期)
- 接入"用户日志 / 崩溃 / 接口历史"的具体埋点
- Rerank 精排开关
- 本地 ONNX embedding(完全离线场景)
- `forget_memory` tool(让 LLM 自己决定遗忘)

## 9. 风险 & 待确认问题(动手前你需要回答)

1. **Embedding provider/model 选哪个?**(D1)
   建议:SiliconFlow + `BAAI/bge-large-zh-v1.5`。需要你提供一个 SiliconFlow key,或确认换别的 provider。
2. **Memory 写入用的总结 LLM,复用现有 chat profile,还是单独配?**
   建议:复用 `AiAgentRuntime.llmClient`,不增加配置。
3. **是否要 rerank?第一版默认关。**
4. **数据清理策略**:是否在第一版就提供"清空记忆"入口?还是先只埋 API、UI 后期再加?
5. **新模块 `lib_ai_memory` 是否要发 Maven?** 现有 `lib_ai_agent_sdk` / `lib_ai_agent_ui` 都发。建议同步发,保持一致。
6. **AgentSession.snapshotHistory()** 这个对内开放的 getter 你接受吗?这是本期对 SDK 唯一的侵入性改动。

## 10. 验收场景

- **场景 A(基本召回)**:用户第一天说「我讨厌香菜」。重启 app,第三天问「帮我点份午饭」。预期:agent 的 system prompt 里出现 `## 相关记忆\n- [user_fact] 用户讨厌香菜`,模型回复时会主动避开。
- **场景 B(去重)**:连续两次让 agent 抽出同一事实,数据库里只保留 1 条,但 `updated_at` 被刷新。
- **场景 C(时间衰减)**:30 天前写入的低重要度事实,与今天写入的同类型事实并存时,排序在后。
- **场景 D(扩展性预演)**:手动调用 `MemoryIngest.logCrash(stackTrace, source="MainActivity")` → 下一次 retrieve 时如果 query 相关,这条 CRASH 能被召回到 prompt。

