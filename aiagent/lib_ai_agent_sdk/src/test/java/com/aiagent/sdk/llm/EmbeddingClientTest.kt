package com.aiagent.sdk.llm

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * 用 MockWebServer 验证 [OpenAiCompatibleEmbeddingClient] 实际发出的请求体
 * 与对响应数据(可能乱序、缺失等异常)的解析行为。
 */
class EmbeddingClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun profile() = LlmProviderProfile.customGateway(
        baseUrl = server.url("/").toString().trimEnd('/'),
        apiKey = "sk-test",
        model = "ignored-for-embedding",
    )

    @Test
    fun embed_sendsModelAndInputAndDimensions() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"data":[{"index":0,"embedding":[0.1,0.2,0.3]}]}""",
            ),
        )
        val vecs = OpenAiCompatibleEmbeddingClient(profile()).embed(
            model = "qwen-3-embedding-8b",
            input = listOf("hello"),
            dimensions = 3,
        )

        val req = server.takeRequest()
        assertEquals("/v1/embeddings", req.path)
        assertEquals("Bearer sk-test", req.getHeader("Authorization"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals("qwen-3-embedding-8b", body.getString("model"))
        assertEquals(1, body.getJSONArray("input").length())
        assertEquals("hello", body.getJSONArray("input").getString(0))
        assertEquals(3, body.getInt("dimensions"))

        assertEquals(1, vecs.size)
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f), vecs[0], 1e-6f)
    }

    @Test
    fun embed_omitsDimensionsWhenNull() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"data":[{"index":0,"embedding":[0.0]}]}""",
            ),
        )
        OpenAiCompatibleEmbeddingClient(profile()).embed(
            model = "m",
            input = listOf("a"),
            dimensions = null,
        )
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertNull("dimensions=null 时不应出现该字段", body.opt("dimensions"))
    }

    @Test
    fun embed_doesNotInjectChatOnlyFields() = runTest {
        // customGateway profile.decorate 注的 enable_thinking / rid / trace_id 都是 chat 专属,
        // embedding 端点不该带,以免污染请求或被网关拒。
        server.enqueue(
            MockResponse().setBody("""{"data":[{"index":0,"embedding":[0]}]}"""),
        )
        OpenAiCompatibleEmbeddingClient(profile()).embed("m", listOf("x"))
        val req = server.takeRequest()
        val body = JSONObject(req.body.readUtf8())
        assertNull("body 不应带 rid", body.opt("rid"))
        assertNull("body 不应带 enable_thinking", body.opt("enable_thinking"))
        assertNull("body 不应带 chat_template_kwargs", body.opt("chat_template_kwargs"))
        assertNull("header 不应带 trace_id", req.getHeader("trace_id"))
    }

    @Test
    fun embed_reordersByIndexFromResponse() = runTest {
        // 响应里 data 数组顺序与请求 input 不一定对齐,我们按 index 重排。
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":[
                  {"index":1,"embedding":[1.0,1.0]},
                  {"index":0,"embedding":[0.0,0.0]}
                ]}
                """.trimIndent(),
            ),
        )
        val vecs = OpenAiCompatibleEmbeddingClient(profile())
            .embed("m", listOf("first", "second"))
        assertEquals(2, vecs.size)
        assertArrayEquals(floatArrayOf(0f, 0f), vecs[0], 1e-6f)
        assertArrayEquals(floatArrayOf(1f, 1f), vecs[1], 1e-6f)
    }

    @Test
    fun embed_throwsOnHttpError() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        val ex = assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking {
                OpenAiCompatibleEmbeddingClient(profile()).embed("m", listOf("x"))
            }
        }
        assertTrue(ex.message!!.contains("401"))
    }

    @Test
    fun embed_throwsWhenInputIsEmpty() = runTest {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                OpenAiCompatibleEmbeddingClient(profile()).embed("m", emptyList())
            }
        }
    }

    @Test
    fun buildEndpoint_handlesBothBaseUrlForms() {
        assertEquals(
            "https://api.example.com/v1/embeddings",
            OpenAiCompatibleEmbeddingClient.buildEndpoint("https://api.example.com"),
        )
        assertEquals(
            "http://gateway.example.com/v1/embeddings",
            OpenAiCompatibleEmbeddingClient.buildEndpoint("http://gateway.example.com/v1"),
        )
    }
}
