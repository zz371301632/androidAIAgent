package com.zhangz.androidaiagent

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aiagent.sdk.setup.AiAgentRuntime
import com.zhangz.androidaiagent.demo.ui.AgentChatActivity
import com.zhangz.androidaiagent.ui.theme.AndroidAIAgentTheme

/**
 * Demo 入口页:仅一个按钮跳到 [AgentChatActivity],并显示当前 key 是否配置。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroidAIAgentTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Home(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
private fun Home(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("AI Agent SDK Demo", style = MaterialTheme.typography.headlineSmall)
        Text(
            if (AiAgentRuntime.isReady) "已检测到 DeepSeek key,可直接对话" else "未配置 ai.deepseek.key,请填 local.properties 后重新构建",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = { ctx.startActivity(Intent(ctx, AgentChatActivity::class.java)) }) {
            Text("打开 AI 助手")
        }
    }
}