# lib_ai_compiler

KSP 处理器。**编译期跑,不进产物**。扫业务模块里的 `@AiTool` / `@AiSkill`,生成
`AiToolsBoot_<module>.kt`,自动把工具与 skill 注册到 `AiCapabilityRegistry`。
业务方上手指引看 [aiagent/README.md](../README.md),本文档面向处理器维护者。

## 调用方式

业务模块 `build.gradle` 里:

```groovy
dependencies {
    kspDebug project(':aiagent:lib_ai_compiler')
}
ksp { arg("aiagent.bootName", project.name) }   // 必填,见下
```

KSP SPI 入口:`META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider`
内一行 `com.aiagent.compiler.AiToolProcessorProvider`。

## 文件地图

```
com/aiagent/compiler/
├── AiToolProcessorProvider.kt   SPI 入口,实例化 AiToolProcessor
├── AiToolProcessor.kt           主流程:scan → parse → emit;处理 bootName / skill 描述聚合
├── Model.kt                     ToolModel / SkillModel / ParamModel 等中间数据结构
├── SchemaWriter.kt              参数 → JSON Schema(string/integer/boolean/enum...)
├── ExecuteBodyWriter.kt         生成 override suspend fun execute(args):
│                                  - Unit  → ToolResult.Success("ok")
│                                  - String→ ToolResult.Success(returnValue)
│                                  - 其它  → 编译期报错
├── SourceWriter.kt              拼装最终 .kt 文件:Tool 实现类 + bootAiTools_<module>()
└── Util.kt                      工具方法(snake_case 化、quote escape...)
```

## 处理流水线

```
KSP round 1
  ├─ AiToolProcessor.process()
  │    ├─ resolver.getSymbolsWithAnnotation("com.aiagent.annotations.AiTool")
  │    │    → 校验签名(必须 suspend / object 成员或顶层 / 返回 Unit|String / 参数白名单)
  │    │    → parseTool() 产 ToolModel
  │    ├─ resolver.getSymbolsWithAnnotation("com.aiagent.annotations.AiSkill")
  │    │    → parseSkill() 产 SkillModel,description.ifBlank → synthesizeSkillDescription()
  │    └─ SourceWriter.emit(tools, skills, bootName)
  │           为每个 ToolModel 生成 internal class XxxAiTool : Tool { ... }
  │           生成顶层 fun bootAiTools_<bootName>() { register(...); registerSkill(...) }
  └─ done
```

## bootName 为什么必须每个业务模块唯一

每个业务模块的 KSP 都会生成一份 `AiToolsBoot_<bootName>.kt`,顶层函数名是
`bootAiTools_<bootName>()`。`<bootName>` 由 `ksp { arg("aiagent.bootName", ...) }` 注入,
**漏配会用 fallback 名,两个模块同名时函数同名 → 链接期 duplicate symbol 报错**。

约定:`arg("aiagent.bootName", project.name)`,Gradle 的 `project.name` 已天然唯一。

## skill 描述自动聚合

`@AiSkill.description` 留空时,`AiToolProcessor.synthesizeSkillDescription()` 会拼:

```
"<skillName>。包含工具:<tool1.description>; <tool2.description>; ..."
```

业务方加新工具时**只需写 `@AiTool.description`**,skill 描述自动同步。
显式写了 `description` 字段时则原样使用,留给「需要精炼概括或额外业务约束」的场景。

## 修改注意

| 改动 | 影响 |
|---|---|
| 加新注解字段 | 同步改 `lib_ai_annotations` 注解类 + `Model.kt` parser + `SourceWriter.kt` emit |
| 放宽返回类型(如支持 `Map`) | 改 `ExecuteBodyWriter.kt` + 顶层 README + `lib_ai_annotations/AiTool.kt` 的 kdoc 约束说明 |
| 改 generated 包名 | 业务侧 `AgentBootstrap` 里调 `bootAiTools_*` 的 import 全部失效 |
| 改 SchemaWriter 输出格式 | 模型看到的 JSON Schema 变形,可能影响调用准确率,跑一轮真机验证 |

## 调试技巧

生成产物落在:
```
<业务模块>/build/generated/ksp/debug/kotlin/com/aiagent/generated/AiToolsBoot_<name>.kt
```

KSP 失败时控制台会打:
```
lib_ai_compiler[<bootName>]: generated N tools, M skills
```
没看到这行说明 KSP 根本没跑(检查 `kspDebug` 配置)。

KSP 报「unresolved reference」一般是注解类没在 classpath,业务模块要至少 `compileOnly project(':aiagent:lib_ai_annotations')`。

## 测试

```
./gradlew :aiagent:lib_ai_compiler:test
```

测试主要走「KSP fixture → 检查生成代码字符串」路线;复杂功能(如描述聚合)
也可以在 `module_ai_agent` build 后直接 cat 生成文件验证。
