package com.zhangz.androidaiagent.demo.headless

/**
 * Headless 任务的执行策略。
 *
 * 由 [com.zhangz.androidaiagent.demo.ui.HeadlessAgentActivity] 从 Intent extras 解析得到,
 * 然后传给 [com.zhangz.androidaiagent.demo.bootstrap.AgentBootstrap.runHeadless]。
 *
 * 默认策略偏「安全」:危险工具一律拒绝,不预加载 skill,模型自己用 list_skills /
 * load_skill 探索。需要更激进的行为时由命令行 extras 显式开启。
 */
data class HeadlessPolicy(
    /**
     * 是否放行 `requiresConfirmation = true` 的工具。
     *
     * - false(默认):confirmDangerous 一律返回 false,危险工具被跳过并以 Failure 形式
     *   回灌给模型,后续逻辑走 `ConfirmationDenied` 事件;
     * - true:确认回调直接返回 true,适用于明确已知后果的自动化场景。
     */
    val allowDangerous: Boolean = false,

    /**
     * 启动前预加载的 skill id 列表(逗号分隔串解析后)。
     *
     * 不填(空集合)时模型自己探索;填了就直接 [com.aiagent.sdk.agent.AgentSession.loadSkill],
     * 节省至少 1 轮 list_skills/load_skill 往返。仅作加速,不影响最终结果。
     */
    val preloadSkillIds: List<String> = emptyList(),
) {
    companion object {
        /** Intent extra key:任务文本。 */
        const val EXTRA_TASK = "task"

        /** Intent extra key:Boolean,放行危险工具。 */
        const val EXTRA_ALLOW_DANGEROUS = "allowDangerous"

        /** Intent extra key:String,逗号分隔的 skill id 列表。 */
        const val EXTRA_LOAD_SKILLS = "loadSkills"

        fun parseSkillIds(raw: String?): List<String> =
            raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    }
}
