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

    // ── Sub-Agent 委派事件 ─────────────────────────────────────────────────────
    // 父会话调用 call_sub_agent 时,SDK 会现拉一个子 AgentLoop。子循环里产生的
    // 所有 AgentEvent 会被 [com.aiagent.sdk.agent.SubAgentInvoker] 包成
    // [SubAgentInnerEvent] 转发给父 flow,UI 据此可做嵌套渲染。
    //
    // 三件套:Started → Inner* → Finished;callId 与父循环里的 ToolCall.id 同步,
    // 便于 UI 把这一组事件挂到对应的 call_sub_agent 工具卡片上。

    /** 子 Agent 委派开始:从父视角看,已经决定要派,但还没真跑第一轮 LLM。 */
    data class SubAgentStarted(
        val callId: String,
        val agentType: String,
        val task: String,
        val depth: Int,
    ) : AgentEvent

    /** 子循环里一条原始事件。父 UI 应递归走相同的渲染逻辑。 */
    data class SubAgentInnerEvent(
        val callId: String,
        val depth: Int,
        val inner: AgentEvent,
    ) : AgentEvent

    /** 子 Agent 委派结束:[finalText] 是即将以 tool 消息形式回灌给父模型的最终文本。 */
    data class SubAgentFinished(
        val callId: String,
        val agentType: String,
        val finalText: String,
        val reason: FinishReason,
    ) : AgentEvent
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
