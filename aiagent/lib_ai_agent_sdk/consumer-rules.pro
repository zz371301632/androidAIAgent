# =============================================================================
# Consumer ProGuard rules for lib_ai_agent_sdk
# 当业务方启用 R8/minify 时,以下规则会被自动追加到业务方的 ProGuard 配置。
# =============================================================================

# -----------------------------------------------------------------------------
# 1. 注解契约层(lib_ai_annotations)
# Tool / ToolResult / ToolSchema / SkillManifest / AiCapabilityRegistry 是
# 业务代码、KSP 生成代码、SDK 三方共享的接口/数据类,接口方法签名不能被混淆,
# 否则 KSP 生成的 Tool 实现 override 与 SDK 内部 dispatch 的 vtable 会对不上。
# -----------------------------------------------------------------------------
-keep class com.aiagent.runtime.** { *; }
-keepclassmembers class com.aiagent.runtime.** { *; }

# -----------------------------------------------------------------------------
# 2. KSP 生成的工具实现 + boot 函数
# AiToolsBoot_<module>.kt 由 lib_ai_compiler 在每个业务模块下生成,
# 内含 Tool 实现类(GetCurrentUserAiTool 等)与顶层 boot 函数。
# AgentBootstrap 通过 import FQN 直接调用 boot 函数,符号需保留。
# -----------------------------------------------------------------------------
-keep class com.aiagent.generated.** { *; }
-keepclassmembers class com.aiagent.generated.** { *; }

# -----------------------------------------------------------------------------
# 3. SDK 公共 API 表面
# 集成方在 AgentBootstrap / AgentChatViewModel / TLogAdapter 中直接引用以下
# 包内的类。保留 public 成员,private/internal 仍可被 R8 优化。
# -----------------------------------------------------------------------------
-keep public class com.aiagent.sdk.agent.** { public *; }
-keep public class com.aiagent.sdk.llm.** { public *; }
-keep public class com.aiagent.sdk.skill.** { public *; }
-keep public class com.aiagent.sdk.tool.** { public *; }
-keep public class com.aiagent.sdk.log.** { public *; }

# Sealed hierarchy(Message / AgentEvent / LlmStreamEvent / ToolResult)的子类
# 通过 `when` 分支被消费,R8 fullMode 下接口擦除需要保留以避免 ICCE。
-keep class com.aiagent.sdk.llm.Message$* { *; }
-keep class com.aiagent.sdk.llm.LlmStreamEvent$* { *; }
-keep class com.aiagent.sdk.agent.AgentEvent$* { *; }

# -----------------------------------------------------------------------------
# 4. 上游依赖
# OkHttp / kotlinx.coroutines 自带 consumer-rules,这里不重复声明;
# org.json 是 Android 平台类,不会被 R8 处理。
# -----------------------------------------------------------------------------
