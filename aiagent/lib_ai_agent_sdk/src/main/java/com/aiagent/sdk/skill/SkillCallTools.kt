package com.aiagent.sdk.skill

import com.aiagent.runtime.ToolSchema

/**
 * Skill 调度工具:list_skills / load_skill。
 *
 * 这两个工具不会被注册到 [com.aiagent.sdk.tool.ToolRegistry];
 * AgentLoop 在派发前直接拦截,改写 [com.aiagent.sdk.agent.AgentSession]
 * 的 loadedSkillIds 状态,然后把执行结果以 tool 消息回给模型。
 *
 * 之所以做成 ToolSchema 而不是普通工具,是因为这是 OpenAI function calling
 * 协议里模型唯一能感知到的「我可以做什么」。
 */
object SkillCallTools {

    const val NAME_LIST = "list_skills"
    const val NAME_LOAD = "load_skill"

    val LIST: ToolSchema = ToolSchema(
        name = NAME_LIST,
        description = "列出当前可用但尚未加载的全部 skill 简介。返回 JSON 数组,每项含 id/name/description/loaded。" +
            "当用户的请求超出当前已加载 skill 的能力时,先调用本工具确认是否有合适的 skill。",
        parametersJson = """{"type":"object","properties":{},"additionalProperties":false}""",
    )

    val LOAD: ToolSchema = ToolSchema(
        name = NAME_LOAD,
        description = "加载指定 skill 的完整说明与工具集。下一轮起,系统提示中会出现该 skill 的操作手册," +
            "且其工具会出现在可用工具列表中。重复加载同一个 skill 是空操作。",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "id": {"type": "string", "description": "skill id,即 list_skills 返回的 id 字段"}
              },
              "required": ["id"],
              "additionalProperties": false
            }
        """.trimIndent(),
    )

    val ALL: List<ToolSchema> = listOf(LIST, LOAD)
}
