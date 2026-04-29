package com.aiagent.sdk.agent

import com.aiagent.runtime.ToolResult
import com.aiagent.sdk.llm.ToolCall

/**
 * AgentLoop 在「思考-调用工具-观察」循环中向外吐出的事件。
 *
 * 事件粒度足以驱动 UI 渲染:
 *  - AssistantDelta:打字机式文本片段
 *  - AssistantFinal:这一轮 assistant 文本最终拼接结果(可能为空)
 *  - ToolCallStarted/Completed:工具卡片状态切换
 *  - ConfirmationDenied:用户拒绝危险工具,链路终止该工具
 *  - LoopFinished:整个循环结束(stop / max_rounds / error)
 */
sealed interface AgentEvent {

    /** 一段流式文本增量。 */
    data class AssistantDelta(val text: String) : AgentEvent

    /** 一轮 assistant 消息收尾(无论是否走工具)。 */
    data class AssistantFinal(val text: String, val toolCalls: List<ToolCall>) : AgentEvent

    /** 工具开始执行(已经过用户确认,如有需要)。 */
    data class ToolCallStarted(val call: ToolCall) : AgentEvent

    /** 工具执行结束。 */
    data class ToolCallCompleted(val call: ToolCall, val result: ToolResult) : AgentEvent

    /** 用户拒绝了某个高危工具的执行,该工具被跳过并以 Failure 形式回灌给模型。 */
    data class ConfirmationDenied(val call: ToolCall) : AgentEvent

    /** 整个循环结束。 */
    data class LoopFinished(val reason: FinishReason) : AgentEvent

    /** 框架级异常(网络 / 解析 / 取消)。 */
    data class LoopError(val cause: Throwable) : AgentEvent
}

/** AgentLoop 终止原因。 */
enum class FinishReason {
    /** 模型自己说话说完了(finish_reason=stop)。 */
    Stop,

    /** 触达 maxRounds。 */
    MaxRoundsReached,

    /** 模型返回了未识别的 finish_reason。 */
    Unknown,
}
