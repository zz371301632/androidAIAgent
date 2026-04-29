package com.aiagent.sdk.agent

import com.aiagent.runtime.Tool
import com.aiagent.runtime.ToolResult
import com.aiagent.sdk.llm.Message
import com.aiagent.sdk.llm.ToolCall
import com.aiagent.sdk.log.AgentLoggerHolder
import com.aiagent.sdk.skill.SkillCallTools
import com.aiagent.sdk.tool.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一次 tool_call 派发的产物。
 *
 * - [Handled]   认领并完成,带回要 append 到 [AgentSession] 的 tool 消息。
 * - [NotHandled] 不认领,主循环把 call 交给链上的下一个 invoker。
 */
internal sealed interface InvocationOutcome {
    data class Handled(val toolMessage: Message.Tool) : InvocationOutcome
    data object NotHandled : InvocationOutcome
}

/**
 * 工具派发的统一入口。Skill 调度、ToolRegistry、未来的 MCP / Sub-Agent / Memory
 * 都实现这个接口,[AgentLoop] 对它们一视同仁。
 *
 * 每个 invoker 自己负责:
 *  - 决定是否认领该 call;
 *  - 通过 [emit] 发起 [AgentEvent.ToolCallStarted] / [AgentEvent.ToolCallCompleted]
 *    / [AgentEvent.ConfirmationDenied] 等 UI 可见事件;
 *  - 写日志(`AiAgent_Loop` channel);
 *  - 通过 [InvocationOutcome.Handled] 返回要 append 的 tool 消息。
 */
internal interface ToolInvoker {
    suspend fun invoke(
        session: AgentSession,
        call: ToolCall,
        emit: suspend (AgentEvent) -> Unit,
    ): InvocationOutcome
}

/** 顺序尝试,第一个认领的胜出;都不认领则继续返回 [InvocationOutcome.NotHandled]。 */
internal class CompositeToolInvoker(
    private val invokers: List<ToolInvoker>,
) : ToolInvoker {
    override suspend fun invoke(
        session: AgentSession,
        call: ToolCall,
        emit: suspend (AgentEvent) -> Unit,
    ): InvocationOutcome {
        for (inv in invokers) {
            val out = inv.invoke(session, call, emit)
            if (out !is InvocationOutcome.NotHandled) return out
        }
        return InvocationOutcome.NotHandled
    }
}

/**
 * 拦截 list_skills / load_skill。这两个工具不在 [ToolRegistry],改写的是
 * [AgentSession] 自身的 loadedSkillIds 状态,所以单独成 invoker。
 */
internal object SkillToolInvoker : ToolInvoker {
    override suspend fun invoke(
        session: AgentSession,
        call: ToolCall,
        emit: suspend (AgentEvent) -> Unit,
    ): InvocationOutcome = when (call.name) {
        SkillCallTools.NAME_LIST -> handleList(session, call, emit)
        SkillCallTools.NAME_LOAD -> handleLoad(session, call, emit)
        else -> InvocationOutcome.NotHandled
    }

    private suspend fun handleList(
        session: AgentSession,
        call: ToolCall,
        emit: suspend (AgentEvent) -> Unit,
    ): InvocationOutcome {
        emit(AgentEvent.ToolCallStarted(call))
        val arr = JSONArray()
        session.skillRegistry.all().forEach { sk ->
            arr.put(
                JSONObject()
                    .put("id", sk.id)
                    .put("name", sk.name)
                    .put("description", sk.description)
                    .put("loaded", sk.id in session.loadedSkillIds)
            )
        }
        val content = arr.toString()
        AgentLoggerHolder.logger.loop(
            "skill_list id=${call.id} count=${session.skillRegistry.all().size} loaded=${session.loadedSkillIds}"
        )
        emit(AgentEvent.ToolCallCompleted(call, ToolResult.Success(content)))
        return InvocationOutcome.Handled(Message.Tool(call.id, content))
    }

    private suspend fun handleLoad(
        session: AgentSession,
        call: ToolCall,
        emit: suspend (AgentEvent) -> Unit,
    ): InvocationOutcome {
        emit(AgentEvent.ToolCallStarted(call))
        val args = parseToolArgs(call.arguments)
        val id = args.optString("id", "")
        val (ok, content) = when {
            id.isBlank() -> false to "error: missing 'id'"
            session.skillRegistry.get(id) == null -> false to "error: unknown_skill: $id"
            !session.loadSkill(id) -> true to "already_loaded: $id"
            else -> true to "loaded: $id"
        }
        AgentLoggerHolder.logger.loop(
            "skill_load id=${call.id} target=$id ok=$ok loaded=${session.loadedSkillIds}"
        )
        val result = if (ok) ToolResult.Success(content) else ToolResult.Failure(content)
        emit(AgentEvent.ToolCallCompleted(call, result))
        return InvocationOutcome.Handled(Message.Tool(call.id, content))
    }
}

/**
 * 派发到 [ToolRegistry]。包含危险动作的二次确认门控:命中 [Tool.requiresConfirmation]
 * 时先经 [confirmDangerous] 询问,拒绝则不进 dispatch、回 `denied_by_user`。
 */
internal class RegistryToolInvoker(
    private val tools: ToolRegistry,
    private val confirmDangerous: suspend (Tool, JSONObject) -> Boolean,
) : ToolInvoker {
    override suspend fun invoke(
        session: AgentSession,
        call: ToolCall,
        emit: suspend (AgentEvent) -> Unit,
    ): InvocationOutcome {
        val toolImpl = tools.get(call.name)
        if (toolImpl != null && toolImpl.requiresConfirmation) {
            val args = parseToolArgs(call.arguments)
            if (!confirmDangerous(toolImpl, args)) {
                AgentLoggerHolder.logger.loop("tool_denied id=${call.id} name=${call.name}")
                emit(AgentEvent.ConfirmationDenied(call))
                return InvocationOutcome.Handled(Message.Tool(call.id, "denied_by_user"))
            }
        }
        AgentLoggerHolder.logger.loop("tool_start id=${call.id} name=${call.name} args=${call.arguments}")
        emit(AgentEvent.ToolCallStarted(call))
        val result = tools.dispatch(call.name, call.arguments)
        val content = when (result) {
            is ToolResult.Success -> result.content
            is ToolResult.Failure -> "error: ${result.message}"
        }
        AgentLoggerHolder.logger.loop(
            "tool_done id=${call.id} name=${call.name} result=${result.javaClass.simpleName} content=$content"
        )
        emit(AgentEvent.ToolCallCompleted(call, result))
        return InvocationOutcome.Handled(Message.Tool(call.id, content))
    }
}

/** 模型给的 arguments 字符串通常是合法 JSON;实在不是就当空对象,兜住协议错误。 */
internal fun parseToolArgs(s: String): JSONObject =
    runCatching { JSONObject(s) }.getOrElse { JSONObject() }
