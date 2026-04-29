package com.aiagent.sdk.llm

/**
 * LlmClient 工厂。当前所有 OpenAI 兼容供应商(官方 SaaS / 自部署网关 / 第三方
 * 中转)都共用 [OpenAiCompatibleClient],差异通过 [LlmProviderProfile] 抹平。
 *
 * 将来如果要接 Anthropic Messages 协议,在这里按 profile.provider 分支
 * 返回新的 LlmClient 实现即可,上层 AgentLoop 完全无感知。
 */
object LlmClientFactory {

    fun create(profile: LlmProviderProfile): LlmClient = OpenAiCompatibleClient(profile)
}
