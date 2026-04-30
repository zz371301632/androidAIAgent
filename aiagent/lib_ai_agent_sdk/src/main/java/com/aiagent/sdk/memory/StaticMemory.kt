package com.aiagent.sdk.memory

import com.aiagent.sdk.llm.Message

/**
 * 「常驻」记忆:每轮原样返回构造时传入的固定 facts,与 query 无关。
 *
 * 适合放用户画像 / 系统约束 / 常量类知识(如「用户所在时区 Asia/Shanghai」),
 * 或者作为单元测试 / 演示场景的最小实现。生产场景的真 RAG 应由集成方自己实现
 * [MemoryProvider]:embedding 召回 / 关键词匹配 / 远端服务等都行。
 */
class StaticMemory(facts: List<MemoryChunk>) : MemoryProvider {

    private val snapshot: List<MemoryChunk> = facts.toList()

    /** 便捷构造:纯文本 facts,自动包成 [MemoryChunk]。 */
    constructor(vararg texts: String) : this(texts.map { MemoryChunk(it) })

    override suspend fun retrieve(query: String, history: List<Message>): List<MemoryChunk> = snapshot
}
