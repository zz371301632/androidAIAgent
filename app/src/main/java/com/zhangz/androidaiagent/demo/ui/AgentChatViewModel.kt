package com.zhangz.androidaiagent.demo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiagent.runtime.Tool
import com.aiagent.runtime.ToolResult
import com.aiagent.sdk.agent.AgentEvent
import com.aiagent.sdk.agent.AgentLoop
import com.aiagent.sdk.agent.AgentPromptDefaults
import com.aiagent.sdk.agent.AgentSession
import com.aiagent.sdk.llm.ToolCall
import com.zhangz.androidaiagent.demo.bootstrap.AgentBootstrap
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

    private val _state = MutableStateFlow(ChatUiState(configured = AgentBootstrap.isReady))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val session: AgentSession = AgentSession(
        skillRegistry = AgentBootstrap.skills,
        basePersona = AgentPromptDefaults.GENERIC_REACT_PERSONA,
    )
    private val loop: AgentLoop = AgentBootstrap.newAgentLoop(::confirmDangerous)
    private val nextId = AtomicLong(1)
    private var pendingDeferred: CompletableDeferred<Boolean>? = null
    private var runningJob: Job? = null

    fun sendUserInput(text: String) {
        if (text.isBlank() || _state.value.isRunning) return
        if (!AgentBootstrap.isReady) {
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
            is AgentEvent.ToolCallStarted -> appendBubble(toolBubble(ev.call, ToolUiState.Running))
            is AgentEvent.ToolCallCompleted -> {
                updateToolBubble(ev.call) { b ->
                    when (val r = ev.result) {
                        is ToolResult.Success -> b.copy(state = ToolUiState.Success, output = r.content)
                        is ToolResult.Failure -> b.copy(state = ToolUiState.Failure, output = r.message)
                    }
                }
                _state.update { it.copy(loadedSkillIds = session.loadedSkillIds.toList()) }
            }
            is AgentEvent.ConfirmationDenied -> appendBubble(
                toolBubble(ev.call, ToolUiState.Denied, output = "已取消"),
            )
            is AgentEvent.LoopFinished -> Unit // 状态在 finally 里处理
            is AgentEvent.LoopError -> _state.update {
                it.copy(error = ev.cause.message ?: ev.cause.javaClass.simpleName)
            }
        }
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

    private inline fun MutableStateFlow<ChatUiState>.update(transform: (ChatUiState) -> ChatUiState) {
        value = transform(value)
    }
}
