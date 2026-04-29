package com.aiagent.sdk.llm

import com.aiagent.runtime.ToolSchema
import kotlinx.coroutines.flow.Flow

/**
 * LLM 客户端抽象。当前只暴露「流式聊天补全」一个接口,
 * 未来要换 OpenAI / 通义千问 / 本地模型,只需新增一个实现即可。
 */
interface LlmClient {

    /**
     * 发起一次流式 chat completion 请求。
     *
     * @param messages 历史对话(按时间顺序)。
     * @param tools 可用工具列表,传 null 表示纯聊天。
     * @param toolChoice 工具选择策略,常见值 "auto" / "none" / 指定函数名。
     */
    fun chatStream(
        messages: List<Message>,
        tools: List<ToolSchema>? = null,
        toolChoice: String = "auto",
    ): Flow<LlmStreamEvent>
}

/**
 * 流式响应中的单个事件。一次完整回答会按以下顺序混合下发:
 *   (ReasoningDelta|ContentDelta)*  +  ToolCallDelta*  +  Done
 *
 * - 文本回答:N 个 ContentDelta 后接 Done(finishReason="stop")
 * - 工具调用:M 个 ToolCallDelta 后接 Done(finishReason="tool_calls")
 *   注意 arguments 会被切成多个 chunk 下发,调用方需要按 index 拼接。
 */
sealed interface LlmStreamEvent {

    /** 一段普通文本增量。 */
    data class ContentDelta(val text: String) : LlmStreamEvent

    /**
     * 一段「推理过程」(CoT)增量。来源:
     *  - DeepSeek 等推理模型在响应里的独立字段 `reasoning_content`;
     *  - 部分供应商即便关了 thinking 仍把 `<think>...</think>` 混进 content,
     *    本接口在 client 层会把这段拆出来作为 ReasoningDelta 单独下发。
     *
     * 调用方可仅做日志/调试展示,不应把它写回历史(供应商不允许 echo 回去)。
     */
    data class ReasoningDelta(val text: String) : LlmStreamEvent

    /**
     * 一段工具调用增量。
     *
     * @param index   工具在 tool_calls 数组中的下标,多工具并行时区分用。
     * @param id      工具调用 id(仅首个增量带)。
     * @param name    函数名(通常仅首个增量带)。
     * @param argsDelta arguments 字符串增量(拼接所有 delta 才是完整 JSON)。
     */
    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val argsDelta: String?,
    ) : LlmStreamEvent

    /**
     * 流结束。finishReason 由服务端给出:
     *  - "stop"          模型自己决定结束
     *  - "tool_calls"    模型要求调用工具
     *  - "length"        达到 max_tokens
     *  - 其它值原样透传
     */
    data class Done(val finishReason: String?) : LlmStreamEvent

    /** 流过程中的异常(HTTP / 解析 / 取消)。一旦触发即视为流结束。 */
    data class Error(val cause: Throwable) : LlmStreamEvent
}
