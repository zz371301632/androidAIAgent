package com.zhangz.androidaiagent.demo.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.core.content.ContextCompat
import com.aiagent.sdk.setup.AiAgentRuntime
import com.aiagent.ui.AgentChatScreenHost

/**
 * 演示用聊天 Activity:UI 全部下沉到 `lib_ai_agent_ui` 模块;这里只做主题包裹 +
 * 触发一次 [AiAgentRuntime.skills] 完成 KSP 注册的 Lazy 装机。接入方想完全自定义
 * 样式时,可以直接抄这几行,或换成自家 Theme + 自家 ViewModel。
 *
 * 录音权限:仅当 [AiAgentRuntime.voiceController] 已注入时才发起请求 —— 接入方
 * 没接 voice 实现就不会弹无意义的权限框。用户拒绝也不影响文字输入,UI 层会让 mic
 * 按钮静默不渲染(voiceAvailable=false)。
 */
class AgentChatActivity : ComponentActivity() {

    private val requestRecordAudio = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 拒绝就当没有 voice,不打扰用户 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AiAgentRuntime.skills
        if (AiAgentRuntime.voiceController != null) maybeRequestRecordAudio()
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface { AgentChatScreenHost() }
            }
        }
    }

    private fun maybeRequestRecordAudio() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestRecordAudio.launch(Manifest.permission.RECORD_AUDIO)
    }
}
