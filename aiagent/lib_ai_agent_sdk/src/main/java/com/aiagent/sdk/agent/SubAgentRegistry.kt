package com.aiagent.sdk.agent

/**
 * Sub-Agent 注册表。保留插入顺序,UI / 模型描述按注册顺序展示。
 * 与 [com.aiagent.sdk.tool.ToolRegistry] 一样,重名注册抛 IllegalStateException
 * 防止静默覆盖。
 *
 * 默认值 [EMPTY] 用于业务方未启用 Sub-Agent 能力的情况:此时
 * [com.aiagent.sdk.agent.AgentLoop] 不会向 LLM 暴露 `call_sub_agent` 工具,
 * 行为完全与历史版本一致。
 */
class SubAgentRegistry {

    private val presets = linkedMapOf<String, SubAgentPreset>()

    fun register(preset: SubAgentPreset) {
        check(!presets.containsKey(preset.id)) { "SubAgent already registered: ${preset.id}" }
        presets[preset.id] = preset
    }

    fun registerAll(items: Iterable<SubAgentPreset>) {
        items.forEach(::register)
    }

    fun get(id: String): SubAgentPreset? = presets[id]

    fun all(): List<SubAgentPreset> = presets.values.toList()

    fun isEmpty(): Boolean = presets.isEmpty()

    companion object {
        val EMPTY: SubAgentRegistry = SubAgentRegistry()
    }
}
