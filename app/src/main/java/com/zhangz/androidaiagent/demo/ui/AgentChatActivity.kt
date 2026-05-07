package com.zhangz.androidaiagent.demo.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.aiagent.sdk.setup.AiAgentRuntime

/**
 * 演示用聊天 Activity:
 *  - 仅做 ViewModel 持有 + Compose host;
 *  - onCreate 里访问一次 [AiAgentRuntime.skills],触发 KSP 注册一次性装机(Lazy)。
 */
class AgentChatActivity : ComponentActivity() {

    private val viewModel: AgentChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AiAgentRuntime.skills
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface { AgentChatScreenHost(viewModel) }
            }
        }
    }
}

@Composable
private fun AgentChatScreenHost(viewModel: AgentChatViewModel) {
    val state by viewModel.state.collectAsState()
    AgentChatScreen(
        state = state,
        onSendInput = viewModel::sendUserInput,
        onConfirm = viewModel::resolveConfirmation,
        onCancel = viewModel::cancel,
    )
}
