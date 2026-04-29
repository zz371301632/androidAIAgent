package com.aiagent.sdk.skill

/**
 * Skill 注册表。保留插入顺序,UI 上的 skill 选择列表按注册顺序展示。
 * 与 ToolRegistry 一样,重名注册直接抛异常防止静默覆盖。
 */
class SkillRegistry {

    private val skills = linkedMapOf<String, Skill>()

    fun register(skill: Skill) {
        check(!skills.containsKey(skill.id)) { "Skill already registered: ${skill.id}" }
        skills[skill.id] = skill
    }

    fun registerAll(items: Iterable<Skill>) {
        items.forEach(::register)
    }

    fun get(id: String): Skill? = skills[id]

    fun all(): List<Skill> = skills.values.toList()
}
