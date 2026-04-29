# lib_ai_annotations

注解契约 + Runtime 接口。**最薄的一层**,SDK 与 KSP 处理器都依赖它,业务模块也依赖它来声明能力。
业务方上手指引看 [aiagent/README.md](../README.md),本文档面向 SDK 维护者。

## 这层为什么必须独立

- 业务模块 / SDK / KSP 处理器三方都需要 `Tool` 这套接口与 `AiCapabilityRegistry` 单例,
  但它们各自又不能互相依赖(KSP 处理器是 JVM 工具,SDK 是 Android 库)。
- 抽出一个**纯 JVM 极薄模块**作为公共契约,所有人 `compileOnly` 它就行。

## 文件地图

```
com/aiagent/
├── annotations/          ← 编译期注解(SOURCE retention,KSP 吃掉后不进产物)
│   ├── AiTool.kt         @AiTool  函数级,声明可被 Agent 调用
│   ├── AiSkill.kt        @AiSkill object 级,声明 progressive-disclosure 分组
│   └── AiParam.kt        @AiParam 参数级,补充参数描述给模型
└── runtime/              ← 运行时契约(永久存活,SDK / 业务 / KSP 生成代码都引)
    ├── Tool.kt                 Tool / ToolResult 接口与密封类
    ├── ToolSchema.kt           工具元数据(name + description + jsonSchema)给 LLM 用
    └── AiCapabilityRegistry.kt 进程级单例,KSP 生成的 init 块往这里注册
```

## 设计约束(动这里前先想清楚)

| 约束 | 原因 |
|---|---|
| **不引入 Android、OkHttp、协程、Compose** | 必须能被 KSP 处理器(纯 JVM)依赖。引一个 Android 类会污染 KSP runtime classpath |
| **JVM target 17** | 与 KSP / Kotlin 编译器对齐,见 build.gradle 强制 jvmTarget |
| **`org.json.JSONObject` 用 `compileOnly`** | Android 自带,JVM 单测里走 mavenCentral。运行时永远有,不需要打包 |
| **`Tool.execute` 返回 `ToolResult` 而非 `String`** | 让 SDK 区分 Success / Failure 而不靠魔法字符串。改这个签名是 API 大破坏 |
| **`AiCapabilityRegistry` 必须线程安全** | 多业务模块的 `bootAiTools_<module>()` 可能在不同初始化阶段被调,内部用 `ConcurrentHashMap` 保护 |
| **注解参数类型严格收敛** | `AiTool.description` 等必须是编译期常量字符串,KSP 才能在 `Default` 拿到值 |

## 何时需要改这里

- 给 `@AiTool` / `@AiSkill` 加新字段 → KSP `Model.kt` 要同步加 parser,`SourceWriter.kt` 要同步 emit。**契约变了三方都要动**,慎重
- 给 `Tool` 接口加方法 → SDK + KSP `ExecuteBodyWriter` + 所有现存生成代码都要重生成。属于破坏性 API 变更
- `ToolResult` 加新子类 → AgentLoop 的 `when` 分支要补,否则编译期不报但运行时漏处理

## 测试

```
./gradlew :aiagent:lib_ai_annotations:test
```

当前是契约层无逻辑,测试主要在 `lib_ai_agent_sdk` 与 `lib_ai_compiler` 里覆盖。
本模块加新工具方法时,有逻辑就在这里加单测。

## 依赖关系

```
        ┌─────────────────────────┐
        │   lib_ai_annotations    │ ← 本模块,无运行时依赖
        └────┬────────────────┬───┘
             │ compileOnly    │ api
             ▼                ▼
   ┌─────────────────┐  ┌─────────────────┐
   │ lib_ai_compiler │  │ lib_ai_agent_sdk│
   │   (KSP)         │  │   (Android)     │
   └─────────────────┘  └─────────────────┘
             ▲                ▲
             │ kspDebug       │ implementation
             │                │
   ┌────────────────────────────────┐
   │ 业务模块 (lib_foundation 等)    │
   │  + compileOnly 本模块           │
   └────────────────────────────────┘
```
