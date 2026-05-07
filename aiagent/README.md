# AI Agent SDK

App 内置的轻量 Agent 框架,业务方挂注解即可让 AI 调用本地能力。本目录是 SDK 实现,
**业务开发者一般不需要进来**,看完本文档你就知道怎么在自己的模块里加 AI 工具了。

```
aiagent/
├── lib_ai_annotations/   注解契约 + Runtime 接口(@AiTool / @AiSkill / Tool / ToolResult)
├── lib_ai_compiler/      KSP 处理器:扫注解,生成注册代码
├── lib_ai_agent_sdk/     运行时:AgentLoop / LLM 客户端 / Skill / Tool 注册表
└── lib_ai_agent_ui/      可选:Compose 聊天 UI(AgentChatScreen / ViewModel / Quick Tools)
```

> UI 是可选模块。要现成聊天界面就 `implementation(":aiagent:lib_ai_agent_ui")`,
> 想自己画就只 `implementation(":aiagent:lib_ai_agent_sdk")` 自家收 `AgentLoop` 事件流。

---

## 30 秒上手:加一个 AI 工具

业务模块里建一个 `object`,挂上注解,**就这一处**:

```kotlin
package com.example.app.ai

import com.aiagent.annotations.AiSkill
import com.aiagent.annotations.AiTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@AiSkill(id = "navigation", name = "页面跳转")   // description 留空,KSP 自动聚合
object NavigationAi {

    @AiTool(
        description = "打开扫码页让用户扫描二维码,适用于添加好友、扫码登录、扫码授权等场景。",
        category = "navigation",
    )
    suspend fun jumpScanActivity() = withContext(Dispatchers.Main) {
        CommonPageRouter.jumpToScanActivity(FxAppLifecycleProvider.getTopActivity())
    }
}
```

Build 一次,跑起来对 Agent 说「打开扫一扫」,扫码页弹出。**全程不需要写 SKILL.md,
不需要在 AiAgentRuntime 里 register,不需要拼 JSON 返回值。**

---

## `@AiTool` 详解

| 字段 | 必填 | 说明 |
|---|---|---|
| `description` | ✅ | 给模型看的功能简介,一句话讲清楚「能做什么 / 什么场景用」。**关键词写清楚是模型选中本工具的前提**。 |
| `name` | ❌ | 工具名,默认按函数名 snake_case 化(`jumpScanActivity` → `jump_scan_activity`) |
| `requiresConfirmation` | ❌ | 高危操作设 `true`,Agent 会先弹用户确认。切账号/删数据/解绑生物认证等不可逆操作必须设 |
| `category` | ❌ | UI 分组与日志归类标签,对模型不可见 |

**KSP 编译期约束**:
- 必须是 `object` 的成员函数或顶层函数(实例字段无法在编译期 new)
- 必须 `suspend`
- 返回类型 `Unit` 或 `String`(`Unit` 自动兜成 `"ok"`,跳转/触发类工具用 `Unit` 即可)
- 参数限于:`String` / `Int` / `Long` / `Boolean` / `Double` / `Float` / `enum`,允许可空与默认值

**线程**:Agent 默认在 IO 协程上调度工具。涉及 UI / WMRouter / Activity 启动的代码,
内部 `withContext(Dispatchers.Main)` 切一下,见 `NavigationAi` 示例。

---

## `@AiSkill` 详解(什么时候打组)

工具数量上来后(>10),全部塞给模型会让 prompt 爆表 + 选择质量下降。Agent 用
**progressive disclosure**:默认只暴露 `list_skills` / `load_skill`,模型按用户意图先 load 一组,再调具体工具。

把一组相关工具放进同一个 `object`,加上 `@AiSkill` 注解即可:

