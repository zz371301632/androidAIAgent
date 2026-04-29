package com.aiagent.sdk.llm

import org.json.JSONArray
import org.json.JSONObject

/**
 * 与 LLM 交互的消息体。OpenAI / DeepSeek 格式兼容。
 *
 * 注:`ToolSchema` 已经下沉到 `com.aiagent.runtime.ToolSchema`(lib_ai_annotations),
 * SDK 与业务方共享同一份契约,这里不再重复定义。
 */
sealed interface Message {
    val role: String

    /** 序列化为 OpenAI 兼容的 JSON 对象。 */
    fun toJson(): JSONObject

    /** 系统提示,约束模型行为。 */
    data class System(val content: String) : Message {
        override val role: String = "system"
        override fun toJson(): JSONObject = JSONObject().put("role", role).put("content", content)
    }

    /**
     * 用户输入。
     *
     * 纯文本场景(99%)直接用 `User("...")` 即可,序列化成
     * `{"role":"user","content":"..."}`。
     *
     * 视觉场景给 [images] 传图片 URL 列表(每条都是 https/http 直链),序列化时
     * 自动改成 OpenAI 多模态 content 数组:
     *   `[{"type":"text","text":...},{"type":"image_url","image_url":{"url":...}}]`
     * 此时 model 必须是支持视觉的型号(如 `Qwen/Qwen2.5-VL-7B-Instruct`)。
     */
    data class User(
        val content: String,
        val images: List<String> = emptyList(),
    ) : Message {
        override val role: String = "user"
        override fun toJson(): JSONObject {
            val obj = JSONObject().put("role", role)
            if (images.isEmpty()) {
                return obj.put("content", content)
            }
            val arr = JSONArray()
            if (content.isNotEmpty()) {
                arr.put(JSONObject().put("type", "text").put("text", content))
            }
            images.forEach { url ->
                arr.put(
                    JSONObject()
                        .put("type", "image_url")
                        .put("image_url", JSONObject().put("url", url)),
                )
            }
            return obj.put("content", arr)
        }
    }

    /**
     * 模型回复。可能只回文本,也可能要求调用工具。
     *
     * [reasoningContent] 是部分供应商(DeepSeek v4-pro thinking 模式)在响应里给的
     * 独立 `reasoning_content` 字段。协议要求:**只要本轮带过它,下一轮就必须原样 echo
     * 回去**,否则供应商会回 400「reasoning_content must be passed back」。所以这里
     * 把它当一等公民存下来,toJson 时序列化为 `reasoning_content`。
     */
    data class Assistant(
        val content: String? = null,
        val reasoningContent: String? = null,
        val toolCalls: List<ToolCall> = emptyList(),
    ) : Message {
        override val role: String = "assistant"
        override fun toJson(): JSONObject {
            val obj = JSONObject().put("role", role)
            obj.put("content", content ?: JSONObject.NULL)
            if (!reasoningContent.isNullOrEmpty()) {
                obj.put("reasoning_content", reasoningContent)
            }
            if (toolCalls.isNotEmpty()) {
                val arr = JSONArray()
                toolCalls.forEach { arr.put(it.toJson()) }
                obj.put("tool_calls", arr)
            }
            return obj
        }
    }

    /** 工具执行结果。必须与 Assistant.toolCalls[i].id 对应。 */
    data class Tool(
        val toolCallId: String,
        val content: String,
    ) : Message {
        override val role: String = "tool"
        override fun toJson(): JSONObject = JSONObject()
            .put("role", role)
            .put("tool_call_id", toolCallId)
            .put("content", content)
    }
}

/** 模型一次工具调用请求。arguments 是 JSON 字符串(模型生成)。 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
    val type: String = "function",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("type", type)
        .put(
            "function",
            JSONObject().put("name", name).put("arguments", arguments),
        )
}
