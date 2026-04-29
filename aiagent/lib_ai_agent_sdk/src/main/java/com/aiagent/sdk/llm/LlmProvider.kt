package com.aiagent.sdk.llm

/**
 * LLM 供应商枚举。新增供应商时往这里加一个分支,
 * 然后在 [LlmProviderProfile] 里给它写一个 factory 函数即可。
 *
 * 协议层面三家目前都走 OpenAI Chat Completions(/v1/chat/completions),
 * 差异点(额外 header / 额外 body 字段 / baseUrl)由 [LlmProviderProfile] 抹平。
 */
enum class LlmProvider(val id: String) {
    /** DeepSeek 官方:https://api.deepseek.com */
    DEEPSEEK_OFFICIAL("deepseek"),

    /** 硅基流动(SiliconFlow):https://api.siliconflow.cn */
    SILICONFLOW("siliconflow"),

    /**
     * 自部署 / 企业内网关:OpenAI 协议 + 调用方按需注入的额外 header / body 字段
     * (例如 `trace_id` 头与配套的 `rid` body 字段)。baseUrl 由调用方运行期填,
     * SDK 不内置任何具体地址。
     */
    CUSTOM_GATEWAY("custom"),
    ;

    companion object {
        /** 从配置读到的字符串映射成枚举,大小写不敏感,未知值回落到默认。 */
        fun fromId(id: String?, fallback: LlmProvider = DEEPSEEK_OFFICIAL): LlmProvider =
            values().firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: fallback
    }
}
