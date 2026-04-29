package com.zhangz.androidaiagent.demo.bootstrap

import android.content.Context
import com.aiagent.generated.bootAiTools_app
import com.aiagent.runtime.AiCapabilityRegistry
import com.aiagent.runtime.Tool
import com.aiagent.sdk.agent.AgentEvent
import com.aiagent.sdk.agent.AgentLoop
import com.aiagent.sdk.agent.AgentPromptDefaults
import com.aiagent.sdk.agent.AgentSession
import com.aiagent.sdk.agent.FinishReason
import com.aiagent.sdk.llm.LlmClient
import com.aiagent.sdk.llm.LlmClientFactory
import com.aiagent.sdk.agent.SubAgentPreset
import com.aiagent.sdk.agent.SubAgentRegistry
import com.aiagent.sdk.log.AgentLoggerHolder
import com.aiagent.sdk.skill.CodeSkill
import com.aiagent.sdk.skill.SkillRegistry
import com.aiagent.sdk.tool.ToolRegistry
import com.zhangz.androidaiagent.demo.config.AgentConfig
import com.zhangz.androidaiagent.demo.headless.HeadlessPolicy
import com.zhangz.androidaiagent.demo.headless.HeadlessReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Demo 模块入口:统一构造 LlmClient / ToolRegistry / SkillRegistry,UI 只跟它打交道。
 *
 * 装机时序(`installOnce` 里完成,首次访问任意 lazy 字段时触发):
 *  1. 把 [LogcatAgentLogger] 注入 SDK 的 [AgentLoggerHolder],让 SDK 内部的
 *     loop / req / resp 日志能输出到 Logcat(默认 noop);
 *  2. 调用 KSP 在 `com.aiagent.generated` 下生成的 `bootAiTools_app()`,把本模块
 *     `@AiTool` / `@AiSkill` 的工具/Skill 灌进 [AiCapabilityRegistry];
 *  3. 把注册表里的 tools 拷进本对象的 [ToolRegistry];skills 包成 [CodeSkill] 进
 *     [SkillRegistry]。
 *
 * 业务自家工程接入时直接抄这份就行 —— 多模块情况下,这里依次调每个模块的
 * `bootAiTools_<bootName>()` 即可,SDK 的 [AiCapabilityRegistry] 是全局静态的。
 */
object AgentBootstrap {

    private var bootInstalled = false

    val tools: ToolRegistry by lazy { installOnce(); buildToolRegistry() }

    val skills: SkillRegistry by lazy { installOnce(); buildSkillRegistry() }

    val llmClient: LlmClient by lazy { installOnce(); LlmClientFactory.create(AgentConfig.activeProfile()) }

    /**
     * 演示用 Sub-Agent 注册表。注册了两个对照预设(researcher / coder),
     * 父 Agent 会通过 `call_sub_agent(agent_type=...)` 把子任务委派给它们。
     * 业务接入时按需精简或扩展 —— 留空集即可关闭 Sub-Agent 能力,
     * 此时 [AgentLoop] 不会向模型暴露 `call_sub_agent` 工具。
     */
    val subAgents: SubAgentRegistry by lazy { buildSubAgentRegistry() }

    val isReady: Boolean get() = AgentConfig.isConfigured

    fun newAgentLoop(
        confirmDangerous: suspend (Tool, JSONObject) -> Boolean = { _, _ -> true },
    ): AgentLoop = AgentLoop(llmClient, tools, confirmDangerous, subAgents)

    // ── Headless 任务调度 ─────────────────────────────────────────────────────────
    //   HeadlessAgentActivity.onCreate
    //     └─ AgentBootstrap.runHeadless(appCtx, task, policy)
    //           ├─ 校验 task / key / 并发,reject 走 reporter.onRejected*()
    //           └─ 接受:ApplicationScope 起协程,跑 AgentLoop 全程,事件灌给 reporter
    //
    // headlessScope 是 SDK 层之外的 ApplicationScope,Activity finish 后协程不受影响,
    // 进程被杀才会终止(可接受,headless 场景就是临时调试)。

    private val headlessScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var headlessJob: Job? = null

