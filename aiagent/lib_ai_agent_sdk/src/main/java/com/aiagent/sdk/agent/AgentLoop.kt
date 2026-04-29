package com.aiagent.sdk.agent

import com.aiagent.runtime.Tool
import com.aiagent.sdk.llm.LlmClient
import com.aiagent.sdk.llm.LlmStreamEvent
import com.aiagent.sdk.llm.Message
import com.aiagent.sdk.llm.ToolCall
import com.aiagent.sdk.log.AgentLoggerHolder
import com.aiagent.sdk.skill.SkillCallTools
import com.aiagent.sdk.tool.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import org.json.JSONObject

/**
 * AgentLoop:ReAct 风格的「Reason → Act → Observe」主循环。
 *
 * 一轮的伪代码:
 * ```
 *   while round < maxRounds:
 *     // —— Reason —— 流式调一次 LLM,把 content / reasoning_content / tool_calls
 *     //              累计成一条 assistant 消息,固化进 session.history
 *     turn = think(session)
 *     emit AssistantFinal
 *     if turn.shouldStop:
 *         emit LoopFinished
 *         return
 *     // —— Act + Observe —— 把每个 tool_call 交给 ToolInvoker 链派发,
 *     //                     产物 append 回 session,下一轮 think 自然看到结果
 *     act(session, turn.toolCalls)
 * ```
 *
 * 扩展点:
 *  - 新工具:注册到 [ToolRegistry] 即可,Schema 自动出现在下一轮 prompt。
 *  - 新派发后端(MCP / Sub-Agent / Memory tool):实现一个 [ToolInvoker] 追加到
 *    [toolInvoker] 的链上,主循环零修改。
 *  - 新 Skill 协议工具:扩展 [SkillToolInvoker] 的 when 分支。
 *  - 上下文装配:见 [AgentSession.snapshot] / [AgentSession.activeToolNames]。
 *
 * UI 通过 collect 拿到 [AgentEvent] 序列,自己负责消息渲染。
 */
class AgentLoop(
    private val llm: LlmClient,
    private val tools: ToolRegistry,
    private val confirmDangerous: suspend (Tool, JSONObject) -> Boolean = { _, _ -> true },
) {

    /**
     * Tool 派发链:Skill 调度优先,其次普通 ToolRegistry。
     * 未来若引入 MCP / Sub-Agent / Memory tool,只需在链上加一节,主循环零修改。
     */
    private val toolInvoker: ToolInvoker = CompositeToolInvoker(
        listOf(
            SkillToolInvoker,
            RegistryToolInvoker(tools, confirmDangerous),
        ),
    )

    /** 跑一轮用户输入。返回的 Flow 在 LoopFinished / LoopError 之后自然结束。 */
    fun run(session: AgentSession, userInput: String): Flow<AgentEvent> = flow {
        session.appendUser(userInput)
        AgentLoggerHolder.logger.loop("user_input loaded=${session.loadedSkillIds} text=$userInput")
        runLoop(session)
    }

    private suspend fun FlowCollector<AgentEvent>.runLoop(session: AgentSession) {
        val log = AgentLoggerHolder.logger
        try {
            for (round in 1..session.maxRounds) {
                val turn = think(session, round)
                emit(AgentEvent.AssistantFinal(turn.assistant.content.orEmpty(), turn.assistant.toolCalls))

                if (turn.shouldStop) {
                    val reason = mapFinishReason(turn.finishReason)
                    log.loop("loop_finished reason=$reason rounds=$round")
                    emit(AgentEvent.LoopFinished(reason))
                    return
                }
                act(session, turn.assistant.toolCalls)
            }
            log.loop("loop_finished reason=MaxRoundsReached rounds=${session.maxRounds}")
            emit(AgentEvent.LoopFinished(FinishReason.MaxRoundsReached))
        } catch (t: Throwable) {
            log.loopError("loop_error ${t.javaClass.simpleName}: ${t.message}", t)
            emit(AgentEvent.LoopError(t))
        }
    }

    /**
     * Reason 阶段:装配 prompt → 流式调用 LLM → 累成 assistant 消息 → 固化进会话历史。
     *
     * reasoning_content 必须随 assistant 一起持久化:DeepSeek v4-pro 等供应商在
     * thinking 模式下要求下一轮把它原样 echo 回去,否则 HTTP 400。
     */
    private suspend fun FlowCollector<AgentEvent>.think(
        session: AgentSession,
        round: Int,
    ): Turn {
        val log = AgentLoggerHolder.logger
        val messages = session.snapshot()
        // 业务工具严格按 activeToolNames 白名单过滤:Claude 风格下默认空集,
        // 模型只能看到 SkillCallTools(list_skills/load_skill);
        // load_skill 后 activeToolNames 才会扩到对应 skill 的工具集。
        val schemas = SkillCallTools.ALL + tools.listSchemas(session.activeToolNames())
        log.loop(
            "round=$round/${session.maxRounds} loaded=${session.loadedSkillIds} " +
                "messages=${messages.size} toolSchemas=${schemas.size}"
        )

        val acc = StreamAccumulator()
        llm.chatStream(messages = messages, tools = schemas).collect { ev ->
            if (ev is LlmStreamEvent.ContentDelta) emit(AgentEvent.AssistantDelta(ev.text))
            acc.apply(ev)
        }

        val assistant = Message.Assistant(
            content = acc.content.takeIf { it.isNotEmpty() },
            reasoningContent = acc.reasoning.takeIf { it.isNotEmpty() },
            toolCalls = acc.toolCalls(),
        )
        session.appendAssistant(assistant)
        log.loop(
            "assistant_final round=$round finish=${acc.finishReason} " +
                "contentLen=${acc.content.length} reasoningLen=${acc.reasoning.length} " +
                "toolCalls=${assistant.toolCalls.size} content=${acc.content}"
        )
        return Turn(assistant, acc.finishReason)
    }

    /**
     * Act + Observe 阶段:逐个 tool_call 走派发链,产物 append 回会话历史,让下一轮
     * think 自然看到结果。事件 / 日志由各 invoker 自行负责,这里只做 plumbing。
     */
    private suspend fun FlowCollector<AgentEvent>.act(
        session: AgentSession,
        calls: List<ToolCall>,
    ) {
        val emitFn: suspend (AgentEvent) -> Unit = { emit(it) }
        for (call in calls) {
            val outcome = toolInvoker.invoke(session, call, emitFn)
            // RegistryToolInvoker 会把未识别工具兜成 Failure,因此实际上不会出现
            // NotHandled;真出现时静默跳过,模型在下一轮会通过 tool 消息缺失自我纠错。
            if (outcome is InvocationOutcome.Handled) {
                session.appendTool(outcome.toolMessage)
            }
        }
    }

    private fun mapFinishReason(raw: String?): FinishReason = when (raw) {
        "stop" -> FinishReason.Stop
        else -> FinishReason.Unknown
    }

    /** 一轮 reason 的产出。toolCalls 为空 / finishReason != "tool_calls" 即终止循环。 */
    private data class Turn(
        val assistant: Message.Assistant,
        val finishReason: String?,
    ) {
        val shouldStop: Boolean
            get() = assistant.toolCalls.isEmpty() || finishReason != "tool_calls"
    }
}
