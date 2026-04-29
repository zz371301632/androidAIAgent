package com.aiagent.sdk.llm

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * 用 MockWebServer 验证 [OpenAiCompatibleRerankClient] 实际发出的请求与
 * 对响应数据(可能 score / relevance_score 不同字段名、index 越界)的解析。
 */
class RerankClientTest {

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
        model = "ignored-for-rerank",
    )

    @Test
    fun rerank_sendsAllFieldsAndSortsResultsByScoreDesc() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"results":[
                  {"index":0,"relevance_score":0.1},
                  {"index":1,"relevance_score":0.9},
                  {"index":2,"relevance_score":0.5}
                ]}
                """.trimIndent(),
            ),
        )
        val docs = listOf("brazil", "france", "horses")
        val out = OpenAiCompatibleRerankClient(profile()).rerank(
            model = "bge-rerank",
            query = "capital of france",
            documents = docs,
            topN = 2,
        )

        val req = server.takeRequest()
        assertEquals("/v1/rerank", req.path)
        assertEquals("Bearer sk-test", req.getHeader("Authorization"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals("bge-rerank", body.getString("model"))
        assertEquals("capital of france", body.getString("query"))
        assertEquals(3, body.getJSONArray("documents").length())
        assertEquals(2, body.getInt("top_n"))

        // 按 score 降序排好,document 字段已回填原文。
        assertEquals(3, out.size)
        assertEquals(1, out[0].index)
        assertEquals("france", out[0].document)
        assertEquals(0.9f, out[0].score, 1e-6f)
        assertEquals(2, out[1].index)
        assertEquals(0, out[2].index)
    }

    @Test
    fun rerank_omitsTopNWhenNull() = runTest {
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))
        OpenAiCompatibleRerankClient(profile()).rerank(
            model = "m", query = "q", documents = listOf("d"), topN = null,
        )
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertNull(body.opt("top_n"))
    }

    @Test
    fun rerank_acceptsAlternateScoreFieldName() = runTest {
        // 个别供应商响应字段叫 `score` 而非 `relevance_score`,做 fallback。
        server.enqueue(
            MockResponse().setBody(
                """{"results":[{"index":0,"score":0.7}]}""",
            ),
        )
        val out = OpenAiCompatibleRerankClient(profile())
            .rerank("m", "q", listOf("only"))
        assertEquals(1, out.size)
        assertEquals(0.7f, out[0].score, 1e-6f)
    }

    @Test
    fun rerank_skipsOutOfBoundsIndex() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"results":[{"index":99,"relevance_score":1.0},{"index":0,"relevance_score":0.5}]}""",
            ),
        )
        val out = OpenAiCompatibleRerankClient(profile())
            .rerank("m", "q", listOf("a"))
        assertEquals("越界 index 应被丢弃", 1, out.size)
        assertEquals(0, out[0].index)
    }

    @Test
    fun rerank_doesNotInjectChatOnlyFields() = runTest {
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))
        OpenAiCompatibleRerankClient(profile()).rerank("m", "q", listOf("d"))
        val req = server.takeRequest()
        val body = JSONObject(req.body.readUtf8())
        assertNull(body.opt("rid"))
        assertNull(body.opt("enable_thinking"))
        assertNull(req.getHeader("trace_id"))
    }

    @Test
    fun rerank_throwsOnHttpError() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("rate limited"))
        val ex = assertThrows(IOException::class.java) {
            runBlocking {
                OpenAiCompatibleRerankClient(profile()).rerank("m", "q", listOf("d"))
            }
        }
        assertTrue(ex.message!!.contains("429"))
    }

    @Test
    fun rerank_throwsWhenDocumentsEmpty() = runTest {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                OpenAiCompatibleRerankClient(profile()).rerank("m", "q", emptyList())
            }
        }
    }

    @Test
    fun buildEndpoint_handlesBothBaseUrlForms() {
        assertEquals(
            "https://api.example.com/v1/rerank",
            OpenAiCompatibleRerankClient.buildEndpoint("https://api.example.com"),
        )
        assertEquals(
            "http://gateway.example.com/v1/rerank",
            OpenAiCompatibleRerankClient.buildEndpoint("http://gateway.example.com/v1"),
        )
    }
}
