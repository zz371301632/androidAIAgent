package com.zhangz.androidaiagent.demo.bootstrap

import android.content.Context
import com.aiagent.sdk.agent.AgentEvent
import com.aiagent.sdk.agent.AgentSession
import com.aiagent.sdk.agent.FinishReason
import com.aiagent.sdk.setup.AiAgentRuntime
import com.zhangz.androidaiagent.demo.headless.HeadlessPolicy
import com.zhangz.androidaiagent.demo.headless.HeadlessReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Headless 任务调度器(demo 自家能力,不在 SDK 里):adb 派一条任务进来,本对象
 * 在 ApplicationScope 上跑一个独立 [AgentSession],事件全部灌给 [HeadlessReporter]。
 *
 * 调用链:
 * ```
 *   HeadlessAgentActivity.onCreate
 *     └─ HeadlessRunner.run(appCtx, task, policy)
 *           ├─ 校验 task / key / 并发,reject 走 reporter.onRejected*()
 *           └─ 接受:ApplicationScope 起协程,跑 AgentLoop 全程,事件灌给 reporter
 * ```
 *
 * [headlessScope] 是 SDK 层之外的 ApplicationScope,Activity finish 后协程不受影响,
 * 进程被杀才会终止(可接受,headless 场景就是临时调试)。
 *
 * 并发策略:**拒绝第二条** —— 上一个 [headlessJob] 还活着时直接 reject,不排队、不抢断。
 */
object HeadlessRunner {

    private val headlessScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var headlessJob: Job? = null

    /**
     * 执行一条 headless 任务。所有反馈走 [HeadlessReporter](logcat + Toast),本方法不抛异常。
     */
    fun run(appContext: Context, task: String, policy: HeadlessPolicy) {
        val reporter = HeadlessReporter(appContext)

        if (task.isBlank()) {
            reporter.onRejectedEmptyTask()
            return
        }
        if (!AiAgentRuntime.isReady) {
            reporter.onRejectedNotConfigured(task)
            return
        }
        synchronized(this) {
            val current = headlessJob
            if (current != null && current.isActive) {
                reporter.onRejectedBusy(task)
                return
            }
            headlessJob = launch(task, policy, reporter)
        }
    }

    private fun launch(
        task: String,
        policy: HeadlessPolicy,
        reporter: HeadlessReporter,
    ): Job = headlessScope.launch {
        reporter.onAccepted(task, policy.allowDangerous, policy.preloadSkillIds)
        // 不复用聊天页的 session,每次新建,history 干净。
        val session = AgentSession(
            skillRegistry = AiAgentRuntime.skills,
            basePersona = AiAgentRuntime.persona,
            memory = AiAgentRuntime.memory,
        )
        policy.preloadSkillIds.forEach { session.loadSkill(it) }
        val loop = AiAgentRuntime.newAgentLoop(confirmDangerous = { _, _ -> policy.allowDangerous })
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
}