```kotlin
@AiSkill(id = "navigation", name = "页面跳转")
object NavigationAi { /* @AiTool ... */ }
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `id` | ✅ | 全局唯一,如 `"navigation"` / `"account_ops"` |
| `name` | ✅ | 给模型看的简短名字 |
| `description` | ❌ | **留空时 KSP 会自动用本 skill 下所有 `@AiTool` 的 description 聚合出一份**,加新工具时只改工具本身即可。需要更精炼概括或额外业务约束时再手写 |

> 没标 `@AiSkill` 的 `object` 里的工具会被注册成「常驻可用」(直接进 baseTools),
> 不走 progressive disclosure。适合极少数高频核心工具。

---

## 业务模块如何接入

业务模块的 `build.gradle` 加两行(参考 `lib_foundation/build.gradle`):

```groovy
plugins {
    id 'com.google.devtools.ksp'   // 顶部:KSP 插件
}

dependencies {
    compileOnly project(':aiagent:lib_ai_annotations')        // 注解
    debugImplementation project(':aiagent:lib_ai_annotations')// debug 包带契约
    kspDebug project(':aiagent:lib_ai_compiler')              // KSP 处理器(只 debug)
}

ksp { arg("aiagent.bootName", project.name) }   // 必填:本模块在生成函数后缀里的唯一标识
```

KSP 会在 `build/generated/ksp/debug/.../AiToolsBoot_<module>.kt` 里生成
`bootAiTools_<module>()` 函数。**接入侧只要把每个模块的 `bootAiTools_<module>` 函数引用
列入 `AiAgentRuntime.install` 的 `kspBootstraps`,装机时 SDK 会统一回调所有 boot 函数
并自动 snapshot tools / skills**,业务方不需要做任何注册动作。

---

## 常见问题

**Q: 写好工具,模型不调用怎么办?**
先看 logcat `AiAgent_*`:
- `list_skills` 返回里有你的 skill 吗?没有 → KSP 没扫到,看 `build/generated/ksp/...` 里有没有生成代码,通常是缺 `kspDebug` 配置或 `bootName` 漏了。
- `load_skill` 加载错了 skill / 没加载 → skill description 没覆盖用户说法的关键词。要么把工具的 `description` 写得更显眼(KSP 会聚合),要么显式手写 skill `description`。
- skill 加载了但工具没调 → 工具 `description` 太模糊,模型不敢选。把触发场景描述更具体。

**Q: `@AiTool` 函数返回 `Unit` 模型怎么知道执行成功?**
KSP 自动包成 `ToolResult.Success("ok")`,模型读到 `"ok"` 就当成功了。需要回传具体数据
(查询类工具)再用 `String` 返回 JSON。

**Q: 高危操作怎么让用户确认?**
`@AiTool(..., requiresConfirmation = true)`。AgentLoop 在 dispatch 前会通过
集成方传入的 `confirmDangerous` 回调暂停等待,UI 弹窗或 headless 策略给出 true / false
之后才会继续(详见 `AgentLoop` 与 `module_ai_agent` 的 `confirmDangerous`)。

**Q: 工具调用报错日志在哪?**
`AiAgent_Loop` tag,关键字 `tool_done ... result=Failure`。集成方接的 logger 实现
决定了具体 tag 名,O2 项目里见 `module_ai_agent/log/TLogAdapter.kt`。

---

## 想了解 SDK 内部

入口顺序推荐:

1. `lib_ai_agent_sdk/src/main/java/com/aiagent/sdk/agent/AgentLoop.kt` — 主循环,看一遍就懂整个调度模型
2. `lib_ai_compiler/src/main/kotlin/com/aiagent/compiler/AiToolProcessor.kt` — KSP 处理流程
3. `lib_ai_agent_sdk/src/main/java/com/aiagent/sdk/skill/` — Skill / progressive disclosure
4. `lib_ai_agent_sdk/src/main/java/com/aiagent/sdk/llm/OpenAiCompatibleClient.kt` — LLM 接入

修改 SDK 公开 API 时建议同步更新本文档与 `module_ai_agent/README.md`。
