package com.aiagent.sdk.llm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 验证 [ThinkTagFilter] 在不同切分场景下都能正确剥离 `<think>...</think>`。
 */
class ThinkTagFilterTest {

    private data class Pieces(val visible: String, val hidden: String)

    private fun feedAll(filter: ThinkTagFilter, chunks: List<String>): Pieces {
        val v = StringBuilder()
        val h = StringBuilder()
        chunks.forEach { c ->
            val r = filter.feed(c)
            v.append(r.visible); h.append(r.hidden)
        }
        val tail = filter.flush()
        v.append(tail.visible); h.append(tail.hidden)
        return Pieces(v.toString(), h.toString())
    }

    @Test
    fun stripsThinkBlock_singleChunk() {
        val r = feedAll(ThinkTagFilter(), listOf("<think>cot here</think>hello"))
        assertEquals("hello", r.visible)
        assertEquals("cot here", r.hidden)
    }

    @Test
    fun keepsTextWithoutThink() {
        val r = feedAll(ThinkTagFilter(), listOf("hello ", "world"))
        assertEquals("hello world", r.visible)
        assertEquals("", r.hidden)
    }

    @Test
    fun stripsWhenTagsArrivedAsOwnDeltas() {
        // 复刻线上日志的切分:<think> 单独一片,中间多片,</think> 与上一片合并
        val r = feedAll(
            ThinkTagFilter(),
            listOf(
                "<think>",
                "We are in the initial state.",
                " The user said \"hi\".",
                " Done.\n</think>",
                "\n你好",
                "！欢迎",
            ),
        )
        assertEquals("\n你好！欢迎", r.visible)
        assertEquals(
            "We are in the initial state. The user said \"hi\". Done.\n",
            r.hidden,
        )
    }

    @Test
    fun handlesOpeningTagSplitAcrossChunks() {
        val r = feedAll(ThinkTagFilter(), listOf("hi <th", "ink>cot</think> ok"))
        assertEquals("hi  ok", r.visible)
        assertEquals("cot", r.hidden)
    }

    @Test
    fun handlesClosingTagSplitAcrossChunks() {
        val r = feedAll(ThinkTagFilter(), listOf("<think>cot</th", "ink>after"))
        assertEquals("after", r.visible)
        assertEquals("cot", r.hidden)
    }

    @Test
    fun handlesThinkTagsOnLastChunkBoundary() {
        // 流结束时 think 仍未闭合:visible 截到开标签前,残留转 hidden
        val r = feedAll(ThinkTagFilter(), listOf("visible <think>still thinking"))
        assertEquals("visible ", r.visible)
        assertEquals("still thinking", r.hidden)
    }

    @Test
    fun keepsLeadingLessThanThatLooksLikeTag() {
        // 不是 <think> 的尖括号,要原样保留
        val r = feedAll(ThinkTagFilter(), listOf("<thanks>", " for asking"))
        assertEquals("<thanks> for asking", r.visible)
        assertEquals("", r.hidden)
    }

    @Test
    fun multipleThinkBlocks() {
        val r = feedAll(ThinkTagFilter(), listOf("<think>a</think>x<think>b</think>y"))
        assertEquals("xy", r.visible)
        assertEquals("ab", r.hidden)
    }

    @Test
    fun trailingPartialOpenTagFlushes() {
        // 流结束时 carry 是 "<th",如果它实际上不是 think 开头,应当原样补出
        val r = feedAll(ThinkTagFilter(), listOf("hello <th"))
        assertEquals("hello <th", r.visible)
        assertEquals("", r.hidden)
    }
}
