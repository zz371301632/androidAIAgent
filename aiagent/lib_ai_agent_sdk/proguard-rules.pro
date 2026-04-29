# =============================================================================
# 仅当 lib_ai_agent_sdk 自身被 minify 时使用(当前 release 下 minifyEnabled=false,
# 实际不会触发)。规则与 consumer-rules.pro 等价,确保 SDK 自测打包稳定。
# =============================================================================
-keep class com.aiagent.runtime.** { *; }
-keep class com.aiagent.generated.** { *; }
-keep public class com.aiagent.sdk.** { public *; }
-keep class com.aiagent.sdk.llm.Message$* { *; }
-keep class com.aiagent.sdk.llm.LlmStreamEvent$* { *; }
-keep class com.aiagent.sdk.agent.AgentEvent$* { *; }
