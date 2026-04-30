package com.aiagent.sdk.agent

import com.aiagent.sdk.llm.Message
import com.aiagent.sdk.memory.MemoryChunk
import com.aiagent.sdk.memory.MemoryProvider
import com.aiagent.sdk.memory.StaticMemory
import com.aiagent.sdk.skill.SkillRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 [AgentSession.snapshot] 在拼 system prompt 时按预期注入 `## 相关记忆` 段落。
 *
 * 覆盖:
 *  - 默认 [MemoryProvider.EMPTY] → 不注入,行为与历史版本一致;
 *  - 提供 memory 但 history 没有 user 消息 → 不调 retrieve,不注入;
 *  - 单条 / 多条 chunk:文本 + 可选 source 前缀均按格式渲染;
 *  - retrieve 收到的 query == 最后一条 user 消息(而不是更早的 user 消息)。
 */
class AgentSessionMemoryTest {

    private fun newSession(memory: MemoryProvider = MemoryProvider.EMPTY) = AgentSession(
        skillRegistry = SkillRegistry(),
        basePersona = "PERSONA",
        memory = memory,
    )

    @Test
    fun defaultEmptyProvider_doesNotInjectMemorySection() = runTest {
        val s = newSession()
        s.appendUser("你好")

        val sys = (s.snapshot().first() as Message.System).content

        assertFalse("EMPTY provider 不应触发记忆段落", sys.contains("## 相关记忆"))
    }

    @Test
    fun providerWithoutUserMessage_doesNotInjectMemorySection() = runTest {
        var called = false
        val mem = object : MemoryProvider {
            override suspend fun retrieve(query: String, history: List<Message>): List<MemoryChunk> {
                called = true
                return listOf(MemoryChunk("不该被注入"))
            }
        }
        val s = newSession(mem)
        // history 完全为空 —— 不该触发 retrieve

        val sys = (s.snapshot().first() as Message.System).content

        assertFalse("没有 user 消息时不该调 retrieve", called)
        assertFalse(sys.contains("## 相关记忆"))
    }

    @Test
    fun staticMemory_singleChunkWithoutSource_rendersBareBullet() = runTest {
        val s = newSession(StaticMemory("用户偏好简短回复"))
        s.appendUser("帮我总结一下")

        val sys = (s.snapshot().first() as Message.System).content

        assertTrue(sys.contains("## 相关记忆"))
        assertTrue("无 source 时不带方括号前缀", sys.contains("- 用户偏好简短回复"))
        assertFalse(sys.contains("[null]"))
    }

    @Test
    fun staticMemory_multipleChunksWithSource_rendersPrefixedBullets() = runTest {
        val mem = StaticMemory(
            listOf(
                MemoryChunk("时区 Asia/Shanghai", source = "user_profile"),
                MemoryChunk("默认语言 Kotlin", source = "user_profile"),
                MemoryChunk("回答尽量简短"),
            )
        )
        val s = newSession(mem)
        s.appendUser("现在几点")

        val sys = (s.snapshot().first() as Message.System).content

        assertTrue(sys.contains("## 相关记忆"))
        assertTrue(sys.contains("- [user_profile] 时区 Asia/Shanghai"))
        assertTrue(sys.contains("- [user_profile] 默认语言 Kotlin"))
        assertTrue(sys.contains("- 回答尽量简短"))
        // 顺序保留(StaticMemory 原样吐出)
        val idx1 = sys.indexOf("时区 Asia/Shanghai")
        val idx2 = sys.indexOf("默认语言 Kotlin")
        val idx3 = sys.indexOf("回答尽量简短")
        assertTrue(idx1 in 0..idx2)
        assertTrue(idx2 in 0..idx3)
    }

    @Test
    fun retrieveQueryEqualsLastUserMessage() = runTest {
        var seenQuery: String? = null
        var seenHistorySize = -1
        val mem = object : MemoryProvider {
            override suspend fun retrieve(query: String, history: List<Message>): List<MemoryChunk> {
                seenQuery = query
                seenHistorySize = history.size
                return emptyList()
            }
        }
        val s = newSession(mem)
        s.appendUser("第一轮提问")
        s.appendAssistant(Message.Assistant(content = "第一轮回答"))
        s.appendUser("第二轮提问 ←这条才是 query")

        s.snapshot()

        assertEquals("第二轮提问 ←这条才是 query", seenQuery)
        assertEquals(3, seenHistorySize)
    }

    @Test
    fun memorySectionAppearsBetweenPersonaAndSkills() = runTest {
        val s = newSession(StaticMemory("片段X"))
        s.appendUser("hi")

        val sys = (s.snapshot().first() as Message.System).content
        val personaIdx = sys.indexOf("PERSONA")
        val memIdx = sys.indexOf("## 相关记忆")
        val skillIdx = sys.indexOf("## 可用 Skill")

        assertNotNull(sys)
        assertTrue("persona 应在最前", personaIdx >= 0)
        assertTrue("memory 段应在 persona 之后", memIdx > personaIdx)
        assertTrue("memory 段应在 skill 段之前", memIdx < skillIdx)
    }
}
