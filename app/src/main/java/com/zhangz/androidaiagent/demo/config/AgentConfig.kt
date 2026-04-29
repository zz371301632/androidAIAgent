package com.zhangz.androidaiagent.demo.config

import com.aiagent.sdk.llm.LlmProvider
import com.aiagent.sdk.llm.LlmProviderProfile
import com.zhangz.androidaiagent.BuildConfig

/**
 * Demo 全局配置。两层取值,**[profileOverride] > BuildConfig**:
 *
 *  1. **代码注入**:`AgentConfig.profileOverride = LlmProviderProfile(...)`
 *     在 [com.zhangz.androidaiagent.DemoApp.onCreate] 里设。任何 SDK 自带或自定义
 *     的 profile 都接受;**必须在第一次访问 [AgentBootstrap.llmClient] 之前设置**,
 *     否则 lazy 单例已经构造,不会重读。
 *
 *  2. **local.properties** → BuildConfig 注入(不进 git):
 *     ```
 *     ai.provider=deepseek | custom         # 选哪家,默认 deepseek
 *     ai.deepseek.key / baseUrl / model
 *     ai.gateway.key  / baseUrl / model     # 自部署 / 企业内 OpenAI 兼容网关,
 *                                            # SDK 会自动加 trace_id 头 + rid 字段
 *     ```
 */
object AgentConfig {

    /**
     * 代码层手动注入的 profile,优先级最高。想接 SDK 未内置的供应商 / 网关,
     * 或临时切 key,直接 set 这里。
     */
    @Volatile
    var profileOverride: LlmProviderProfile? = null

    /** 当前选中的 provider;profileOverride 设置时不读这个。 */
    val activeProvider: LlmProvider
        get() = LlmProvider.fromId(BuildConfig.AI_PROVIDER)

    /** 是否已配置 key。未配置时 UI 层应给出引导,而不是直接发请求。 */
    val isConfigured: Boolean
        get() = profileOverride != null || activeKey().isNotBlank()

    fun activeProfile(): LlmProviderProfile = profileOverride ?: when (activeProvider) {
        LlmProvider.DEEPSEEK_OFFICIAL -> LlmProviderProfile.deepSeekOfficial(
            baseUrl = BuildConfig.AI_DEEPSEEK_BASE_URL,
            apiKey = BuildConfig.AI_DEEPSEEK_KEY,
            model = BuildConfig.AI_DEEPSEEK_MODEL,
        )
        LlmProvider.CUSTOM_GATEWAY -> LlmProviderProfile.customGateway(
            baseUrl = BuildConfig.AI_GATEWAY_BASE_URL,
            apiKey = BuildConfig.AI_GATEWAY_KEY,
            model = BuildConfig.AI_GATEWAY_MODEL,
        )
        LlmProvider.SILICONFLOW -> error(
            "SiliconFlow 未在 demo 中接线;在 local.properties 里走 deepseek/custom,或" +
                "在 DemoApp 里设 AgentConfig.profileOverride = LlmProviderProfile.siliconFlow(...)"
        )
    }

    private fun activeKey(): String = when (activeProvider) {
        LlmProvider.DEEPSEEK_OFFICIAL -> BuildConfig.AI_DEEPSEEK_KEY
        LlmProvider.CUSTOM_GATEWAY -> BuildConfig.AI_GATEWAY_KEY
        LlmProvider.SILICONFLOW -> ""
    }
}
