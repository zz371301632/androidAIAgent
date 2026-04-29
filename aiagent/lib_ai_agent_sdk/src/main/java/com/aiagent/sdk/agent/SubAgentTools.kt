package com.aiagent.sdk.agent

import com.aiagent.runtime.ToolSchema

/**
 * Sub-Agent 调度工具:`call_sub_agent`。
 *
 * 与 [com.aiagent.sdk.skill.SkillCallTools] 同构,但承载的 schema 必须按当前
 * [SubAgentRegistry] 现拼:description 里要枚举可用的预设 id 让模型知道能委派给谁,
 * 因此本对象只暴露工厂方法而非常量。
 *
 * 该工具不会被注册到 [com.aiagent.sdk.tool.ToolRegistry];
 * [AgentLoop] 在派发前由 [SubAgentInvoker] 直接拦截,递归启动一个子 [AgentLoop]
 * 跑完委派任务并把最终文本回灌给父循环。
 */
object SubAgentTools {

    const val NAME_CALL = "call_sub_agent"

    fun schemasFor(registry: SubAgentRegistry): List<ToolSchema> {
        if (registry.isEmpty()) return emptyList()
        val presets = registry.all()
        val ids = presets.joinToString("、") { "`${it.id}`" }
        val catalog = presets.joinToString("\n") { "- `${it.id}`(${it.displayName}):${it.description}" }
        val description = buildString {
            append("把一个独立子任务委派给具备特定人设的子 Agent 执行。")
            append("子 Agent 拥有自己的会话历史、工具白名单与角色设定,")
            append("跑完后只把最终文本结果返回给你,父会话只看到结果不看到中间过程。")
            append("可用子 Agent:").append(ids).append("。\n").append(catalog)
            append("\n何时使用:① 任务跨多步且需要不同人设;② 想隔离子任务的 token 上下文;")
            append("③ 委派给只擅长某类输出(如「研究员」「程序员」)的角色。")
        }
        val parameters = """
            {
              "type": "object",
              "properties": {
                "agent_type": {"type": "string", "description": "子 Agent 预设 id,从可用列表中选一个"},
                "task": {"type": "string", "description": "用一段自然语言描述要让子 Agent 完成的子任务,信息要足够自包含,因为子 Agent 看不到父会话历史"}
              },
              "required": ["agent_type", "task"],
              "additionalProperties": false
            }
        """.trimIndent()
        return listOf(
            ToolSchema(
                name = NAME_CALL,
                description = description,
                parametersJson = parameters,
            )
        )
    }
}
