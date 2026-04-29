package com.aiagent.sdk.agent

import com.aiagent.sdk.llm.Message
import com.aiagent.sdk.skill.SkillRegistry

/**
 * 一次会话的可变状态:对话历史 + 已加载 skill 集合。
 *
 * Claude 风格的 skill:默认每轮只把 list_skills / load_skill 暴露给模型,
 * skill 的完整说明 / 工具白名单只有在被 load_skill 显式加载后才进 context。
 *
 * - history     仅存 user / assistant / tool,不存 system,system 每轮重建。
 * - loadedSkillIds  按加载顺序保留,影响 system 拼装顺序与 [activeToolNames]。
 * - snapshot()  生成「最新 system + 历史」的不可变列表给 LlmClient。
 *
 * `basePersona` 由集成方提供,SDK 不内置任何业务向 persona 文案;若集成方想要一份
 * 通用 ReAct 风格的回退,见 [com.aiagent.sdk.agent.AgentPromptDefaults]。
 */
class AgentSession(
    val skillRegistry: SkillRegistry,
    val basePersona: String,
    val baseToolNames: Set<String> = emptySet(),
    val maxRounds: Int = DEFAULT_MAX_ROUNDS,
) {

    private val history = mutableListOf<Message>()
    private val _loadedSkillIds = linkedSetOf<String>()

    /** 已加载 skill id,按加载顺序。 */
    val loadedSkillIds: Set<String> get() = _loadedSkillIds.toSet()

    fun appendUser(text: String) {
        history.add(Message.User(text))
    }

    fun appendAssistant(message: Message.Assistant) {
        history.add(message)
    }

    fun appendTool(message: Message.Tool) {
        history.add(message)
    }

    /**
     * 标记一个 skill 为已加载。返回:
     *  - true  新加载成功
     *  - false skill 不存在,或之前已加载
     */
    fun loadSkill(id: String): Boolean {
        val sk = skillRegistry.get(id) ?: return false
        return _loadedSkillIds.add(sk.id)
    }

    /** 当前可派发的工具集合:基础工具 ∪ 所有已加载 skill 的工具白名单。 */
    fun activeToolNames(): Set<String> {
        val out = linkedSetOf<String>()
        out.addAll(baseToolNames)
        for (id in _loadedSkillIds) {
            skillRegistry.get(id)?.toolNames?.let(out::addAll)
        }
        return out
    }

    /** 生成本轮要发给模型的完整消息列表(system 每轮重建)。 */
    suspend fun snapshot(): List<Message> {
        val sys = buildSystemPrompt()
        return listOf(Message.System(sys)) + history
    }

    private suspend fun buildSystemPrompt(): String = buildString {
        append(basePersona.trim())
        val all = skillRegistry.all()
        append("\n\n## 可用 Skill\n")
        if (all.isEmpty()) {
            append("(无)\n")
        } else {
            all.forEach { sk ->
                val mark = if (sk.id in _loadedSkillIds) "[已加载]" else "[未加载]"
                append("- $mark `${sk.id}` — ${sk.name}:${sk.description}\n")
            }
        }
        append(
            "\n当用户的请求超出已加载 skill 能力时,先用 list_skills 看清可选能力," +
                "再用 load_skill 加载对应 skill,然后才能调用其工具。"
        )
        if (_loadedSkillIds.isNotEmpty()) {
            append("\n\n## 已加载 Skill 操作手册")
            for (id in _loadedSkillIds) {
                val sk = skillRegistry.get(id) ?: continue
                append("\n\n### ${sk.name} (`${sk.id}`)\n")
                append(sk.loadInstructions())
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_ROUNDS = 8
    }
}
