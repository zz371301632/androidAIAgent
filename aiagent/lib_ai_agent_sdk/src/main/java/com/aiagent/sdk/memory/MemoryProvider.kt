package com.aiagent.sdk.memory

import com.aiagent.sdk.llm.Message

/**
 * 长期记忆检索接口。SDK 不规定后端实现 —— 集成方可以接 SQLite FTS / 向量库 /
 * mem0 / LightRAG 等任意来源,把命中片段以 [MemoryChunk] 形式返回。
 *
 * 调用时机:[com.aiagent.sdk.agent.AgentSession.snapshot] 在拼 system prompt
 * 时调用 [retrieve],把返回的片段渲染到 `## 相关记忆` 段落里。AgentLoop 完全
 * 感知不到 memory 的存在 —— 主循环零改动。
 *
 * 短期记忆约定:由 [com.aiagent.sdk.agent.AgentSession] 的 history 直接承担,
 * MemoryProvider 只面向跨轮 / 跨会话的长期记忆。
 */
interface MemoryProvider {

    /**
     * 拉取与本轮相关的记忆片段。
     *
     * @param query   本轮检索 query。SDK 默认传 [history] 里最后一条 [Message.User]
     *                的文本;若 history 里没有任何 user 消息,SDK 会跳过本次调用。
     * @param history 当前会话历史(只读),供上下文相关召回使用。
     * @return        已按相关性降序排好的片段列表;空列表 = 本轮不注入。
     */
    suspend fun retrieve(query: String, history: List<Message>): List<MemoryChunk>

    companion object {
        /** 占位实现:始终返回空,等价于「未启用 memory」。 */
        val EMPTY: MemoryProvider = object : MemoryProvider {
            override suspend fun retrieve(query: String, history: List<Message>): List<MemoryChunk> =
                emptyList()
        }
    }
}

/**
 * 单条记忆片段。
 *
 * @param text   片段文本,会原样进 system prompt;不要塞过长内容,模型 context 有限。
 * @param source 可选来源标记(如 `user_profile` / `doc:wifi.md#L12`)。非空时以
 *               `[source]` 前缀展示,便于模型引用 / 回避错误来源。
 * @param score  可选相关性分数,SDK 不读取,仅供 [MemoryProvider] 实现内部排序使用。
 */
data class MemoryChunk(
    val text: String,
    val source: String? = null,
    val score: Float? = null,
)
