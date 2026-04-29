package com.aiagent.sdk.agent

/**
 * Sub-Agent 预设。父 Agent 通过 `call_sub_agent` 工具按 [id] 选择,SDK 会用本配置
 * 现拉一个独立的 [AgentSession] + [AgentLoop] 跑完委派任务并把最终文本回灌给父。
 *
 * 关键字段:
 *  - [persona] 子 Agent 的 system 提示,与父完全独立(不继承父的角色设定);
 *  - [baseToolNames] 子 Agent 默认可见的工具白名单;不在 [com.aiagent.sdk.skill.SkillRegistry]
 *    已加载的 skill 之内,等价于 [AgentSession.baseToolNames] 的语义;
 *  - [allowedSubAgentIds] 限定本 preset 还能再调哪些子 Agent;空集 = 不可再嵌套;
 *  - [maxRounds] 子循环上限,独立于父的 maxRounds。
 *
 * 安全约束(由 [SubAgentInvoker] 强制,不可在此 opt-in):
 *  - 子 Agent 的 confirmDangerous 永远 deny-all,父若放行 dangerous 工具,子也不能调;
 *  - 递归深度由 [AgentSession.depth] 控制,最大 [SubAgentInvoker.MAX_DEPTH]。
 */
data class SubAgentPreset(
    val id: String,
    val displayName: String,
    val description: String,
    val persona: String,
    val baseToolNames: Set<String> = emptySet(),
    val allowedSubAgentIds: Set<String> = emptySet(),
    val maxRounds: Int = AgentSession.DEFAULT_MAX_ROUNDS,
)
