package com.zhangz.androidaiagent

import android.app.Application
import com.aiagent.generated.bootAiTools_app
import com.aiagent.sdk.agent.SubAgentPreset
import com.aiagent.sdk.llm.LlmProvider
import com.aiagent.sdk.llm.LlmProviderProfile
import com.aiagent.sdk.memory.MemoryChunk
import com.aiagent.sdk.memory.StaticMemory
import com.aiagent.sdk.setup.AiAgentConfig
import com.aiagent.sdk.setup.AiAgentRuntime
import com.zhangz.androidaiagent.demo.bootstrap.AppContextHolder
import com.zhangz.androidaiagent.demo.bootstrap.LogcatAgentLogger

/**
 * Demo Application:**接入 SDK 的全部决策都集中在本文件**的 [AiAgentConfig] 里。
 *
 * 想换 LLM provider / 换 logger / 改 memory / 加减 subAgent / 改 persona,只看下面
 * 这一段即可,其它文件一行不用动 —— 这就是 [AiAgentRuntime] 改造的核心目的。
 *
 * 业务自家工程接入时:把 `bootAiTools_app` 换成自家模块的 `bootAiTools_<bootName>`,
 * 多模块按需追加;`profile` 直接传一个 [LlmProviderProfile] 写死即可,demo 这里多了
 * 一层 [profileFromBuildConfig] 兜底是为了让人「不改代码也能切 key」,生产上可以删掉。
 */
class DemoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContextHolder.application = this
        AiAgentRuntime.install(
            AiAgentConfig(
                kspBootstraps = listOf(::bootAiTools_app),
                persona = DEMO_PERSONA,
                profile = profileFromBuildConfig(),
                logger = LogcatAgentLogger,
                memory = StaticMemory(
                    listOf(
                        MemoryChunk(
                            text = "用户所在时区 Asia/Shanghai,日期格式偏好 yyyy-MM-dd。",
                            source = "user_profile",
                        ),
                        MemoryChunk(
                            text = "用户默认开发语言是 Kotlin,Android 平台,minSdk 24。",
                            source = "user_profile",
                        ),
                        MemoryChunk(
                            text = "回答中涉及代码时优先给出可直接复制的最小可运行片段,避免长篇铺垫。",
                            source = "preference",
                        ),
                    )
                ),
                subAgentPresets = listOf(
                    SubAgentPreset(
                        id = "researcher",
                        displayName = "研究员",
                        description = "擅长信息整理、事实查证、给出有依据的简短结论。" +
                            "需要查时间 / 对比方案 / 总结文本时把子任务派给我。",
                        persona = "你是一名细致严谨的研究员。回答时优先调用可用工具拿事实," +
                            "无法解决再说明限制。结论控制在 200 字以内,不展开闲聊,不输出代码。",
                        maxRounds = 4,
                    ),
                    SubAgentPreset(
                        id = "coder",
                        displayName = "程序员",
                        description = "擅长写代码片段、修小 bug、解释技术概念。" +
                            "需要产出代码或技术解释时把子任务派给我。",
                        persona = "你是一名专业程序员。优先给出可直接复制的代码块,再用一两句话说明思路。" +
                            "默认 Kotlin,除非用户指定别的语言。单次实现不超过 50 行。",
                        maxRounds = 4,
                    ),
                ),
            ),
        )
    }

    /**
     * Demo 的 BuildConfig 兜底:从 local.properties 读出来的 ai.* 字段拼成 profile;
     * key 为空返回 null,UI / Headless 入口看到 [AiAgentRuntime.isReady] 为 false 会拒发
     * 请求并提示「未配置 key」。业务接入大多直接显式传 profile,这一段可以整段删除。
     */
    private fun profileFromBuildConfig(): LlmProviderProfile? =
        when (LlmProvider.fromId(BuildConfig.AI_PROVIDER)) {
            LlmProvider.DEEPSEEK_OFFICIAL -> if (BuildConfig.AI_DEEPSEEK_KEY.isBlank()) null
            else LlmProviderProfile.deepSeekOfficial(
                baseUrl = BuildConfig.AI_DEEPSEEK_BASE_URL,
                apiKey = BuildConfig.AI_DEEPSEEK_KEY,
                model = BuildConfig.AI_DEEPSEEK_MODEL,
            )
            LlmProvider.CUSTOM_GATEWAY -> if (BuildConfig.AI_GATEWAY_KEY.isBlank()) null
            else LlmProviderProfile.customGateway(
                baseUrl = BuildConfig.AI_GATEWAY_BASE_URL,
                apiKey = BuildConfig.AI_GATEWAY_KEY,
                model = BuildConfig.AI_GATEWAY_MODEL,
            )
            LlmProvider.SILICONFLOW -> null
        }
}

/**
 * Demo 自家的主 Agent persona。SDK 不再提供任何业务向回退 —— 「我的 Agent 是谁」属于
 * 接入方决策。改 persona 只在这一处改即可,其它文件零改动。
 */
private val DEMO_PERSONA: String = """
    你是一个运行在终端 App 内的智能助手。
    - 优先使用工具完成请求,不要凭空回答。
    - 涉及破坏性 / 不可逆操作时,必须先简要解释,得到用户确认后再调用工具。
    - 工具失败时给出可读的中文错误提示并建议下一步。
    - 默认你只能看到 list_skills / load_skill 两个工具;
      业务能力被封装成 skill,需要时先 list_skills 浏览,再 load_skill 加载,
      然后才能调用其下的工具。
""".trimIndent()
