package com.aiagent.sdk.llm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SseParser 的纯逻辑单测。验证三类常见情况:
 *  1. 多条 data 行混合空行 / 注释行
 *  2. [DONE] 终止行原样透传(由调用方负责识别)
 *  3. 非 data: 字段(event:/id:/retry:)被忽略
 */
class SseParserTest {

    @Test
    fun parsesMultipleDataLines() {
        val text = """
            data: {"choices":[{"delta":{"content":"hi"}}]}
            
            data: {"choices":[{"delta":{"content":" world"}}]}
            
            data: [DONE]
        """.trimIndent()

        val out = SseParser.parseFromString(text)
        assertEquals(3, out.size)
        assertEquals("{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}", out[0])
        assertEquals("{\"choices\":[{\"delta\":{\"content\":\" world\"}}]}", out[1])
        assertEquals("[DONE]", out[2])
    }

    @Test
    fun ignoresCommentsAndUnknownFields() {
        val text = """
            : keep-alive comment
            event: message
            id: 42
            retry: 3000
            data: {"a":1}
        """.trimIndent()

        val out = SseParser.parseFromString(text)
        assertEquals(1, out.size)
        assertEquals("{\"a\":1}", out[0])
    }

    @Test
    fun trimsLeadingSpaceAfterColon() {
        // 规范允许 "data:foo" 与 "data: foo" 等价,前导空格应被去掉
        val text = "data:noSpace\ndata:   threeSpaces"
        val out = SseParser.parseFromString(text)
        assertEquals(listOf("noSpace", "threeSpaces"), out)
    }
}
