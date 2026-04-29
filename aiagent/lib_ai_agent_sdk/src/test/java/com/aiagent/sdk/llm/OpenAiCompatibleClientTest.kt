package com.aiagent.sdk.llm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 用 MockWebServer 验证 [OpenAiCompatibleClient] 在不同 [LlmProviderProfile] 下
 * 实际发出的 HTTP 报文。重点是 customGateway profile 的 trace_id 头 + rid body + 思考关闭。
 */
class OpenAiCompatibleClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun sseBody(vararg payloads: String): String =
        payloads.joinToString(separator = "\n\n", postfix = "\n\n") { "data: $it" } + "data: [DONE]\n\n"

    @Test
    fun deepSeekProfile_sendsVanillaOpenAiBody() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody("""{"choices":[{"delta":{"content":"hi"},"finish_reason":"stop"}]}""")),
        )
        val profile = LlmProviderProfile.deepSeekOfficial(
            baseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "sk-test",
            model = "deepseek-chat",
        )
        val client = OpenAiCompatibleClient(profile)

        val events = client.chatStream(listOf(Message.User("hi"))).toList()

        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer sk-test", req.getHeader("Authorization"))
        assertNull("DeepSeek 官方 profile 不应带 trace_id 头", req.getHeader("trace_id"))

        val body = JSONObject(req.body.readUtf8())
        assertEquals("deepseek-chat", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        assertNull("DeepSeek 官方 profile 不应带 rid", body.opt("rid"))
        assertNull("DeepSeek 官方 profile 不应带 enable_thinking", body.opt("enable_thinking"))

        assertTrue("应解析出 ContentDelta", events.any { it is LlmStreamEvent.ContentDelta })
        assertTrue("应以 Done 结束", events.last() is LlmStreamEvent.Done)
    }

    @Test
    fun customGatewayProfile_addsTraceIdAndRidAndDisablesThinking() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""")),
        )
        val profile = LlmProviderProfile.customGateway(
            baseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "sk-gateway",
            model = "deepseek-reasoner",
        )
        OpenAiCompatibleClient(profile).chatStream(listOf(Message.User("ping"))).toList()

        val req = server.takeRequest()
        val traceId = req.getHeader("trace_id")
        assertNotNull("customGateway profile 必须带 trace_id 头", traceId)
        assertTrue("trace_id 应为 UUID", traceId!!.matches(Regex("[0-9a-f-]{36}")))
        assertEquals("Bearer sk-gateway", req.getHeader("Authorization"))

        val body = JSONObject(req.body.readUtf8())
        assertEquals("deepseek-reasoner", body.getString("model"))
        assertEquals("rid 必须与 trace_id 头一致", traceId, body.getString("rid"))
        assertEquals(false, body.getBoolean("enable_thinking"))
        assertEquals(false, body.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"))
    }

    @Test
    fun customGatewayProfile_eachCallGetsFreshUuid() = runTest {
        repeat(2) {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(sseBody("""{"choices":[{"delta":{"content":""},"finish_reason":"stop"}]}""")),
            )
        }
        val profile = LlmProviderProfile.customGateway(
            baseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "sk-gateway",
            model = "deepseek-reasoner",
        )
        val client = OpenAiCompatibleClient(profile)
        client.chatStream(listOf(Message.User("a"))).toList()
        client.chatStream(listOf(Message.User("b"))).toList()

        val first = server.takeRequest().getHeader("trace_id")
        val second = server.takeRequest().getHeader("trace_id")
        assertNotNull(first); assertNotNull(second)
        assertTrue("两次请求 trace_id 不应相同", first != second)
    }

    @Test
    fun jsonNullContent_isNotStringifiedToLiteralNull() = runTest {
        // 复刻线上 bug:DeepSeek 流尾会出现 {"delta":{"content":null}},
        // 早期实现走 optString 直接拼出字面量 "null",UI 上能看到尾巴多 "null"。
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    sseBody(
                        """{"choices":[{"delta":{"content":"hi"}}]}""",
                        """{"choices":[{"delta":{"content":null}}]}""",
                        """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
                    ),
                ),
        )
        val profile = LlmProviderProfile.deepSeekOfficial(
            baseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "sk-test",
            model = "deepseek-chat",
        )
        val events = OpenAiCompatibleClient(profile).chatStream(listOf(Message.User("x"))).toList()

        val text = events.filterIsInstance<LlmStreamEvent.ContentDelta>()
            .joinToString("") { it.text }
        assertEquals("hi", text)
        assertTrue(events.none { it is LlmStreamEvent.ContentDelta && it.text.contains("null") })
    }

    @Test
    fun thinkTagsInsideContent_areStrippedAndForwardedAsReasoning() = runTest {
        // 自定义网关上的 deepseek-reasoner 即便关了 thinking,仍会把 <think>...</think>
        // 拼进 content。客户端应当过滤掉对外的 ContentDelta,但仍以日志/事件形式可见。
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    sseBody(
                        """{"choices":[{"delta":{"content":"<think>"}}]}""",
                        """{"choices":[{"delta":{"content":"reasoning..."}}]}""",
                        """{"choices":[{"delta":{"content":"</think>"}}]}""",
                        """{"choices":[{"delta":{"content":"hello"}},{"finish_reason":"stop"}]}""",
                        """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
                    ),
                ),
        )
        val profile = LlmProviderProfile.customGateway(
            baseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "sk-gateway",
            model = "deepseek-reasoner",
        )
        val events = OpenAiCompatibleClient(profile).chatStream(listOf(Message.User("hi"))).toList()

        val visible = events.filterIsInstance<LlmStreamEvent.ContentDelta>()
            .joinToString("") { it.text }
        val reasoning = events.filterIsInstance<LlmStreamEvent.ReasoningDelta>()
            .joinToString("") { it.text }
        assertEquals("hello", visible)
        assertEquals("reasoning...", reasoning)
    }

    @Test
    fun reasoningContentField_emittedAsReasoningDelta() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    sseBody(
                        """{"choices":[{"delta":{"reasoning_content":"thinking..."}}]}""",
                        """{"choices":[{"delta":{"content":"answer"}}]}""",
                        """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
                    ),
                ),
        )
        val profile = LlmProviderProfile.deepSeekOfficial(
            baseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "sk-test",
            model = "deepseek-reasoner",
        )
        val events = OpenAiCompatibleClient(profile).chatStream(listOf(Message.User("x"))).toList()

        val reasoning = events.filterIsInstance<LlmStreamEvent.ReasoningDelta>()
            .joinToString("") { it.text }
        val visible = events.filterIsInstance<LlmStreamEvent.ContentDelta>()
            .joinToString("") { it.text }
        assertEquals("thinking...", reasoning)
        assertEquals("answer", visible)
    }

    @Test
    fun defaultMaxTokens_isSentInBody() = runTest {
        // 文档示例里 max_tokens 都是显式传的(SDK 默认 4096),我们对齐这一行为。
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody("""{"choices":[{"delta":{"content":"x"},"finish_reason":"stop"}]}""")),
        )
        val profile = LlmProviderProfile.customGateway(
            baseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "sk-gateway",
            model = "DeepSeek/deepseek-v4-pro",
        )
        OpenAiCompatibleClient(profile).chatStream(listOf(Message.User("hi"))).toList()

        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(4096, body.getInt("max_tokens"))
    }

    @Test
    fun maxTokensCanBeOmitted_byPassingNull() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody("""{"choices":[{"delta":{"content":"x"},"finish_reason":"stop"}]}""")),
        )
        val profile = LlmProviderProfile.deepSeekOfficial(
            baseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "sk-test",
            model = "deepseek-chat",
            maxTokens = null,
        )
        OpenAiCompatibleClient(profile).chatStream(listOf(Message.User("hi"))).toList()

        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertNull("传 null 时不应出现 max_tokens 字段", body.opt("max_tokens"))
    }

    @Test
    fun responseFormat_isForwardedWhenConfigured() = runTest {
        // 文档「设置 json_schema」段:网关支持 response_format=json_schema 强制结构化输出。
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody("""{"choices":[{"delta":{"content":"{}"},"finish_reason":"stop"}]}""")),
        )
        val schema = JSONObject(
            """
            {"type":"json_schema","json_schema":{"name":"x","schema":{"type":"object"}}}
            """.trimIndent(),
        )
        val profile = LlmProviderProfile.customGateway(
            baseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "sk-gateway",
            model = "DeepSeek/deepseek-v4-pro",
            responseFormat = schema,
        )
        OpenAiCompatibleClient(profile).chatStream(listOf(Message.User("hi"))).toList()

        val body = JSONObject(server.takeRequest().body.readUtf8())
        val rf = body.getJSONObject("response_format")
        assertEquals("json_schema", rf.getString("type"))
        assertEquals("x", rf.getJSONObject("json_schema").getString("name"))
    }

    @Test
    fun responseFormat_omittedByDefault() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody("""{"choices":[{"delta":{"content":"x"},"finish_reason":"stop"}]}""")),
        )
        val profile = LlmProviderProfile.deepSeekOfficial(
            baseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "sk-test",
            model = "deepseek-chat",
        )
        OpenAiCompatibleClient(profile).chatStream(listOf(Message.User("hi"))).toList()

        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertNull("默认不应带 response_format", body.opt("response_format"))
    }

    @Test
    fun userMessageWithImages_serializedAsMultimodalArray() = runTest {
        // 视觉模型(Qwen2.5-VL 等)必须用数组形式的 content,见
        // .doc/通过apiKey直接调用模型.md「视觉模型」段。
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody("""{"choices":[{"delta":{"content":"猫"},"finish_reason":"stop"}]}""")),
        )
        val profile = LlmProviderProfile.customGateway(
            baseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "sk-gateway",
            model = "Qwen/Qwen2.5-VL-7B-Instruct",
        )
        val msg = Message.User(
            content = "描述这张图片",
            images = listOf("https://example.com/a.jpg", "https://example.com/b.jpg"),
        )
        OpenAiCompatibleClient(profile).chatStream(listOf(msg)).toList()

        val body = JSONObject(server.takeRequest().body.readUtf8())
        val content = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals(3, content.length())
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("描述这张图片", content.getJSONObject(0).getString("text"))
        assertEquals("image_url", content.getJSONObject(1).getString("type"))
        assertEquals(
            "https://example.com/a.jpg",
            content.getJSONObject(1).getJSONObject("image_url").getString("url"),
        )
        assertEquals(
            "https://example.com/b.jpg",
            content.getJSONObject(2).getJSONObject("image_url").getString("url"),
        )
    }

    @Test
    fun userMessageWithoutImages_keepsStringContent() = runTest {
        // 纯文本场景必须保持 content 是字符串(向后兼容,不切多模态数组)。
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody("""{"choices":[{"delta":{"content":"x"},"finish_reason":"stop"}]}""")),
        )
        val profile = LlmProviderProfile.deepSeekOfficial(
            baseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "sk-test",
            model = "deepseek-chat",
        )
        OpenAiCompatibleClient(profile).chatStream(listOf(Message.User("hello"))).toList()

        val body = JSONObject(server.takeRequest().body.readUtf8())
        val content = body.getJSONArray("messages").getJSONObject(0).get("content")
        assertEquals("hello", content as String)
    }

    @Test
    fun buildEndpoint_handlesBothBaseUrlForms() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            OpenAiCompatibleClient.buildEndpoint("https://api.deepseek.com"),
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            OpenAiCompatibleClient.buildEndpoint("https://api.deepseek.com/"),
        )
        assertEquals(
            "https://gateway.example.com/v1/chat/completions",
            OpenAiCompatibleClient.buildEndpoint("https://gateway.example.com/v1"),
        )
        assertEquals(
            "https://gateway.example.com/v1/chat/completions",
            OpenAiCompatibleClient.buildEndpoint("https://gateway.example.com/v1/"),
        )
    }
}
