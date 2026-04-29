package com.aiagent.runtime

import org.json.JSONObject

/**
 * 工具的 JSON Schema,告诉模型「我有哪些工具、每个工具长什么样」。
 *
 * [parametersJson] 是符合 JSON Schema 的字符串,例如:
 * `{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}`
 */
data class ToolSchema(
    val name: String,
    val description: String,
    val parametersJson: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", name)
                .put("description", description)
                .put("parameters", JSONObject(parametersJson)),
        )
}
