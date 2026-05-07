package com.aiagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun BubbleList(
    bubbles: List<ChatBubble>,
    streaming: String,
    isRunning: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(bubbles.size, streaming) {
        val target = bubbles.size + if (streaming.isNotEmpty()) 1 else 0
        if (target > 0) listState.animateScrollToItem(target - 1)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(bubbles, key = { it.id }) { Bubble(it) }
        if (streaming.isNotEmpty()) {
            item("streaming") { AssistantBubble(streaming, streaming = true) }
        } else if (isRunning && bubbles.lastOrNull() !is ChatBubble.ToolCallView) {
            item("thinking") { ThinkingDots() }
        }
        item("tail") { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun Bubble(b: ChatBubble) {
    when (b) {
        is ChatBubble.User -> UserBubble(b.text)
        is ChatBubble.Assistant -> AssistantBubble(b.text, streaming = false)
        is ChatBubble.ToolCallView -> ToolBubble(b)
        is ChatBubble.SubAgentBubble -> SubAgentBubbleView(b)
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 280.dp),
        ) { Text(text, modifier = Modifier.padding(10.dp)) }
    }
}

@Composable
internal fun AssistantBubble(text: String, streaming: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(10.dp)) {
                Text(text)
                if (streaming) {
                    Spacer(Modifier.height(4.dp))
                    Text("…", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ToolBubble(b: ChatBubble.ToolCallView) {
    val (label, color) = when (b.state) {
        ToolUiState.Pending -> "等待确认" to MaterialTheme.colorScheme.tertiary
        ToolUiState.Running -> "执行中" to MaterialTheme.colorScheme.primary
        ToolUiState.Success -> "成功" to MaterialTheme.colorScheme.primary
        ToolUiState.Failure -> "失败" to MaterialTheme.colorScheme.error
        ToolUiState.Denied -> "已取消" to MaterialTheme.colorScheme.outline
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, color),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text("⚙ ${b.argsPreview}", style = MaterialTheme.typography.bodyMedium)
            Text(label, color = color, style = MaterialTheme.typography.labelSmall)
            b.output?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ThinkingDots() {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(end = 8.dp))
        Text("思考中…", style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * 嵌套的子 Agent 委派气泡:头部展示 agent_type / task / 状态,
 * 内部 [ChatBubble.SubAgentBubble.inner] 递归走 [Bubble] 渲染,因此可以呈现任意
 * 层数的 Agent → SubAgent → SubAgent 嵌套调用。
 */
@Composable
private fun SubAgentBubbleView(b: ChatBubble.SubAgentBubble) {
    val (label, color) = when (b.state) {
        ToolUiState.Pending -> "等待" to MaterialTheme.colorScheme.tertiary
        ToolUiState.Running -> "运行中" to MaterialTheme.colorScheme.primary
        ToolUiState.Success -> "完成" to MaterialTheme.colorScheme.primary
        ToolUiState.Failure -> "失败" to MaterialTheme.colorScheme.error
        ToolUiState.Denied -> "已取消" to MaterialTheme.colorScheme.outline
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, color),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text("🤖 子 Agent · ${b.agentType} · depth=${b.depth}", style = MaterialTheme.typography.bodyMedium)
            Text("任务:${b.task}", style = MaterialTheme.typography.bodySmall)
            Text(label, color = color, style = MaterialTheme.typography.labelSmall)
            if (b.inner.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Column(
                    modifier = Modifier.padding(start = 8.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    b.inner.forEach { Bubble(it) }
                }
            } else if (b.state == ToolUiState.Running) {
                Spacer(Modifier.height(4.dp))
                Text("子 Agent 思考中…", style = MaterialTheme.typography.bodySmall)
            }
            b.finalText?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text("最终回执:$it", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
