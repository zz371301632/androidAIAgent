package com.aiagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aiagent.runtime.Tool

/**
 * 输入框上方的横向快捷工具栏。点击 chip 直接触发 [onClick],绕过 LLM。
 * 仅展示无参工具(由 ViewModel 过滤后传入);列表为空则不渲染,避免占空高度。
 */
@Composable
internal fun QuickToolBar(
    tools: List<Tool>,
    enabled: Boolean,
    onClick: (Tool) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tools.isEmpty()) return
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(tools, key = { it.name }) { tool ->
            AssistChip(
                onClick = { if (enabled) onClick(tool) },
                enabled = enabled,
                label = { Text(tool.nameCN.ifBlank { tool.name }) },
                colors = AssistChipDefaults.assistChipColors(),
            )
        }
    }
}
