package com.aiagent.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiagent.runtime.Tool
import com.aiagent.runtime.ToolResult
import com.aiagent.sdk.agent.AgentEvent
import com.aiagent.sdk.agent.AgentLoop
import com.aiagent.sdk.agent.AgentSession
import com.aiagent.sdk.agent.FinishReason
import com.aiagent.sdk.agent.SubAgentTools
import com.aiagent.sdk.llm.ToolCall
import com.aiagent.sdk.setup.AiAgentRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * 把 [AgentLoop] 的事件流翻译成 UI state 的唯一对象。集成方做自家 ViewModel 时,
 * 抄这一份就够了 —— 关键点只有两条:
 *  - confirmDangerous 是 suspend 回调,本类用 CompletableDeferred 实现「UI 弹框 →
 *    用户点击 → resolve」的等待;
 *  - 一次会话只用一个 [AgentSession],跨多次 sendUserInput 复用历史 / 已加载 skill。
 */
class AgentChatViewModel : ViewModel() {

    private val _state = MutableStateFlow(
        ChatUiState(
            configured = AiAgentRuntime.isReady,
            quickTools = if (AiAgentRuntime.isReady) collectQuickTools() else emptyList(),
        )
    )
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val session: AgentSession = AgentSession(
        skillRegistry = AiAgentRuntime.skills,
        basePersona = AiAgentRuntime.persona,
        memory = AiAgentRuntime.memory,
    )
    private val loop: AgentLoop = AiAgentRuntime.newAgentLoop(::confirmDangerous)
    private val nextId = AtomicLong(1)
    private var pendingDeferred: CompletableDeferred<Boolean>? = null
    private var runningJob: Job? = null

    fun sendUserInput(text: String) {
        if (text.isBlank() || _state.value.isRunning) return
        if (!AiAgentRuntime.isReady) {
            _state.update { it.copy(error = "AI key 未配置,请在 local.properties 写入 ai.deepseek.key") }
            return
        }
        appendBubble(ChatBubble.User(nextId.getAndIncrement(), text))
        runningJob = viewModelScope.launch {
            _state.update { it.copy(isRunning = true, streamingText = "", error = null) }
            try {
                loop.run(session, text).collect(::onAgentEvent)
            } finally {
                _state.update { it.copy(isRunning = false, streamingText = "") }
            }
        }
    }

    fun cancel() {
        runningJob?.cancel()
        pendingDeferred?.complete(false)
    }

    /** 用户在确认对话框上点确认/取消。 */
    fun resolveConfirmation(approve: Boolean) {
        pendingDeferred?.complete(approve)
        pendingDeferred = null
        _state.update { it.copy(pending = null) }
    }

