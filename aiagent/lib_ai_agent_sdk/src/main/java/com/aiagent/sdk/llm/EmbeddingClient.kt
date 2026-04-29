package com.aiagent.sdk.llm

import com.aiagent.sdk.log.AgentLoggerHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 文本向量化(Embedding)客户端抽象。
 *
 * 用途:把任意文本变成定长向量,便于做语义相似度 / RAG 召回 / 聚类等。
 * 协议层面对齐 OpenAI:POST {baseUrl}/v1/embeddings,响应里
 * `data[i].embedding` 是 N 维 float 数组,顺序与入参 [input] 对应。
 */
interface EmbeddingClient {

    /**
     * 一次性把若干文本批量转成向量。
     *
     * @param model      模型 ID,如 `qwen-3-embedding-8b` / `Qwen_Qwen3-Embedding-8B`
     * @param input      待向量化的文本列表(顺序与返回值一一对应)
     * @param dimensions 可选,部分模型支持运行期指定输出维度;null 用模型默认
     */
    suspend fun embed(
        model: String,
        input: List<String>,
        dimensions: Int? = null,
    ): List<FloatArray>
}

/**
 * OpenAI 兼容的 Embedding 实现。
 *
 * 与 [OpenAiCompatibleClient] 的区别:
 *  - 非流式,一次 HTTP 即拿到全部向量
 *  - **不**调用 [LlmProviderProfile.decorate](那是 chat 专属:enable_thinking
 *    / chat_template_kwargs / rid 都对 embedding 端点没意义)。
 *  - baseUrl / apiKey 复用 profile,模型 ID 按调用传入(embedding 模型与 chat
 *    模型不同,不适合写死在 profile.model)。
 */
class OpenAiCompatibleEmbeddingClient(
    private val profile: LlmProviderProfile,
    private val client: OkHttpClient = defaultClient(),
) : EmbeddingClient {

    override suspend fun embed(
        model: String,
        input: List<String>,
        dimensions: Int?,
    ): List<FloatArray> = withContext(Dispatchers.IO) {
        require(profile.apiKey.isNotBlank()) {
            "AI key 未配置:请检查对应 provider 的 key"
        }
        require(input.isNotEmpty()) { "embedding input 不能为空" }

        val log = AgentLoggerHolder.logger
        val endpoint = buildEndpoint(profile.baseUrl)
        val body = JSONObject()
            .put("model", model)
            .put("input", JSONArray().also { arr -> input.forEach(arr::put) })
        dimensions?.let { body.put("dimensions", it) }

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer ${profile.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        log.req(
            "POST $endpoint provider=${profile.provider.id} model=$model " +
                "inputs=${input.size} dims=${dimensions ?: "default"}"
        )

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string().orEmpty()
                log.respError("embedding HTTP ${resp.code} body=$errBody")
                throw IOException("HTTP ${resp.code}: ${errBody.take(500)}")
            }
            val payload = resp.body?.string().orEmpty()
            parseEmbeddings(payload, expected = input.size)
        }
    }

    /**
     * 解析 OpenAI Embedding 响应:
     *   {"data":[{"index":0,"embedding":[...]}, ...]}
     * 按 `index` 排序,容忍服务端乱序返回。
     */
    private fun parseEmbeddings(payload: String, expected: Int): List<FloatArray> {
        val obj = JSONObject(payload)
        val data = obj.optJSONArray("data")
            ?: throw IOException("embedding response missing data: ${payload.take(200)}")
        val out = arrayOfNulls<FloatArray>(expected)
        for (i in 0 until data.length()) {
            val item = data.getJSONObject(i)
            val idx = item.optInt("index", i)
            val emb = item.optJSONArray("embedding")
                ?: throw IOException("embedding[$idx] missing embedding array")
            val vec = FloatArray(emb.length()) { j -> emb.getDouble(j).toFloat() }
            if (idx in 0 until expected) out[idx] = vec
        }
        return out.mapIndexed { i, v ->
            v ?: throw IOException("embedding response missing index=$i (got ${data.length()}/$expected)")
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** 与 chat completions 同样的 baseUrl 拼接策略,只是后缀换成 /embeddings。 */
        internal fun buildEndpoint(baseUrl: String): String {
            val trimmed = baseUrl.trimEnd('/')
            val withV1 = if (trimmed.endsWith("/v1")) trimmed else "$trimmed/v1"
            return "$withV1/embeddings"
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
