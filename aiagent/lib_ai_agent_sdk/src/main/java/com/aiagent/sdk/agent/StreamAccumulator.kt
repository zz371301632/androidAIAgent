package com.aiagent.sdk.agent

import com.aiagent.sdk.llm.LlmStreamEvent
import com.aiagent.sdk.llm.ToolCall

/**
 * 把一条流式响应物化成一轮 assistant turn 的状态机。
 *
 * - 纯累加,不发事件、不写历史 —— 让 [AgentLoop] 自己决定什么时候 emit / persist。
 * - `LlmStreamEvent.Error` 直接抛出,由调用方按异常路径处理(等价于流终止)。
 *
 * 多 tool_call 的 arguments 被切成多个 chunk 下发,这里按 index 拼回完整 JSON。
 */
internal class StreamAccumulator {

    var content: String = ""
        private set

    var reasoning: String = ""
        private set

    var finishReason: String? = null
        private set

    private val partials = sortedMapOf<Int, PartialCall>()

    /** 喂入一个流事件。Error 会被抛出。 */
    fun apply(event: LlmStreamEvent) {
        when (event) {
            is LlmStreamEvent.ContentDelta -> content += event.text
            // 推理过程要累计进 assistant 消息:thinking 模式的供应商要求下一轮
            // 必须把 reasoning_content echo 回去,所以这里得留住;UI 不展示。
            is LlmStreamEvent.ReasoningDelta -> reasoning += event.text
            is LlmStreamEvent.ToolCallDelta -> applyToolDelta(event)
            is LlmStreamEvent.Done -> finishReason = event.finishReason
            is LlmStreamEvent.Error -> throw event.cause
        }
    }

    /** 拼好的 ToolCall 列表(只保留 id 和 name 都齐了的)。 */
    fun toolCalls(): List<ToolCall> = partials.values
        .filter { it.id != null && it.name != null }
        .map { ToolCall(id = it.id!!, name = it.name!!, arguments = it.args.toString()) }

    private fun applyToolDelta(d: LlmStreamEvent.ToolCallDelta) {
        val p = partials.getOrPut(d.index) { PartialCall() }
        if (d.id != null) p.id = d.id
        if (d.name != null) p.name = d.name
        if (d.argsDelta != null) p.args.append(d.argsDelta)
    }

    private class PartialCall {
        var id: String? = null
        var name: String? = null
        val args = StringBuilder()
    }
}
