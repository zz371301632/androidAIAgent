package com.aiagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aiagent.runtime.Tool
import kotlinx.coroutines.launch

/**
 * 无参便捷入口:接入方在 `setContent { MaterialTheme { Surface { AgentChatScreenHost() } } }`
 * 即可拿到一个完整的聊天页面。内部用 [viewModel] 拿默认的 [AgentChatViewModel],适合
 * 不想自己写 ViewModel / 不想定制行为的场景。
 */
@Composable
fun AgentChatScreenHost(viewModel: AgentChatViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    AgentChatScreen(
        state = state,
        onSendInput = viewModel::sendUserInput,
        onConfirm = viewModel::resolveConfirmation,
        onCancel = viewModel::cancel,
        onQuickTool = viewModel::runQuickTool,
        onVoiceStart = viewModel::startVoice,
        onVoiceStop = viewModel::stopVoice,
        onVoiceCancel = viewModel::cancelVoice,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentChatScreen(
    state: ChatUiState,
    onSendInput: (String) -> Unit,
    onConfirm: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onQuickTool: (Tool) -> Unit,
    onVoiceStart: () -> Unit = {},
    onVoiceStop: () -> Unit = {},
    onVoiceCancel: () -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.error) {
        state.error?.let { scope.launch { snackbar.showSnackbar(it) } }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Agent Demo") },
                actions = {
                    if (state.isRunning) {
                        TextButton(onClick = onCancel) { Text("停止") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column {
                QuickToolBar(
                    tools = state.quickTools,
                    enabled = !state.isRunning && state.configured,
                    onClick = onQuickTool,
                )
                ChatInputBar(
                    enabled = !state.isRunning && state.configured,
                    placeholder = if (state.configured) "和 AI 说点什么…" else "未配置 ai.deepseek.key",
                    onSend = onSendInput,
                    voiceAvailability = state.voiceAvailability,
                    voiceRecording = state.voiceRecording,
                    voicePartial = state.voicePartial,
                    onVoiceStart = onVoiceStart,
                    onVoiceStop = onVoiceStop,
                    onVoiceCancel = onVoiceCancel,
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LoadedSkillStrip(state.loadedSkillIds)
            HorizontalDivider()
            BubbleList(
                bubbles = state.bubbles,
                streaming = state.streamingText,
                isRunning = state.isRunning,
                modifier = Modifier.weight(1f),
            )
        }
        state.pending?.let { p ->
            AlertDialog(
                onDismissRequest = { onConfirm(false) },
                title = { Text("确认执行高危操作") },
                text = {
                    Column {
                        Text("即将调用工具: ${p.tool.name}")
                        Spacer(Modifier.height(8.dp))
                        Text(p.tool.description, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Text("参数: ${p.args}")
                    }
                },
                confirmButton = { TextButton(onClick = { onConfirm(true) }) { Text("确认") } },
                dismissButton = { TextButton(onClick = { onConfirm(false) }) { Text("取消") } },
            )
        }
    }
}

/** 只读条:展示当前会话已加载的 skill,加载由模型驱动,UI 不可改。 */
@Composable
private fun LoadedSkillStrip(loadedIds: List<String>) {
    if (loadedIds.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("已加载:", style = MaterialTheme.typography.labelSmall)
        loadedIds.forEach { id ->
            Text(
                "·$id",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
