package com.aiagent.sdk.skill

import com.aiagent.runtime.SkillManifest

/**
 * 把 KSP 从 [com.aiagent.annotations.AiSkill] 生成的 [SkillManifest] 桥接成 [Skill] 接口。
 *
 * 与 [AssetSkill] 的差别:
 *  - 没有 SKILL.md,[loadInstructions] 直接吐 manifest.instructions(为空时回退到 description),
 *    适合用一两段文案就能讲清楚的轻量 skill;
 *  - 需要给模型完整操作手册 / 表格 / 示例的重业务流程,仍然推荐 [AssetSkill]。
 *
 * 由 [com.aiagent.runtime.AiCapabilityRegistry.snapshotSkills] 在 AgentBootstrap 启动时
 * 统一捡走,业务模块零样板。
 */
class CodeSkill(private val manifest: SkillManifest) : Skill {

    override val id: String = manifest.id
    override val name: String = manifest.name
    override val description: String = manifest.description
    override val toolNames: Set<String> = manifest.toolNames

    override suspend fun loadInstructions(): String =
        manifest.instructions ?: manifest.description
}
