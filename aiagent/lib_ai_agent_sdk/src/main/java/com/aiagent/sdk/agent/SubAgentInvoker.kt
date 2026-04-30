package com.aiagent.sdk.agent

import com.aiagent.runtime.ToolResult
import com.aiagent.sdk.llm.LlmClient
import com.aiagent.sdk.llm.Message
import com.aiagent.sdk.llm.ToolCall
import com.aiagent.sdk.log.AgentLoggerHolder
import com.aiagent.sdk.tool.ToolRegistry
import kotlinx.coroutines.flow.collect

/**
 * 拦截 `call_sub_agent`。本 invoker 的 invoke 不属于普通工具派发链中的「认领-执行」语义,
 * 而是会现拉一个子 [AgentLoop]、跑到底、把最终 assistant 文本作为 tool 消息回灌给父循环。
 *
 * 设计要点:
 *  - 子会话的 history 完全独立,不继承父会话历史;调用方在 `task` 里要把上下文写够;
 *  - 子会话的 [AgentSession.depth] = 父深度 + 1,递归到 [MAX_DEPTH] 时直接拒绝;
 *  - 子循环的 confirmDangerous 永远 deny-all:防止父放行 dangerous 工具后子也能调,
 *    破坏「父用户已确认」的语义;
 *  - 子循环里的事件按原顺序包成 [AgentEvent.SubAgentInnerEvent] 转发给父 flow,
 *    UI 拿到后递归走相同的渲染逻辑即可呈现嵌套调用栈。
 *
 * 子循环本身依然能调用 sub-agent —— 受 [SubAgentPreset.allowedSubAgentIds] 进一步收紧:
 * 默认空集 = 不可再嵌套。
 */
internal class SubAgentInvoker(
    private val llm: LlmClient,
    private val tools: ToolRegistry,
    private val subAgents: SubAgentRegistry,
) : ToolInvoker {

    override suspend fun invoke(
        session: AgentSession,
        call: ToolCall,
        emit: suspend (AgentEvent) -> Unit,
    ): InvocationOutcome {
        if (call.name != SubAgentTools.NAME_CALL) return InvocationOutcome.NotHandled

        val log = AgentLoggerHolder.logger
        emit(AgentEvent.ToolCallStarted(call))

        val args = parseToolArgs(call.arguments)
        val agentType = args.optString("agent_type", "")
        val task = args.optString("task", "")

        // 参数校验:agent_type 必填、task 非空、preset 必须存在,否则不启动子循环。
        val rejectReason: String? = when {
            agentType.isBlank() -> "missing_agent_type"
            task.isBlank() -> "missing_task"
            subAgents.get(agentType) == null ->
                "unknown_agent_type: $agentType (available: ${subAgents.all().joinToString(",") { it.id }})"
            session.depth >= MAX_DEPTH ->
                "max_depth_reached: depth=${session.depth} max=$MAX_DEPTH"
            else -> null
        }
        if (rejectReason != null) {
            log.loop("sub_agent_rejected id=${call.id} reason=$rejectReason")
            val failure = ToolResult.Failure(rejectReason)
            emit(AgentEvent.ToolCallCompleted(call, failure))
            return InvocationOutcome.Handled(Message.Tool(call.id, "error: $rejectReason"))
        }

        val preset = subAgents.get(agentType)!!
        val childDepth = session.depth + 1
        log.loop("sub_agent_start id=${call.id} type=$agentType depth=$childDepth task=$task")
        emit(AgentEvent.SubAgentStarted(call.id, agentType, task, childDepth))

        // 子会话:独立 history,继承父的 SkillRegistry(progressive disclosure 共享 skill 库)
        // 与 memory(用户画像 / 长期事实跨 agent 共享);baseToolNames / persona / maxRounds 走 preset。
        val childSession = AgentSession(
            skillRegistry = session.skillRegistry,
            basePersona = preset.persona,
            baseToolNames = preset.baseToolNames,
            maxRounds = preset.maxRounds,
            depth = childDepth,
            memory = session.memory,
        )
        // 子循环只看到 preset 允许再嵌套的子 Agent,默认空集 = 不可嵌套。
        val childRegistry = filteredSubAgents(preset.allowedSubAgentIds)
        val childLoop = AgentLoop(
            llm = llm,
            tools = tools,
            confirmDangerous = DENY_ALL,
            subAgents = childRegistry,
        )

        // 跑子循环,把每条事件包成 SubAgentInnerEvent 转发,同时累计最终文本。
        val finalText = StringBuilder()
        var finishReason: FinishReason = FinishReason.Unknown
        var error: Throwable? = null
        try {
            childLoop.run(childSession, task).collect { ev ->
                emit(AgentEvent.SubAgentInnerEvent(call.id, childDepth, ev))
                when (ev) {
                    is AgentEvent.AssistantFinal -> {
                        // 多轮里只保留最后一轮的 final text 给父(中间轮多半是 tool_calls)。
                        if (ev.text.isNotBlank()) {
                            finalText.setLength(0)
                            finalText.append(ev.text)
                        }
                    }
                    is AgentEvent.LoopFinished -> finishReason = ev.reason
                    is AgentEvent.LoopError -> error = ev.cause
                    else -> Unit
                }
            }
        } catch (t: Throwable) {
            error = t
        }

        val toolContent: String = when {
            error != null -> "error: sub_agent_failed: ${error!!.javaClass.simpleName}: ${error!!.message ?: ""}"
            finalText.isEmpty() -> "(sub agent finished without text, reason=$finishReason)"
            else -> finalText.toString()
        }
        val result = if (error == null) ToolResult.Success(toolContent) else ToolResult.Failure(toolContent)
        log.loop(
            "sub_agent_done id=${call.id} type=$agentType depth=$childDepth " +
                "reason=$finishReason textLen=${finalText.length}"
        )
        emit(AgentEvent.SubAgentFinished(call.id, agentType, toolContent, finishReason))
        emit(AgentEvent.ToolCallCompleted(call, result))
        return InvocationOutcome.Handled(Message.Tool(call.id, toolContent))
    }

    private fun filteredSubAgents(allowedIds: Set<String>): SubAgentRegistry {
        if (allowedIds.isEmpty()) return SubAgentRegistry.EMPTY
        val out = SubAgentRegistry()
        for (id in allowedIds) subAgents.get(id)?.let(out::register)
        return out
    }

    companion object {
        /** 父循环 depth=0,允许向下嵌套到 depth=3。再深就直接拒绝。 */
        const val MAX_DEPTH = 3

        /** 子 Agent 的危险动作一律拒绝:父若放行 dangerous 工具不应被子悄悄继承。 */
        private val DENY_ALL: suspend (com.aiagent.runtime.Tool, org.json.JSONObject) -> Boolean =
            { _, _ -> false }
    }
}
