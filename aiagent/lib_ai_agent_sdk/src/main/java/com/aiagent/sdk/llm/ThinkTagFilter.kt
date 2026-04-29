package com.aiagent.sdk.llm

/**
 * 流式 `<think>...</think>` 段过滤器。
 *
 * 背景:部分供应商(例如自定义网关上的 deepseek-reasoner)即便传了
 * `enable_thinking=false`,仍会把 CoT 用 `<think>...</think>` 包在 content 字段里
 * 一起返回。直接展示给用户既丑又泄漏推理过程,持久化进 history 还会让下一轮
 * 命中供应商的「assistant.content 不允许含 think 段」校验,触发 HTTP 400。
 *
 * 行为:
 *  - 输入按任意切分点分多次喂入(模拟 SSE delta);
 *  - 输出只包含 think 段之外的「可见文本」;
 *  - 标签可能跨 chunk,内部用一个小缓冲缓存「可能是标签前缀」的尾巴。
 *
 * 嵌套不支持(实际场景没见过),遇到嵌套会按最外层成对匹配处理。
 */
internal class ThinkTagFilter {

    /** 单次 [feed] 的拆分结果:visible 走 ContentDelta,hidden 走 ReasoningDelta。 */
    data class Result(val visible: String, val hidden: String) {
        companion object { val EMPTY = Result("", "") }
    }

    private var inside: Boolean = false
    /** 上一轮 feed 留下的、可能是标签前缀的尾巴(永远 < OPEN/CLOSE 的长度)。 */
    private var carry: String = ""

    fun feed(text: String): Result {
        if (text.isEmpty()) return Result.EMPTY
        val s = carry + text
        carry = ""
        val visible = StringBuilder()
        val hidden = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (inside) {
                val idx = s.indexOf(CLOSE, i)
                if (idx >= 0) {
                    if (idx > i) hidden.append(s, i, idx)
                    inside = false
                    i = idx + CLOSE.length
                } else {
                    val tail = partialMatchLen(s, i, CLOSE)
                    val end = s.length - tail
                    if (end > i) hidden.append(s, i, end)
                    if (tail > 0) carry = s.substring(end)
                    return Result(visible.toString(), hidden.toString())
                }
            } else {
                val idx = s.indexOf(OPEN, i)
                if (idx >= 0) {
                    visible.append(s, i, idx)
                    inside = true
                    i = idx + OPEN.length
                } else {
                    val tail = partialMatchLen(s, i, OPEN)
                    val end = s.length - tail
                    visible.append(s, i, end)
                    if (tail > 0) carry = s.substring(end)
                    return Result(visible.toString(), hidden.toString())
                }
            }
        }
        return Result(visible.toString(), hidden.toString())
    }

    /**
     * 流结束时调用。
     *  - 若结束时仍在 think 段(标签未闭合),carry 里残留的当作 hidden 透出,避免静默丢失;
     *  - 若结束时在外侧,carry 里只可能是 `<think>` 的真前缀(还没闭合的 `<`、`<t` 等),
     *    一并作为可见文本返还,避免末尾少字。
     */
    fun flush(): Result {
        val pending = carry
        carry = ""
        return if (inside) Result("", pending) else Result(pending, "")
    }

    /**
     * 返回 s 从 start 起的尾巴中,作为 pattern 真前缀的最大长度。
     * 用于「标签可能在下一次 feed 才闭合」的场景:把这部分尾巴留到 carry。
     */
    private fun partialMatchLen(s: String, start: Int, pattern: String): Int {
        val maxLen = minOf(pattern.length - 1, s.length - start)
        for (n in maxLen downTo 1) {
            if (s.regionMatches(s.length - n, pattern, 0, n)) return n
        }
        return 0
    }

    companion object {
        private const val OPEN = "<think>"
        private const val CLOSE = "</think>"
    }
}
