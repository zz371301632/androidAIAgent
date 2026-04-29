package com.aiagent.sdk.llm

import okhttp3.Request
import org.json.JSONObject
import java.util.UUID

/**
 * 单个供应商的运行期配置 + 「请求装饰器」。
 *
 * OpenAI 兼容供应商之间的差异都收敛在这里:
 *  - baseUrl / apiKey / model / maxTokens / responseFormat 走数据字段
 *  - 额外 header(例如某些网关要求的 `trace_id`)、额外 body 字段
 *    (例如配套的 `rid` / `enable_thinking` / `chat_template_kwargs`)走 [decorate]
 *
 * [decorate] 在每次发请求前调用一次。**同一次调用里 header 和 body 共享同一个
 * UUID**(部分网关要求 trace_id 头与 body.rid 必须完全一致,不一致会 4xx)。
 */
data class LlmProviderProfile(
    val provider: LlmProvider,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    /**
     * 单次响应的 token 上限。null 表示交给服务端默认;显式传值通常更稳,
     * 默认 4096 对齐多数 OpenAI 兼容网关的文档示例。
     */
    val maxTokens: Int? = 4096,
    /**
     * 可选的 OpenAI `response_format` 字段(完整对象,例如
     * `{"type":"json_schema","json_schema":{...}}`)。null 表示不强制结构化输出。
     * 与 `tools` 不冲突,但通常二选一。
     */
    val responseFormat: JSONObject? = null,
    /**
     * 每次请求前调一次。给定可写的 OkHttp Request.Builder 与 body JSONObject,
     * 实现方可任意往里塞 header / 字段;默认实现不做任何事。
     */
    val decorate: (Request.Builder, JSONObject) -> Unit = { _, _ -> },
) {

    companion object {

        /** DeepSeek 官方,OpenAI 协议直发,无额外字段。 */
        fun deepSeekOfficial(
            baseUrl: String,
            apiKey: String,
            model: String,
            maxTokens: Int? = 4096,
            responseFormat: JSONObject? = null,
        ) = LlmProviderProfile(
            provider = LlmProvider.DEEPSEEK_OFFICIAL,
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            maxTokens = maxTokens,
            responseFormat = responseFormat,
        )

        /** SiliconFlow,OpenAI 协议直发,无额外字段。 */
        fun siliconFlow(
            baseUrl: String,
            apiKey: String,
            model: String,
            maxTokens: Int? = 4096,
            responseFormat: JSONObject? = null,
        ) = LlmProviderProfile(
            provider = LlmProvider.SILICONFLOW,
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            maxTokens = maxTokens,
            responseFormat = responseFormat,
        )

        /**
         * 自定义企业网关 / 自部署网关的常见形态。一个开箱即用的 [decorate]:
         *  1. 每次请求生成随机 UUID,放进 `trace_id` 头;
         *  2. body 里写一个 `rid` 字段,值与 trace_id 头相同(很多网关强校验);
         *  3. body 显式关思考(双保险):
         *       enable_thinking=false 与 chat_template_kwargs.enable_thinking=false。
         *
         * 兜底:个别模型即便关了 thinking 仍会在 content 里串 `<think>…</think>`,
         * 由 [ThinkTagFilter] 在客户端层剥离,不在本 profile 处理。
         *
         * 如果你的网关不需要这些字段,直接用顶层构造器自己写 [decorate] 即可。
         */
        fun customGateway(
            baseUrl: String,
            apiKey: String,
            model: String,
            maxTokens: Int? = 4096,
            responseFormat: JSONObject? = null,
        ) = LlmProviderProfile(
            provider = LlmProvider.CUSTOM_GATEWAY,
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            maxTokens = maxTokens,
            responseFormat = responseFormat,
            decorate = { reqBuilder, body ->
                val traceId = UUID.randomUUID().toString()
                reqBuilder.addHeader("trace_id", traceId)
                body.put("rid", traceId)
                body.put("enable_thinking", false)
                body.put(
                    "chat_template_kwargs",
                    JSONObject().put("enable_thinking", false),
                )
            },
        )
    }
}
