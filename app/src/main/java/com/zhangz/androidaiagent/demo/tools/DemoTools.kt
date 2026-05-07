package com.zhangz.androidaiagent.demo.tools

import android.widget.Toast
import com.aiagent.annotations.AiSkill
import com.aiagent.annotations.AiTool
import com.zhangz.androidaiagent.demo.bootstrap.AppContextHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Demo 工具集合 —— 这个文件就是 SDK 接入的「全部业务代码」。
 *
 * 关键点:
 *  - `object` + `suspend` + 仅基础类型参数,KSP 才能在编译期为它生成无样板的注册码;
 *  - `@AiSkill` 把本组工具包成「按需加载」的 skill,模型默认看不到 [showToast] 等
 *    具体工具,只有先调 list_skills + load_skill 加载 demo,才能看到这里的工具。
 *
 * 如果你只想试 SDK,把这个文件 **整体复制** 到自己的 Android 工程里、改改包名/工具
 * 实现就能跑 —— 注解契约部分一行都不用动。
 */
@AiSkill(
    id = "demo",
    name = "演示工具",
    description = "本 demo 自带的玩具工具:展示 Toast、读取设备时间、清空演示历史。",
)
object DemoTools {

    @AiTool(
        description = "向用户弹出一条 Toast 文本提示,适用于展示一句话结果或调试。",
        category = "demo",
    )
    suspend fun showToast(message: String): String = withContext(Dispatchers.Main) {

        Toast.makeText(AppContextHolder.appContext, message, Toast.LENGTH_SHORT).show()
        "toast_shown"
    }

    @AiTool(
        description = "查询设备当前时间,以 ISO-8601 字符串返回(UTC),用于「现在几点」类问答。",
        category = "demo",
    )
    suspend fun deviceTime(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return fmt.format(Date())
    }

    /**
     * 演示 `requiresConfirmation = true`:Agent 派发本工具前会先调 UI 弹窗,
     * 用户点了「确认」才会真正调用。这里只是把动作模拟成另一条 Toast。
     */
    @AiTool(
        description = "清空 demo 聊天历史(模拟动作)。属于演示性「破坏性」操作,需用户确认。",
        category = "demo",
        requiresConfirmation = true,
    )
    suspend fun clearDemoHistory(): String = withContext(Dispatchers.Main) {
        Toast.makeText(AppContextHolder.appContext, "历史已清空(演示)", Toast.LENGTH_SHORT).show()
        "cleared"
    }
}
