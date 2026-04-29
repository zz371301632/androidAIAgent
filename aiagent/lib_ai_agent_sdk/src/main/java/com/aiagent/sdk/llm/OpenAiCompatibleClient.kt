package com.aiagent.sdk.llm

import com.aiagent.runtime.ToolSchema
import com.aiagent.sdk.log.AgentLoggerHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenAI Chat Completions 协议的通用客户端。
 *
 * 协议要点:
 *  - POST {baseUrl}/v1/chat/completions
 *  - Authorization: Bearer {key}
 *  - Body: {model, messages, tools?, tool_choice?, stream:true}
 *  - Response: text/event-stream,多行 "data: {json}",结束行 "data: [DONE]"
 *
 * 供应商差异通过 [LlmProviderProfile.decorate] 注入(例如某些网关需要的
 * trace_id / rid / enable_thinking),本类自身不感知具体供应商。
 */
class OpenAiCompatibleClient(
    private val profile: LlmProviderProfile,
    private val client: OkHttpClient = defaultClient(),
) : LlmClient {

    override fun chatStream(
        messages: List<Message>,
        tools: List<ToolSchema>?,
        toolChoice: String,
    ): Flow<LlmStreamEvent> = flow {
        require(profile.apiKey.isNotBlank()) {
            "AI key 未配置:请检查对应 provider 的 key"
        }

        val log = AgentLoggerHolder.logger
        val endpoint = buildEndpoint(profile.baseUrl)
        val body = buildRequestBody(messages, tools, toolChoice)
        val builder = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer ${profile.apiKey}")
            .addHeader("Accept", "text/event-stream")

        profile.decorate(builder, body)

        log.req(
            "POST $endpoint provider=${profile.provider.id} model=${profile.model} " +
                "messages=${messages.size} tools=${tools?.size ?: 0} toolChoice=$toolChoice"
        )
        log.req("body=${body}")

        val request = builder
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        val call = client.newCall(request)
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string().orEmpty()
                    log.resp("HTTP ${resp.code} fail body=$errBody")
                    throw IOException("HTTP ${resp.code}: ${errBody.take(500)}")
                }
                val source = resp.body?.source() ?: throw IOException("empty response body")
                var finishReason: String? = null
                var contentLen = 0
                var reasoningLen = 0
                var toolDeltas = 0
                val thinkFilter = ThinkTagFilter()

                for (payload in SseParser.parse(source)) {
                    if (payload == "[DONE]") break
                    val obj = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                    val choices = obj.optJSONArray("choices") ?: continue
                    if (choices.length() == 0) continue
                    val choice = choices.getJSONObject(0)
                    val fr = choice.optString("finish_reason", "")
                    if (fr.isNotBlank() && fr != "null") finishReason = fr
                    val delta = choice.optJSONObject("delta") ?: continue
                    val (cLen, rLen, tDeltas) = emitDelta(delta, thinkFilter)
                    contentLen += cLen
                    reasoningLen += rLen
                    toolDeltas += tDeltas
                }
                val tail = thinkFilter.flush()
                if (tail.visible.isNotEmpty()) {
                    contentLen += tail.visible.length
                    emit(LlmStreamEvent.ContentDelta(tail.visible))
                }
                if (tail.hidden.isNotEmpty()) {
                    reasoningLen += tail.hidden.length
                    emit(LlmStreamEvent.ReasoningDelta(tail.hidden))
                }
                log.resp(
                    "DONE finish=$finishReason contentLen=$contentLen " +
                        "reasoningLen=$reasoningLen toolDeltas=$toolDeltas"
                )
                emit(LlmStreamEvent.Done(finishReason))
            }
        } catch (ce: CancellationException) {
            log.resp("cancelled")
            call.cancel()
            throw ce
        } catch (t: Throwable) {
            log.respError("error ${t.javaClass.simpleName}: ${t.message}", t)
            emit(LlmStreamEvent.Error(t))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 解析单条 delta,按需分别 emit ReasoningDelta / ContentDelta / ToolCallDelta。
     * 返回三元组 (content 字符数, reasoning 字符数, tool_call 增量数) 用于汇总日志。
     */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<LlmStreamEvent>.emitDelta(
        delta: JSONObject,
        thinkFilter: ThinkTagFilter,
    ): Triple<Int, Int, Int> {
        val log = AgentLoggerHolder.logger
        var reasoningEmittedLen = 0
        var contentEmittedLen = 0

        val reasoning = readNullableString(delta, "reasoning_content")
        if (reasoning.isNotEmpty()) {
            log.resp("delta reasoning=$reasoning")
            reasoningEmittedLen += reasoning.length
            emit(LlmStreamEvent.ReasoningDelta(reasoning))
        }

        val rawContent = readNullableString(delta, "content")
        if (rawContent.isNotEmpty()) {
            log.resp("delta content=$rawContent")
            val res = thinkFilter.feed(rawContent)
            if (res.visible.isNotEmpty()) {
                contentEmittedLen = res.visible.length
                emit(LlmStreamEvent.ContentDelta(res.visible))
            }
            if (res.hidden.isNotEmpty()) {
                reasoningEmittedLen += res.hidden.length
                emit(LlmStreamEvent.ReasoningDelta(res.hidden))
            }
        }

        val toolArr = delta.optJSONArray("tool_calls")
        var toolCount = 0
        if (toolArr != null) {
            for (i in 0 until toolArr.length()) {
                val tc = toolArr.getJSONObject(i)
                val index = tc.optInt("index", i)
                val id = readNullableString(tc, "id").takeIf { it.isNotEmpty() }
                val func = tc.optJSONObject("function")
                val name = func?.let { readNullableString(it, "name") }?.takeIf { it.isNotEmpty() }
                val args = func?.let { readNullableString(it, "arguments") }
                log.resp("delta tool_call index=$index id=$id name=$name args=$args")
                emit(LlmStreamEvent.ToolCallDelta(index, id, name, args))
                toolCount++
            }
        }
        return Triple(contentEmittedLen, reasoningEmittedLen, toolCount)
    }

    private fun readNullableString(obj: JSONObject, key: String): String =
        if (obj.isNull(key)) "" else obj.optString(key, "")

    private fun buildRequestBody(
        messages: List<Message>,
        tools: List<ToolSchema>?,
        toolChoice: String,
    ): JSONObject {
        val msgArr = JSONArray()
        messages.forEach { msgArr.put(it.toJson()) }
        val obj = JSONObject()
            .put("model", profile.model)
            .put("messages", msgArr)
            .put("stream", true)
        profile.maxTokens?.let { obj.put("max_tokens", it) }
        profile.responseFormat?.let { obj.put("response_format", it) }
        if (!tools.isNullOrEmpty()) {
            val toolArr = JSONArray()
            tools.forEach { toolArr.put(it.toJson()) }
            obj.put("tools", toolArr).put("tool_choice", toolChoice)
        }
        return obj
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /**
         * 把用户配置的 baseUrl 拼成 chat/completions 端点。允许两种写法:
         *  - https://api.example.com                -> 自动补 /v1/chat/completions
         *  - https://api.example.com/v1             -> 已经带 /v1,只补 /chat/completions
         */
        internal fun buildEndpoint(baseUrl: String): String {
            val trimmed = baseUrl.trimEnd('/')
            val withV1 = if (trimmed.endsWith("/v1")) trimmed else "$trimmed/v1"
            return "$withV1/chat/completions"
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
