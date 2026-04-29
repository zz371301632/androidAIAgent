package com.aiagent.sdk.skill

/**
 * 一个 Skill 是「Claude 风格」的可挂载工作包:**默认不在上下文里**,
 * 模型通过 list_skills 看到所有 skill 的简介,需要时主动 load_skill(id) 把
 * 完整说明 + 工具白名单加载进当前会话。设计参考:
 *   https://www.anthropic.com/news/skills
 *
 * 三段渐进披露(progressive disclosure):
 *   1. id / name / description       ── 常驻 system 提示,token 成本最低
 *   2. loadInstructions()            ── 被加载时才读,通常是 SKILL.md 的正文,
 *                                       可以很长,只在用到时进入 context
 *   3. toolNames                     ── 加载后这一组工具的 schema 才会发给模型
 *
 * 实现层不要持有运行态;loaded 状态由 [com.aiagent.sdk.agent.AgentSession] 统一管理。
 */
interface Skill {

    /** 唯一 id。也可作为 assets/skills/<id>/SKILL.md 的目录名。 */
    val id: String

    /** 给模型看的简短名字,比如 "账号运维"。 */
    val name: String

    /** 一两句话说清楚 skill 的能力边界,常驻 system 提示供模型路由判断。 */
    val description: String

    /**
     * 加载完整操作指引。返回值会被回灌到 system 提示并保留至会话结束。
     * 通常实现是读 SKILL.md 正文;首次调用后建议自缓存,避免重复 IO。
     */
    suspend fun loadInstructions(): String

    /** 加载后解锁的工具名集合(必须已注册到全局 ToolRegistry)。 */
    val toolNames: Set<String>
}
