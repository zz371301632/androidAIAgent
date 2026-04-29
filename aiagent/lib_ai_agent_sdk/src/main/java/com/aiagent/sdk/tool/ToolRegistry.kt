package com.aiagent.sdk.tool

import com.aiagent.runtime.Tool
import com.aiagent.runtime.ToolResult
import com.aiagent.runtime.ToolSchema
import org.json.JSONObject

/**
 * 工具注册表。AgentLoop 通过它做两件事:
 *  1. listSchemas(...) 拿到要发给模型的 tools 数组
 *  2. dispatch(name, argsJson) 真正执行模型选中的工具
 *
 * 注册表保留插入顺序,便于在 UI / 日志中按可预期顺序展示。
 * 同一 name 二次注册会抛 IllegalStateException(防止 Skill 之间静默覆盖)。
 *
 * Tool / ToolResult / ToolSchema 均来自 lib_ai_annotations(`com.aiagent.runtime`),
 * 业务方与 KSP 生成代码共享同一份契约。
 */
class ToolRegistry {

    private val tools = linkedMapOf<String, Tool>()

    fun register(tool: Tool) {
        check(!tools.containsKey(tool.name)) { "Tool already registered: ${tool.name}" }
        tools[tool.name] = tool
    }

    fun registerAll(tools: Iterable<Tool>) {
        tools.forEach(::register)
    }

    fun unregister(name: String): Tool? = tools.remove(name)

    fun get(name: String): Tool? = tools[name]

    fun all(): List<Tool> = tools.values.toList()

    /**
     * 导出 schema 列表。filter 给定时只返回 name 在集合内的工具,
     * 用于实现 Skill = 工具白名单的语义。
     */
    fun listSchemas(filter: Set<String>? = null): List<ToolSchema> {
        val source = if (filter == null) tools.values else tools.values.filter { it.name in filter }
        return source.map { it.toSchema() }
    }

    /**
     * 调用一个工具。argsJson 是模型给的 arguments 字符串(可能为空)。
     * - 工具不存在 -> Failure("unknown_tool: ...")
     * - argsJson 非合法 JSON -> Failure("bad_arguments: ...")
     * - execute 抛异常 -> Failure("exception: ...") 并保留消息
     */
    suspend fun dispatch(name: String, argsJson: String?): ToolResult {
        val tool = tools[name] ?: return ToolResult.Failure("unknown_tool: $name")
        val args = parseArgs(argsJson) ?: return ToolResult.Failure("bad_arguments: $argsJson")
        return runCatching { tool.execute(args) }
            .getOrElse { t -> ToolResult.Failure("exception: ${t.javaClass.simpleName}: ${t.message ?: ""}") }
    }

    private fun parseArgs(argsJson: String?): JSONObject? {
        if (argsJson.isNullOrBlank()) return JSONObject()
        return runCatching { JSONObject(argsJson) }.getOrNull()
    }
}
