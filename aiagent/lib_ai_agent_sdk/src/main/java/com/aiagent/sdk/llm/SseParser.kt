package com.aiagent.sdk.llm

import okio.BufferedSource

/**
 * 极简 SSE 行解析器。OpenAI / DeepSeek 的流式响应格式是 Server-Sent Events,
 * 每条事件由若干 "field: value" 行组成,事件之间用空行分隔,例如:
 *
 *   data: {"choices":[{"delta":{"content":"hi"}}]}
 *
 *   data: {"choices":[{"delta":{"content":" world"}}]}
 *
 *   data: [DONE]
 *
 * 我们只关心 data 字段。返回的 String 序列正是每条 data 的原始文本(不含 "data: " 前缀)。
 *
 * 此实现刻意保持纯函数 + 无状态副作用,便于单测。
 */
internal object SseParser {

    /**
     * 从 OkHttp 的 BufferedSource 持续读取,直到流结束。
     * 调用方负责在合适时机关闭 source。
     *
     * 返回的 Sequence 是惰性的:每次 next() 才读下一行。
     */
    fun parse(source: BufferedSource): Sequence<String> = sequence {
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (line.isEmpty() || line.startsWith(":")) continue
            val payload = when {
                line.startsWith("data:") -> line.substring(5).trimStart()
                else -> continue
            }
            yield(payload)
        }
    }

    /** 直接从字符串解析(测试用)。 */
    fun parseFromString(text: String): List<String> {
        val out = mutableListOf<String>()
        for (raw in text.split('\n')) {
            val line = raw.trimEnd('\r')
            if (line.isEmpty() || line.startsWith(":")) continue
            if (line.startsWith("data:")) {
                out.add(line.substring(5).trimStart())
            }
        }
        return out
    }
}
