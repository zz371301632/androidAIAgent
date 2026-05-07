package com.aiagent.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.aiagent.sdk.voice.Availability

@Composable
internal fun ChatInputBar(
    enabled: Boolean,
    placeholder: String,
    onSend: (String) -> Unit,
    voiceAvailability: Availability? = null,
    voiceRecording: Boolean = false,
    voicePartial: String = "",
    onVoiceStart: () -> Unit = {},
    onVoiceStop: () -> Unit = {},
    onVoiceCancel: () -> Unit = {},
) {
    var text by rememberSaveable { mutableStateOf("") }
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (voiceRecording) {
                RecordingIndicator(partial = voicePartial, modifier = Modifier.weight(1f))
            } else {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(placeholder) },
                    singleLine = true,
                    enabled = enabled,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    modifier = Modifier.weight(1f),
                )
            }
            voiceAvailability?.let {
                MicButton(
                    availability = it,
                    enabled = enabled || voiceRecording,
                    recording = voiceRecording,
                    onPressStart = onVoiceStart,
                    onPressEnd = onVoiceStop,
                    onPressCancel = onVoiceCancel,
                )
            }
            Button(
                enabled = enabled && text.isNotBlank() && !voiceRecording,
                onClick = {
                    val toSend = text.trim()
                    if (toSend.isNotEmpty()) {
                        text = ""
                        onSend(toSend)
                    }
                },
            ) { Text("发送") }
        }
    }
}

/** 录音中替换输入框的视觉条:显示「🎙 正在听:[partial]」。 */
@Composable
private fun RecordingIndicator(partial: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.height(56.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(4.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = if (partial.isBlank()) "🎙 正在听…" else "🎙 $partial",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * push-to-talk 按钮,根据 [availability] 渲染 3 态:
 *  - [Availability.Ready]:正常按住录音,松手发送;
 *  - [Availability.Preparing]:展示进度文字 + 圆形进度,不接受手势;
 *  - [Availability.Unavailable]:首次点击触发 prepare(走 [onPressStart]),不录音 ——
 *    ViewModel 在 [onPressStart] 内根据当前 availability 自动分流。
 * 录音中(`recording = true`)按钮变红、文案改「松开发送」。
 */
@Composable
private fun MicButton(
    availability: Availability,
    enabled: Boolean,
    recording: Boolean,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    onPressCancel: () -> Unit,
) {
    val preparing = availability is Availability.Preparing
    val bg = when {
        recording -> MaterialTheme.colorScheme.error
        preparing -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.secondary
    }
    val gestureEnabled = enabled && !preparing
    Surface(
        color = bg,
        contentColor = if (preparing) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.onSecondary,
        shape = RoundedCornerShape(50),
        modifier = Modifier
            .height(40.dp)
            .pointerInput(gestureEnabled) {
                if (!gestureEnabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        onPressStart()
                        // 仅 Ready 态走 PTT 收尾;Unavailable 态首次点击只触发 prepare,
                        // 此时 ViewModel 不会切到 voiceRecording=true,onPressEnd 也是 no-op
                        val released = tryAwaitRelease()
                        if (released) onPressEnd() else onPressCancel()
                    },
                )
            },
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                recording -> Text("松开发送", style = MaterialTheme.typography.labelMedium)
                availability is Availability.Preparing -> PreparingContent(availability.progress)
                availability is Availability.Unavailable ->
                    Text("🎤 准备模型", style = MaterialTheme.typography.labelMedium)
                else -> Text("🎤 按住说", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** Preparing 态:进度 0..100 显示百分比 + 小圆环;< 0 表示不确定进度。 */
@Composable
private fun PreparingContent(progress: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (progress in 0..100) {
            CircularProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
            Text("$progress%", style = MaterialTheme.typography.labelMedium)
        } else {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("准备中", style = MaterialTheme.typography.labelMedium)
        }
    }
}
