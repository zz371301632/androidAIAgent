# lib_ai_agent_sdk

AI Agent 运行时。负责跑主循环、发 LLM 请求、调度工具、做 progressive disclosure。
**项目无关**,任何 Android app 都能直接抄走。
业务方上手指引看 [aiagent/README.md](../README.md),本文档面向 SDK 维护者。

## 模块层次

```
com/aiagent/sdk/
├── agent/    主循环 + 会话(消费 llm + tool + skill)
├── llm/      LLM 客户端(OpenAI 兼容协议、SSE 流式)
├── skill/    Skill 抽象 + progressive disclosure
├── tool/     ToolRegistry(注册中心,从 lib_ai_annotations 的 AiCapabilityRegistry snapshot)
└── log/      统一日志接口(由 host app 注入实现,SDK 自身不依赖 android.util.Log)
```

## 文件地图

### agent/
| 文件 | 职责 |
|---|---|
| `AgentLoop.kt` | **核心主循环**。每轮把 system + history + 已加载 skill 工具发给 LLM,接 tool_calls 转 ToolInvoker,直到 stop |
| `AgentSession.kt` | 一次对话的会话上下文(message history、已 load 的 skill 集合) |
| `AgentEvent.kt` | 事件流密封类:Token / ToolStarted / ToolCompleted / AwaitingConfirmation / Done |
| `ToolInvoker.kt` | 调度 `Tool.execute(args)`,处理 requiresConfirmation 暂停 / 错误兜底 |
| `StreamAccumulator.kt` | SSE 流式 chunk 拼装为完整 message + tool_calls |

### llm/
| 文件 | 职责 |
|---|---|
| `LlmClient.kt` | 抽象接口:`chatStream(...) : Flow<...>` |
| `OpenAiCompatibleClient.kt` | 默认实现,直接走 `/v1/chat/completions` SSE |
| `SseParser.kt` | 自己写的 SSE 解析(不引第三方,保持瘦身) |
| `Models.kt` | LLM 协议层 DTO(Message / ToolCall / Choice 等) |
| `LlmProvider.kt` / `LlmProviderProfile.kt` / `LlmClientFactory.kt` | 多 provider(豆包 / 通义 / OpenAI / Claude 兼容)切换 |
| `EmbeddingClient.kt` / `RerankClient.kt` | 检索辅助(本仓暂未在主链路启用) |
| `ThinkTagFilter.kt` | 过滤推理模型 `<think>...</think>` 段(只显示给用户最终内容) |

### skill/
| 文件 | 职责 |
|---|---|
| `Skill.kt` | Skill 接口:`id` / `name` / `description` / `loadInstructions()` / `toolNames` |
| `AssetSkill.kt` | 从 `assets/skills/<id>/SKILL.md` 读取的 skill(markdown 版,适合密集 prompt) |
| `CodeSkill.kt` | 从 KSP 产物 `SkillManifest` 桥接的 skill(注解版,**当前主流写法**) |
| `SkillManifestParser.kt` | 解析 `assets/skills/<id>/SKILL.md` 的 frontmatter |
| `SkillRegistry.kt` | 进程级 skill 仓库 |
| `SkillCallTools.kt` | 暴露给 LLM 的两个内置工具:`list_skills` / `load_skill` |

### tool/ + log/
- `ToolRegistry.kt`:统一工具仓库,启动时 `AiCapabilityRegistry.snapshotTools()` 灌入
- `AgentLogger.kt` + `AgentLoggerHolder.kt`:日志依赖反转,host app 注入实现,SDK 不直接 import `android.util.Log`,方便 JVM 单测

## 关键设计原则

| 原则 | 落地方式 |
|---|---|
| **App-agnostic** | 不走项目内的 `gradle/common_library.gradle`,只引 OkHttp + 协程 + 注解契约 |
| **依赖反转** | 日志走 holder,业务工具走 `AiCapabilityRegistry` snapshot,SDK 对业务零感知 |
| **流式优先** | `LlmClient.chatStream` 返回 `Flow`,UI 层订阅做打字机效果 |
| **Progressive disclosure** | 默认只暴露 `list_skills` / `load_skill`,模型按需加载,避免 prompt 爆炸 |
| **可中断** | AgentLoop 里所有 IO 都在 suspend 函数里,外部 `cancel()` 即停 |
| **可观测** | 关键节点都打 `AiAgent_*` tag,字段命名 stable(`round` / `loaded` / `toolSchemas`) |

## ProGuard

`consumer-rules.pro` 已 keep:
- `com.aiagent.runtime.*` 全部(注解契约不能被混淆,KSP 生成代码引用)
- `bootAiTools_*` 顶层函数(运行时反射查找会失败,实际是显式调用,但 keep 一手保险)
- KSP 生成的 `*AiTool` 实现类(它们 `Tool` 接口下的字段 / 方法被 LLM 协议层用)

修改 SDK 公开 API 时记得同步检查 `consumer-rules.pro`,**漏 keep 在 release 包才暴雷**。

## 测试

```
./gradlew :aiagent:lib_ai_agent_sdk:test
```

`AgentLoopTest.kt` 是主要回归点,覆盖:多轮 tool_call 调度 / load_skill 后工具列表变化 /
失败兜底 / requiresConfirmation 暂停。改 AgentLoop 调度逻辑前**强烈建议先把它跑一遍**。

## 依赖

| 依赖 | 用途 |
|---|---|
| `lib_ai_annotations` (api) | Tool / ToolResult / SkillManifest / AiCapabilityRegistry |
| OkHttp 3 | LLM HTTP / SSE |
| kotlinx-coroutines-android | Flow / 主循环 |
| `org.json` | 自带,JSON 解析 |

刻意没引:Retrofit / Moshi / Gson / Compose / Coil。**加新依赖前先想想能不能不加**。