    /**
     * 执行一条 headless 任务。所有反馈走 [HeadlessReporter](logcat + Toast),本方法不抛异常。
     *
     * 并发策略:**拒绝第二条** —— 上一个 headlessJob 还活着时直接 reject,不排队、不抢断。
     */
    fun runHeadless(appContext: Context, task: String, policy: HeadlessPolicy) {
        val reporter = HeadlessReporter(appContext)

        if (task.isBlank()) {
            reporter.onRejectedEmptyTask()
            return
        }
        if (!isReady) {
            reporter.onRejectedNotConfigured(task)
            return
        }
        synchronized(this) {
            val current = headlessJob
            if (current != null && current.isActive) {
                reporter.onRejectedBusy(task)
                return
            }
            headlessJob = launchHeadless(task, policy, reporter)
        }
    }

    private fun launchHeadless(
        task: String,
        policy: HeadlessPolicy,
        reporter: HeadlessReporter,
    ): Job = headlessScope.launch {
        reporter.onAccepted(task, policy.allowDangerous, policy.preloadSkillIds)
        // 不复用聊天页的 session,每次新建,history 干净。
        val session = AgentSession(
            skillRegistry = skills,
            basePersona = AgentPromptDefaults.GENERIC_REACT_PERSONA,
        )
        policy.preloadSkillIds.forEach { session.loadSkill(it) }
        val loop = newAgentLoop(confirmDangerous = { _, _ -> policy.allowDangerous })
        var failure: Throwable? = null
        var finishReason: FinishReason? = null
        try {
            loop.run(session, task).collect { ev ->
                reporter.onAgentEvent(ev)
                when (ev) {
                    is AgentEvent.LoopFinished -> finishReason = ev.reason
                    is AgentEvent.LoopError -> failure = ev.cause
                    else -> Unit
                }
            }
        } catch (t: Throwable) {
            failure = t
        }
        val f = failure
        if (f != null) reporter.onFailed(f)
        else reporter.onFinished(finishReason ?: FinishReason.Unknown)
    }

    @Synchronized
    private fun installOnce() {
        if (bootInstalled) return
        // SDK 默认 logger 是 noop,不 install 的话 loop / req / resp 日志一律静默
        AgentLoggerHolder.install(LogcatAgentLogger)
        // KSP 生成函数:扫本模块所有 @AiTool / @AiSkill 注解,灌入全局 AiCapabilityRegistry
        bootAiTools_app()
        bootInstalled = true
    }

    private fun buildToolRegistry(): ToolRegistry = ToolRegistry().apply {
        registerAll(AiCapabilityRegistry.snapshotTools())
    }

    private fun buildSkillRegistry(): SkillRegistry = SkillRegistry().apply {
        AiCapabilityRegistry.snapshotSkills().forEach { register(CodeSkill(it)) }
    }

    /**
     * 两个对照预设:研究员擅长信息整理 / 程序员擅长写代码。两者都没有 baseToolNames,
     * 想用 demo 工具就要自己 list_skills + load_skill,与父 Agent 保持一致的渐进披露。
     */
    private fun buildSubAgentRegistry(): SubAgentRegistry = SubAgentRegistry().apply {
        register(
            SubAgentPreset(
                id = "researcher",
                displayName = "研究员",
                description = "擅长信息整理、事实查证、给出有依据的简短结论。" +
                    "需要查时间 / 对比方案 / 总结文本时把子任务派给我。",
                persona = "你是一名细致严谨的研究员。回答时优先调用可用工具拿事实," +
                    "无法解决再说明限制。结论控制在 200 字以内,不展开闲聊,不输出代码。",
                maxRounds = 4,
            )
        )
        register(
            SubAgentPreset(
                id = "coder",
                displayName = "程序员",
                description = "擅长写代码片段、修小 bug、解释技术概念。" +
                    "需要产出代码或技术解释时把子任务派给我。",
                persona = "你是一名专业程序员。优先给出可直接复制的代码块,再用一两句话说明思路。" +
                    "默认 Kotlin,除非用户指定别的语言。单次实现不超过 50 行。",
                maxRounds = 4,
            )
        )
    }
}
