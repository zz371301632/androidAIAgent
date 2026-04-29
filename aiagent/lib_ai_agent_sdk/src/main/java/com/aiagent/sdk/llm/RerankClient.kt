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
 * Rerank(重排序)客户端抽象。
 *
 * 用途:给定 query + 一堆候选 documents,模型按相关性打分并排序。常和
 * [EmbeddingClient] 配合做 RAG:embedding 召回 top-50 → rerank 精排出 top-5。
 *
 * 协议:POST {baseUrl}/v1/rerank,响应 `results` 数组,每项 {index, relevance_score}。
 */
interface RerankClient {

    /**
     * 一次性给 [documents] 全部打分并返回**已按相关性降序排好**的结果。
     *
     * @param model     模型 ID,如 `bge-rerank`
     * @param query     查询语句
     * @param documents 候选文档列表
     * @param topN      可选,只返回最相关的前 N 条;null 返回全部
     * @return          按 relevance_score 降序的结果列表
     */
    suspend fun rerank(
        model: String,
        query: String,
        documents: List<String>,
        topN: Int? = null,
    ): List<RerankResult>
}

/** 单条重排结果。 */
data class RerankResult(
    /** 在传入 documents 列表中的下标(从 0 起)。 */
    val index: Int,
    /** 模型给出的相关性分数,越大越相关。具体范围依模型而定(常见 0~1 或任意实数)。 */
    val score: Float,
    /** 原文档字符串(便于直接拿,免得调用方再按 index 回查)。 */
    val document: String,
)

/**
 * OpenAI 兼容的 Rerank 实现。
 *
 * 与 [OpenAiCompatibleEmbeddingClient] 同样不调用 [LlmProviderProfile.decorate]
 * (那是 chat 专属字段),只复用 baseUrl / apiKey。模型 ID 按调用传入。
 */
class OpenAiCompatibleRerankClient(
    private val profile: LlmProviderProfile,
    private val client: OkHttpClient = defaultClient(),
) : RerankClient {

    override suspend fun rerank(
        model: String,
        query: String,
        documents: List<String>,
        topN: Int?,
    ): List<RerankResult> = withContext(Dispatchers.IO) {
        require(profile.apiKey.isNotBlank()) {
            "AI key 未配置:请检查对应 provider 的 key"
        }
        require(documents.isNotEmpty()) { "rerank documents 不能为空" }

        val log = AgentLoggerHolder.logger
        val endpoint = buildEndpoint(profile.baseUrl)
        val body = JSONObject()
            .put("model", model)
            .put("query", query)
            .put("documents", JSONArray().also { arr -> documents.forEach(arr::put) })
        topN?.let { body.put("top_n", it) }

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer ${profile.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        log.req(
            "POST $endpoint provider=${profile.provider.id} model=$model " +
                "docs=${documents.size} topN=${topN ?: "all"}"
        )

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string().orEmpty()
                log.respError("rerank HTTP ${resp.code} body=$errBody")
                throw IOException("HTTP ${resp.code}: ${errBody.take(500)}")
            }
            val payload = resp.body?.string().orEmpty()
            parseResults(payload, documents)
        }
    }

    /**
     * 解析响应。考虑两种常见格式:
     *  - OpenAI 风格(jina / cohere / 多数网关):`{"results":[{"index":i,"relevance_score":s}]}`
     *  - 也可能字段名是 `score`,做 fallback。
     * 返回前按 score 降序排,index 越界条目跳过。
     */
    private fun parseResults(payload: String, documents: List<String>): List<RerankResult> {
        val obj = JSONObject(payload)
        val arr = obj.optJSONArray("results")
            ?: throw IOException("rerank response missing results: ${payload.take(200)}")
        val out = ArrayList<RerankResult>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val idx = item.optInt("index", -1)
            if (idx !in documents.indices) continue
            val score = when {
                item.has("relevance_score") -> item.getDouble("relevance_score").toFloat()
                item.has("score") -> item.getDouble("score").toFloat()
                else -> continue
            }
            out += RerankResult(index = idx, score = score, document = documents[idx])
        }
        out.sortByDescending { it.score }
        return out
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** 与 chat completions 同样的 baseUrl 拼接策略,只是后缀换成 /rerank。 */
        internal fun buildEndpoint(baseUrl: String): String {
            val trimmed = baseUrl.trimEnd('/')
            val withV1 = if (trimmed.endsWith("/v1")) trimmed else "$trimmed/v1"
            return "$withV1/rerank"
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
