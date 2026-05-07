# androidAIAgent

一个**可独立移植**的 Android AI Agent SDK + 一个最小可跑通的 Demo App。

挂上 `@AiTool` 注解,你的 Kotlin 函数就能被大模型在本地调用 —— 不需要写 prompt
模板、不用维护 tool 注册表、不用手撸 OpenAI 协议解析。一切由 KSP 编译期生成 +
SDK 运行期托管。

![demo](res/demo.gif)

## 目录结构

```
androidAIAgent/
├── aiagent/                  ← 真正想抄走的 SDK,跟项目零耦合
│   ├── lib_ai_annotations/   注解契约 + Runtime 接口
│   ├── lib_ai_compiler/      KSP 处理器,扫 @AiTool / @AiSkill 生成注册代码
│   ├── lib_ai_agent_sdk/     运行时:AgentLoop / LLM 客户端 / Skill / Tool
│   └── lib_ai_agent_ui/      可选 Compose 聊天 UI(Screen / ViewModel / Quick Tools)
│
└── app/                      ← Demo:演示 SDK 如何接入
    ├── DemoApp.kt            ★ 唯一接入面板:AiAgentRuntime.install(AiAgentConfig(...))
    └── com.zhangz.androidaiagent.demo
        ├── tools/            DemoTools.kt:三个示例工具
        ├── bootstrap/        AppContextHolder + HeadlessRunner + LogcatAgentLogger
        ├── headless/         adb 派任务的策略 / 反馈
        └── ui/               AgentChatActivity / HeadlessAgentActivity(壳,UI 在 lib_ai_agent_ui)
```

## 架构

```mermaid
flowchart LR
  subgraph BIZ["业务层(可替换)"]
    UI["Compose UI<br/>AgentChatScreen"]
    VM["AgentChatViewModel<br/>事件流 → UI state"]
    Tools["@AiTool 标注的<br/>业务函数"]
  end

  subgraph KSP["编译期(KSP 自动生成)"]
    Boot["bootAiTools_module()<br/>注册到 Registry"]
  end

  subgraph SDK["aiagent/ SDK(零业务)"]
    Loop["AgentLoop<br/>ReAct 主循环"]
    LLM["LlmClient<br/>OpenAI 兼容协议<br/>SSE 流式"]
    TR["ToolRegistry"]
    SR["SkillRegistry<br/>progressive disclosure"]
  end

  Tools -. KSP 扫描 .-> Boot
  Boot -- register --> TR
  Boot -- register --> SR
  UI <--> VM
  VM <--> Loop
  Loop <--> LLM
  Loop <--> TR
  Loop <--> SR
  LLM <-->|HTTPS| DeepSeek["DeepSeek / 任意<br/>OpenAI 兼容网关"]
```

## 5 分钟跑通 Demo

