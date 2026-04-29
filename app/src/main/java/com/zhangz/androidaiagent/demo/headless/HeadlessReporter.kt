package com.zhangz.androidaiagent.demo.headless

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.aiagent.runtime.ToolResult
import com.aiagent.sdk.agent.AgentEvent
import com.aiagent.sdk.agent.FinishReason

/**
 * Headless 任务的事件输出。
 *
 * 双通道:
 *  - logcat 通过 [Log] 走 `AiAgent_Headless` tag,事件流逐条打印,自动化脚本 grep 用
 *    `adb logcat -s AiAgent_Headless`;
 *  - Toast 在主线程弹出 task 起始 / 终态(成功 / 失败 / 拒绝),给手动派任务的测试人员
 *    一个肉眼可见的反馈。
 *
 * 中间过程的 AssistantDelta(打字机文本)不弹 Toast,只走 logcat 累计,避免刷屏。
 */
class HeadlessReporter(private val appContext: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val streamingText = StringBuilder()

    fun onAccepted(task: String, allowDangerous: Boolean, preload: List<String>) {
        Log.i(TAG, "task_accepted task=\"$task\" allowDangerous=$allowDangerous preload=$preload")
        toast("AI headless: 已派单\n$task")
    }

    fun onRejectedBusy(task: String) {
        Log.i(TAG, "task_rejected_busy task=\"$task\"")
        toast("AI headless: 上一个任务还在跑,已拒绝")
    }

    fun onRejectedNotConfigured(task: String) {
        Log.i(TAG, "task_rejected_not_configured task=\"$task\"")
        toast("AI headless: 未配置 AI key,无法执行")
    }

    fun onRejectedEmptyTask() {
        Log.i(TAG, "task_rejected_empty_task")
        toast("AI headless: task 为空,已拒绝")
    }

    fun onAgentEvent(ev: AgentEvent) {
        when (ev) {
            is AgentEvent.AssistantDelta -> streamingText.append(ev.text)
            is AgentEvent.AssistantFinal -> {
                if (streamingText.isNotEmpty()) {
                    Log.i(TAG, "assistant_text ${streamingText.toString().take(MAX)}")
                    streamingText.clear()
                }
                if (ev.toolCalls.isNotEmpty()) {
                    Log.i(TAG, "assistant_tool_calls ${ev.toolCalls.joinToString { it.name + "(" + it.arguments + ")" }}")
                }
            }
            is AgentEvent.ToolCallStarted ->
                Log.i(TAG, "tool_started name=${ev.call.name} args=${ev.call.arguments}")
            is AgentEvent.ToolCallCompleted -> {
                val r = ev.result
                val tag = if (r is ToolResult.Success) "tool_ok" else "tool_fail"
                val msg = if (r is ToolResult.Success) r.content else (r as ToolResult.Failure).message
                Log.i(TAG, "$tag name=${ev.call.name} ${msg.take(MAX)}")
            }
            is AgentEvent.ConfirmationDenied ->
                Log.i(TAG, "tool_denied name=${ev.call.name} (set --ez allowDangerous true to permit)")
            is AgentEvent.LoopFinished -> Unit // 总结在 onFinished
            is AgentEvent.LoopError -> Unit // 总结在 onFailed
            is AgentEvent.SubAgentStarted ->
                Log.i(TAG, "sub_agent_start callId=${ev.callId} type=${ev.agentType} depth=${ev.depth} task=${ev.task.take(MAX)}")
            is AgentEvent.SubAgentInnerEvent ->
                // 子循环里的事件递归走同一份处理逻辑,日志前缀里带上 depth 便于分层阅读。
                Log.i(TAG, "sub_agent_inner depth=${ev.depth} callId=${ev.callId} -> ${ev.inner.javaClass.simpleName}")
            is AgentEvent.SubAgentFinished ->
                Log.i(TAG, "sub_agent_done callId=${ev.callId} type=${ev.agentType} reason=${ev.reason} text=${ev.finalText.take(MAX)}")
        }
    }

    fun onFinished(reason: FinishReason) {
        Log.i(TAG, "task_finished reason=$reason")
        toast("AI headless: ✅ 完成 ($reason)")
    }

    fun onFailed(t: Throwable) {
        Log.e(TAG, "task_failed ${t.javaClass.simpleName}: ${t.message}", t)
        toast("AI headless: ❌ ${t.javaClass.simpleName}: ${t.message ?: ""}")
    }

    private fun toast(text: String) {
        main.post {
            try {
                Toast.makeText(appContext, text, Toast.LENGTH_LONG).show()
            } catch (_: Throwable) {
                // 测试 / 进程被杀场景兜底
            }
        }
    }

    companion object {
        private const val TAG = "AiAgent_Headless"
        private const val MAX = 500
    }
}
