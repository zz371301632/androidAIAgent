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
