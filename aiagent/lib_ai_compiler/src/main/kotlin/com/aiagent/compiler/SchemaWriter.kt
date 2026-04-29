package com.aiagent.compiler

/**
 * 把 [ParsedTool] 的参数列表序列化成 OpenAI / DeepSeek 兼容的 JSON Schema 字符串,
 * 嵌入生成代码里给模型看。
 *
 * 这里手写 JSON 串(不引 org.json),原因:
 *  - 处理器跑在编译进程,结果会作为字符串字面量塞进 .kt 文件,本身就要转义,
 *    走 JSON 库再 toString 反而多绕一层;
 *  - 字段顺序固定(properties / required),便于 diff 与缓存命中。
 */
internal object SchemaWriter {

    fun build(tool: ParsedTool): String {
        val sb = StringBuilder()
        sb.append("{\"type\":\"object\",\"properties\":{")
        var first = true
        for (p in tool.params) {
            if (!first) sb.append(',')
            first = false
            appendProp(sb, p)
        }
        sb.append("},\"required\":[")
        var firstReq = true
        for (p in tool.params) {
            if (!p.isRequired) continue
            if (!firstReq) sb.append(',')
            firstReq = false
            sb.append('"').append(escape(p.name)).append('"')
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun appendProp(sb: StringBuilder, p: ParsedParam) {
        sb.append('"').append(escape(p.name)).append("\":{")
        sb.append("\"type\":\"").append(p.type.jsonSchemaType).append('"')
        if (p.description.isNotEmpty()) {
            sb.append(",\"description\":\"").append(escape(p.description)).append('"')
        }
        if (p.type == ParamType.Enum && p.enumDecl != null) {
            sb.append(",\"enum\":[")
            p.enumDecl.entries.forEachIndexed { idx, e ->
                if (idx > 0) sb.append(',')
                sb.append('"').append(escape(e)).append('"')
            }
            sb.append(']')
        }
        sb.append('}')
    }

    /**
     * JSON 字符串转义。注意我们随后会把整段塞进一个 Kotlin 三引号字符串里,
     * Kotlin 三引号不对 `"`、`\` 做转义,所以这里只需要按 JSON 规范处理。
     * 控制字符 (<0x20) 一律 \uXXXX 形式,避免源码里出现裸控制符。
     */
    private fun escape(s: String): String {
        val sb = StringBuilder(s.length + 4)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c.code < 0x20) {
                    sb.append("\\u").append("%04x".format(c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        return sb.toString()
    }
}
