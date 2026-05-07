package com.zhangz.androidaiagent.demo.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.aiagent.sdk.setup.AiAgentRuntime
import com.aiagent.ui.AgentChatScreenHost

/**
 * 演示用聊天 Activity:UI 全部下沉到 `lib_ai_agent_ui` 模块;这里只做主题包裹 +
 * 触发一次 [AiAgentRuntime.skills] 完成 KSP 注册的 Lazy 装机。接入方想完全自定义
 * 样式时,可以直接抄这 5 行,或换成自家 Theme + 自家 ViewModel。
 */
class AgentChatActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AiAgentRuntime.skills
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface { AgentChatScreenHost() }
            }
        }
    }
}
