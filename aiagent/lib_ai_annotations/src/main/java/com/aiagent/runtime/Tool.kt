package com.aiagent.runtime

import org.json.JSONObject

/**
 * 一个 Tool = 一段 Agent 可调用的本地能力。
 *
 * 该接口故意放在 **lib_ai_annotations** 而非 SDK,这样:
 *  - 业务模块只 `implementation project(':aiagent:lib_ai_annotations')` 就能让 KSP 生成
 *    `class FooTool : Tool { ... }`;
 *  - SDK(`lib_ai_agent_sdk`)同样 import 这个接口,和注解处理器零感知互通。
 *
 * 危险动作(切账号、解人脸…)必须把 [requiresConfirmation] 置为 true,
 * AgentLoop 在 dispatch 前会暂停并等待 UI 弹确认,确认通过后才会真正调用 [execute]。
 */
interface Tool {

    /** 工具名,即模型 tool_calls 里的 name。需要全局唯一。 */
    val name: String

    /**
     * 工具中文名,用于 UI 展示(QuickToolBar 等)。对模型不可见。
     * 空串表示未设置,UI 应回退到 [name]。
     */
    val nameCN: String get() = ""

    /** 给模型看的能力描述。 */
    val description: String

    /** 参数的 JSON Schema 字符串(OpenAI / DeepSeek 兼容格式)。 */
    val parametersJsonSchema: String

    /** 是否在执行前要求用户二次确认。默认否。 */
    val requiresConfirmation: Boolean get() = false

    /**
     * 工具分类标签,用于 UI 分组展示与日志归类(对模型不可见)。
     * 默认空串表示未分类。
     */
    val category: String get() = ""

    /**
     * 执行工具。args 是模型生成的 JSON 对象(已解析,空对象代表无参)。
     *  - 成功 → [ToolResult.Success],content 会被原样回灌给模型作为 tool 消息;
     *  - 失败 → [ToolResult.Failure],模型一般会据此调整后续动作;
     *  - 实现里不要抛异常,实在抛了 ToolRegistry 会兜成 Failure。
     */
    suspend fun execute(args: JSONObject): ToolResult

    /** 转成模型可消费的 schema。 */
    fun toSchema(): ToolSchema = ToolSchema(name, description, parametersJsonSchema)
}

/** 工具一次执行的结果。 */
sealed interface ToolResult {

    /** content 是回传给模型的纯文本,通常是结构化 JSON 或人话摘要。 */
    data class Success(val content: String) : ToolResult

    /** 失败时给模型一个能读懂的解释,通常包含 error code + message。 */
    data class Failure(val message: String) : ToolResult
}