1. **填 key**:打开仓库根 `local.properties`(已 git-ignored,**永远不会进 git**),
   把 `ai.deepseek.key=` 那行的注释打开,粘上你的 [DeepSeek API key](https://platform.deepseek.com/)。
2. **同步工程**:Android Studio 打开本目录,`File → Sync Project with Gradle Files`。
3. **跑起来**:连真机或模拟器,直接 Run `app`。
4. 入口页点「打开 AI 助手」→ 输入「现在几点?」→ 模型会调 `device_time` 工具,把 UTC 时间放在气泡里返回。
5. 试试「弹个 Toast 说你好」→ 走 `show_toast`;再试「清空历史」→ 会先弹**确认对话框**,这是 `requiresConfirmation = true` 的演示。

### 接入面板:`AiAgentConfig`

**所有接入决策都集中在 `DemoApp.onCreate` 里的 `AiAgentRuntime.install(...)` 一处**,
其它文件零改动:

```kotlin
class DemoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContextHolder.application = this           // 仅服务需要 Context 的 @AiTool
        AiAgentRuntime.install(AiAgentConfig(
            kspBootstraps   = listOf(::bootAiTools_app),  // 必填:KSP 生成函数,多模块就列多个
            persona         = MY_AGENT_PERSONA,           // 必填:你的 Agent 是谁,SDK 不替你想
            profile         = LlmProviderProfile.deepSeekOfficial(...), // 想接哪家直接传哪家
            logger          = LogcatAgentLogger,          // 可选,默认 noop
            memory          = StaticMemory(listOf(/* facts */)), // 可选,默认 EMPTY
            subAgentPresets = listOf(SubAgentPreset(...)),// 可选,默认空(不开 sub-agent)
        ))
    }
}
```

**SDK 不替你做任何业务向假设** —— `kspBootstraps` 与 `persona` 都是必填(persona 是
「我的 Agent 是谁」的显式声明,接入方必须自己给一份);其余字段空就是真空,与「不开启
该能力」等价。

### 换一家 LLM provider

SDK 把「OpenAI 兼容协议 + 可选自定义 header / body」的差异收敛到
`LlmProviderProfile`,**换 provider 就是换一个 profile**:

```kotlin
// A. SDK 内置的 SiliconFlow profile
profile = LlmProviderProfile.siliconFlow(
    baseUrl = "https://api.siliconflow.cn",
    apiKey  = "sk-...",
    model   = "deepseek-ai/DeepSeek-V3",
)

// B. 自部署 / 公司内网网关:用最底层构造器,自己写 decorate(每次发请求前调一次)
profile = LlmProviderProfile(
    provider = LlmProvider.CUSTOM_GATEWAY,
    baseUrl  = "https://your-gateway.example.com",
    apiKey   = "...",
    model    = "deepseek-chat",
    decorate = { reqBuilder, body ->
        val traceId = UUID.randomUUID().toString()
        reqBuilder.addHeader("trace_id", traceId) // 网关要求的额外头
        body.put("rid", traceId)                  // 网关要求的额外 body 字段
    },
)
```

> Demo 工程额外接了一条 BuildConfig → `local.properties` 的兜底链(见
> `DemoApp.profileFromBuildConfig`),让人不改代码也能切 key——业务接入想要更直接的
> 写法,直接传一个 profile 写死就行,把那个私有方法整段删掉即可。

> 想把 baseUrl / key / model 也搬出 git?把它们写进 `local.properties`,
> 在 `app/build.gradle.kts` 加 `buildConfigField` 透传到代码里读 BuildConfig 即可
> ——参考已有的 `ai.deepseek.*` 那一组的写法。

> baseUrl 是 `http://`(自部署 / 内网网关常见)时,Android 9+ 会拦明文流量。
> Demo 已在 `app/src/main/res/xml/network_security_config.xml` 全局放行 cleartext
> 并在 manifest 引用,需要收紧时改成 per-domain `<domain-config>` 白名单即可。

## 无 UI 触发(adb headless)

调试 / 自动化场景下不想点 UI?装上 debug 包后,直接用 adb 派任务:

```bash
# 默认安全:危险工具(requiresConfirmation=true)一律拒绝
adb shell am start -a com.zhangz.androidaiagent.HEADLESS \
    -e task "现在几点"

# 显式放行危险工具
adb shell am start -a com.zhangz.androidaiagent.HEADLESS \
    -e task "清空演示历史" \
    --ez allowDangerous true

# 预加载 skill,省一轮 list_skills/load_skill
adb shell am start -a com.zhangz.androidaiagent.HEADLESS \
    -e task "用 toast 弹一句你好" \
    -e loadSkills "demo"

# 看反馈(任务派单 / 每轮 / 完成 / 失败,以及 SDK 自身日志)
adb logcat -s AiAgent_Headless,AiAgent_Loop,AiAgent_Req,AiAgent_Resp
```

| Intent extra | 类型 | 说明 |
|---|---|---|
| `task` | String,必填 | 任务描述,空串会被 reject |
| `allowDangerous` | Boolean,默认 false | 放行 `requiresConfirmation=true` 的工具 |
| `loadSkills` | String,默认空 | 逗号分隔的 skill id,启动前预加载 |

实现:`HeadlessAgentActivity`(`Theme.NoDisplay` + `onCreate` 立即 `finish`)→
`HeadlessRunner.run`(ApplicationScope 跑 AgentLoop,Activity 销毁不影响)→
`HeadlessReporter`(logcat + Toast)。仅 `BuildConfig.DEBUG` 真正执行,release 包
进入即 finish;真要彻底隔离可把 manifest 那条 activity 搬到 `app/src/debug/`。

## 30 秒加一个工具

在你自己的 Android 模块里挑一个 `object`,挂注解就完事:

```kotlin
@AiSkill(id = "navigation", name = "页面跳转")
object NavigationAi {

    @AiTool(description = "跳转到指定 URI 的内部页")
    suspend fun openUri(uri: String): String {
        // 你的真实业务 ...
        return "ok"
    }
}
```

KSP 会在 `app/build/generated/ksp/.../AiToolsBoot_<bootName>.kt` 生成一个
`bootAiTools_<bootName>()` 函数,启动时调一次,工具就接上了。约束:

- **必须 `object` + `suspend`**(单例 + 协程,SDK 这样才能零反射调用);
- **参数仅基础类型**(`String / Int / Long / Boolean / Double / Float`),复杂入参建议拆分;
- **返回 `String`**(返回给模型看的内容,纯文本即可)。

详细规则、Skill 用法、KSP 注意事项 → [`aiagent/README.md`](aiagent/README.md)。

## 接入到自己的工程

复制以下目录,**用不到 UI 就别复制 `lib_ai_agent_ui`**:

```
你的工程/
├── aiagent/lib_ai_annotations/    ← 必抄
├── aiagent/lib_ai_compiler/       ← 必抄
├── aiagent/lib_ai_agent_sdk/      ← 必抄
└── aiagent/lib_ai_agent_ui/       ← 可选:想要现成 Compose 聊天 UI 才抄
```

然后在自己业务模块的 `build.gradle.kts` 里:

```kotlin
plugins { alias(libs.plugins.ksp) }

ksp { arg("aiagent.bootName", "myModule") }   // 决定生成函数名后缀

dependencies {
    implementation(project(":aiagent:lib_ai_agent_sdk"))
    implementation(project(":aiagent:lib_ai_annotations"))
    kspDebug(project(":aiagent:lib_ai_compiler"))

    // 可选:要现成聊天 UI 加这一行;不要就只收 AgentLoop 事件流自己渲染
    implementation(project(":aiagent:lib_ai_agent_ui"))
}
```

最后参考 [`app/.../DemoApp.kt`](app/src/main/java/com/zhangz/androidaiagent/DemoApp.kt)
在自家 `Application.onCreate` 里调一次 `AiAgentRuntime.install(AiAgentConfig(...))`,
配置就完了 —— 接入面板就这一处,其它文件不用改。需要 Context 的 `@AiTool`(弹 Toast、
读 assets 等)按 demo 那样在 `AppContextHolder` 里持一份 Application 即可,SDK 自身不
持有任何 Android 单例。

**用现成 UI**:Activity 里 5 行:

```kotlin
class MyAgentActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface { AgentChatScreenHost() } } }
    }
}
```

`AgentChatScreenHost` 内部用 `viewModel<AgentChatViewModel>()` 自动接 `AgentLoop`
事件流,确认弹窗 / 子 Agent 嵌套气泡 / 流式输出 / 快捷工具栏全都现成。

**自己画 UI**:不依赖 `lib_ai_agent_ui`,直接收 `AgentLoop`:

```kotlin
val loop = AiAgentRuntime.newAgentLoop(::confirmDangerous)
val session = AgentSession(AiAgentRuntime.skills, AiAgentRuntime.persona, AiAgentRuntime.memory)
loop.run(session, userInput).collect { event -> /* 翻译成自家 UI state */ }
```

事件类型见 `AgentEvent`。复杂的事件→气泡翻译可以参考
[`AgentChatViewModel`](aiagent/lib_ai_agent_ui/src/main/java/com/aiagent/ui/AgentChatViewModel.kt)。

## 工具链

| 项 | 版本 |
|---|---|
| Gradle | 8.14.3 |
| Android Gradle Plugin | 8.11.2 |
| Kotlin | 2.2.0 |
| KSP | 2.2.0-2.0.2 |
| compileSdk / minSdk | 35 / 24 |

## 进一步阅读

- [`aiagent/README.md`](aiagent/README.md) — SDK 接入完整说明
- [`aiagent/lib_ai_agent_sdk/README.md`](aiagent/lib_ai_agent_sdk/README.md) — Runtime 内部设计
- [`aiagent/lib_ai_compiler/README.md`](aiagent/lib_ai_compiler/README.md) — KSP 处理器细节
- [`aiagent/lib_ai_agent_ui/`](aiagent/lib_ai_agent_ui/) — 可选 Compose 聊天 UI(Screen / ViewModel / Quick Tools)
