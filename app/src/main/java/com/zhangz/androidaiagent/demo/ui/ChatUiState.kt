package com.zhangz.androidaiagent.demo.ui

import com.aiagent.runtime.Tool
import org.json.JSONObject

/** UI 层独立的会话视图模型,与 SDK 的 Message 解耦,只装 UI 关心的字段。 */
sealed interface ChatBubble {
    val id: Long

    data class User(override val id: Long, val text: String) : ChatBubble
    data class Assistant(override val id: Long, val text: String) : ChatBubble
    data class ToolCallView(
        override val id: Long,
        val name: String,
        val argsPreview: String,
        val state: ToolUiState,
        val output: String? = null,
    ) : ChatBubble

    /**
     * Sub-Agent 委派气泡。承载父调用的一次 `call_sub_agent`,内部 [inner] 递归渲染
     * 子 Agent 自己的 Assistant / Tool / 再嵌套 SubAgent 气泡。[callId] 用于把
     * [com.aiagent.sdk.agent.AgentEvent.SubAgentInnerEvent] 路由到对应节点。
     */
    data class SubAgentBubble(
        override val id: Long,
        val callId: String,
        val agentType: String,
        val task: String,
        val depth: Int,
        val state: ToolUiState,
        val finalText: String? = null,
        val inner: List<ChatBubble> = emptyList(),
    ) : ChatBubble
}

enum class ToolUiState { Pending, Running, Success, Failure, Denied }

/** 待用户确认的危险工具调用。 */
data class PendingConfirmation(
    val tool: Tool,
    val args: JSONObject,
)

/** UI 顶层状态。 */
data class ChatUiState(
    /** 当前会话已加载 skill 的 id,只用于头部只读展示。 */
    val loadedSkillIds: List<String> = emptyList(),
    val bubbles: List<ChatBubble> = emptyList(),
    val streamingText: String = "",
    val isRunning: Boolean = false,
    val pending: PendingConfirmation? = null,
    val error: String? = null,
    val configured: Boolean = true,
)