    private suspend fun confirmDangerous(tool: Tool, args: JSONObject): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingDeferred = deferred
        _state.update { it.copy(pending = PendingConfirmation(tool, args)) }
        return deferred.await()
    }

    /**
     * 快捷工具:绕过 [AgentLoop] 直接 [Tool.execute],不写 session.history,因此 LLM
     * 下一轮看不到本次调用。仅展示一条 [ChatBubble.ToolCallView] 反馈结果。
     * 危险工具复用 [confirmDangerous] 的 pending 弹窗机制。
     */
    fun runQuickTool(tool: Tool) {
        val st = _state.value
        if (st.isRunning || !st.configured) return
        runningJob = viewModelScope.launch {
            _state.update { it.copy(isRunning = true, error = null) }
            try {
                if (tool.requiresConfirmation && !confirmDangerous(tool, JSONObject())) {
                    appendBubble(quickToolBubble(tool, ToolUiState.Denied, output = "已取消"))
                    return@launch
                }
                val bubbleId = nextId.getAndIncrement()
                appendBubble(quickToolBubble(tool, ToolUiState.Running, idOverride = bubbleId))
                val result = runCatching { tool.execute(JSONObject()) }
                    .getOrElse { ToolResult.Failure("exception: ${it.javaClass.simpleName}: ${it.message ?: ""}") }
                updateBubbleById(bubbleId) { b ->
                    if (b !is ChatBubble.ToolCallView) b
                    else when (result) {
                        is ToolResult.Success -> b.copy(state = ToolUiState.Success, output = result.content)
                        is ToolResult.Failure -> b.copy(state = ToolUiState.Failure, output = result.message)
                    }
                }
            } finally {
                _state.update { it.copy(isRunning = false) }
            }
        }
    }

    private fun onAgentEvent(ev: AgentEvent) {
        when (ev) {
            is AgentEvent.AssistantDelta -> _state.update {
                it.copy(streamingText = it.streamingText + ev.text)
            }
            is AgentEvent.AssistantFinal -> {
                val txt = _state.value.streamingText
                if (txt.isNotEmpty()) {
                    appendBubble(ChatBubble.Assistant(nextId.getAndIncrement(), txt))
                }
                _state.update { it.copy(streamingText = "") }
            }
            is AgentEvent.ToolCallStarted -> {
                if (ev.call.name != SubAgentTools.NAME_CALL) {
                    appendBubble(toolBubble(ev.call, ToolUiState.Running))
                }
            }
            is AgentEvent.ToolCallCompleted -> {
                if (ev.call.name != SubAgentTools.NAME_CALL) {
                    updateToolBubble(ev.call) { b ->
                        when (val r = ev.result) {
                            is ToolResult.Success -> b.copy(state = ToolUiState.Success, output = r.content)
                            is ToolResult.Failure -> b.copy(state = ToolUiState.Failure, output = r.message)
                        }
                    }
                }
                _state.update { it.copy(loadedSkillIds = session.loadedSkillIds.toList()) }
            }
            is AgentEvent.ConfirmationDenied -> appendBubble(
                toolBubble(ev.call, ToolUiState.Denied, output = "已取消"),
            )
            is AgentEvent.SubAgentStarted -> appendBubble(
                ChatBubble.SubAgentBubble(
                    id = nextId.getAndIncrement(),
                    callId = ev.callId,
                    agentType = ev.agentType,
                    task = ev.task,
                    depth = ev.depth,
                    state = ToolUiState.Running,
                ),
            )
            is AgentEvent.SubAgentInnerEvent -> _state.update { s ->
                s.copy(bubbles = applyToSubAgent(s.bubbles, ev.callId) { sub ->
                    sub.copy(inner = applyInnerEvent(sub.inner, ev.inner))
                })
            }
            is AgentEvent.SubAgentFinished -> _state.update { s ->
                s.copy(bubbles = applyToSubAgent(s.bubbles, ev.callId) { sub ->
                    sub.copy(
                        state = if (ev.reason == FinishReason.Stop) ToolUiState.Success
                        else ToolUiState.Failure,
                        finalText = ev.finalText,
                    )
                })
            }
            is AgentEvent.LoopFinished -> Unit
            is AgentEvent.LoopError -> _state.update {
                it.copy(error = ev.cause.message ?: ev.cause.javaClass.simpleName)
            }
        }
    }

    /**
     * 在气泡树里递归找到 callId 对应的 SubAgentBubble,做不可变 transform。
     * 任意深度嵌套都能命中:递归先看本层,再下钻每个 SubAgent 的 [inner]。
     */
    private fun applyToSubAgent(
        bubbles: List<ChatBubble>,
        callId: String,
        transform: (ChatBubble.SubAgentBubble) -> ChatBubble.SubAgentBubble,
    ): List<ChatBubble> = bubbles.map { b ->
        when {
            b is ChatBubble.SubAgentBubble && b.callId == callId -> transform(b)
            b is ChatBubble.SubAgentBubble ->
                b.copy(inner = applyToSubAgent(b.inner, callId, transform))
            else -> b
        }
    }

    /**
     * 把子循环里的一条原始 [AgentEvent] 应用到 SubAgent 气泡的 [inner] 列表上。
     * 与父级 [onAgentEvent] 同构,但不更新顶层 streamingText / loadedSkillIds —— 子
     * 循环的进度只表现为内部气泡。AssistantDelta / LoopFinished / LoopError 在子层面
     * 不渲染,避免噪声。
     */
    private fun applyInnerEvent(
        inner: List<ChatBubble>,
        ev: AgentEvent,
    ): List<ChatBubble> = when (ev) {
        is AgentEvent.AssistantFinal -> if (ev.text.isBlank()) inner
        else inner + ChatBubble.Assistant(nextId.getAndIncrement(), ev.text)
        is AgentEvent.ToolCallStarted -> if (ev.call.name == SubAgentTools.NAME_CALL) inner
        else inner + toolBubble(ev.call, ToolUiState.Running)
        is AgentEvent.ToolCallCompleted -> if (ev.call.name == SubAgentTools.NAME_CALL) inner
        else inner.map { b ->
            if (b is ChatBubble.ToolCallView && b.name == ev.call.name && b.state == ToolUiState.Running) {
                when (val r = ev.result) {
                    is ToolResult.Success -> b.copy(state = ToolUiState.Success, output = r.content)
                    is ToolResult.Failure -> b.copy(state = ToolUiState.Failure, output = r.message)
                }
            } else b
        }
        is AgentEvent.ConfirmationDenied -> inner + toolBubble(ev.call, ToolUiState.Denied, output = "已取消")
        is AgentEvent.SubAgentStarted -> inner + ChatBubble.SubAgentBubble(
            id = nextId.getAndIncrement(),
            callId = ev.callId,
            agentType = ev.agentType,
            task = ev.task,
            depth = ev.depth,
            state = ToolUiState.Running,
        )
        is AgentEvent.SubAgentInnerEvent -> applyToSubAgent(inner, ev.callId) { sub ->
            sub.copy(inner = applyInnerEvent(sub.inner, ev.inner))
        }
        is AgentEvent.SubAgentFinished -> applyToSubAgent(inner, ev.callId) { sub ->
            sub.copy(
                state = if (ev.reason == FinishReason.Stop) ToolUiState.Success
                else ToolUiState.Failure,
                finalText = ev.finalText,
            )
        }
        else -> inner
    }

    private fun toolBubble(call: ToolCall, st: ToolUiState, output: String? = null): ChatBubble.ToolCallView {
        val args = call.arguments.takeIf { it.isNotBlank() && it != "{}" }?.let { " $it" } ?: ""
        return ChatBubble.ToolCallView(
            id = nextId.getAndIncrement(),
            name = call.name,
            argsPreview = call.name + args,
            state = st,
            output = output,
        )
    }

    private fun appendBubble(b: ChatBubble) {
        _state.update { it.copy(bubbles = it.bubbles + b) }
    }

    private fun updateToolBubble(call: ToolCall, transform: (ChatBubble.ToolCallView) -> ChatBubble.ToolCallView) {
        _state.update { s ->
            val list = s.bubbles.toMutableList()
            val idx = list.indexOfLast { it is ChatBubble.ToolCallView && it.name == call.name && it.state == ToolUiState.Running }
            if (idx >= 0) list[idx] = transform(list[idx] as ChatBubble.ToolCallView)
            s.copy(bubbles = list)
        }
    }

    private fun updateBubbleById(id: Long, transform: (ChatBubble) -> ChatBubble) {
        _state.update { s ->
            val list = s.bubbles.toMutableList()
            val idx = list.indexOfLast { it.id == id }
            if (idx >= 0) list[idx] = transform(list[idx])
            s.copy(bubbles = list)
        }
    }

    private fun quickToolBubble(
        tool: Tool,
        st: ToolUiState,
        output: String? = null,
        idOverride: Long? = null,
    ): ChatBubble.ToolCallView = ChatBubble.ToolCallView(
        id = idOverride ?: nextId.getAndIncrement(),
        name = tool.name,
        argsPreview = tool.name,
        state = st,
        output = output,
    )

    /**
     * 从 [AiAgentRuntime.tools] 里挑出无参工具(`properties` 为空)。有参工具点了
     * 也无法直接执行,UI 不暴露。schema 解析失败时按「非无参」处理,避免误触。
     */
    private fun collectQuickTools(): List<Tool> = AiAgentRuntime.tools.all().filter { tool ->
        val schema = runCatching { JSONObject(tool.parametersJsonSchema) }.getOrNull() ?: return@filter false
        val props = schema.optJSONObject("properties")
        props == null || props.length() == 0
    }

    private inline fun MutableStateFlow<ChatUiState>.update(transform: (ChatUiState) -> ChatUiState) {
        value = transform(value)
    }
}
