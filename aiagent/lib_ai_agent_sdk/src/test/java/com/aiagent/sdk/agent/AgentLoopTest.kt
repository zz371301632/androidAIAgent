package com.aiagent.sdk.agent

import com.aiagent.sdk.llm.LlmClient
import com.aiagent.sdk.llm.LlmStreamEvent
import com.aiagent.sdk.llm.Message
import com.aiagent.runtime.ToolSchema
import com.aiagent.sdk.skill.Skill
import com.aiagent.sdk.skill.SkillCallTools
import com.aiagent.sdk.skill.SkillRegistry
import com.aiagent.runtime.Tool
import com.aiagent.sdk.tool.ToolRegistry
import com.aiagent.runtime.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用脚本化的事件序列驱动 LlmClient,便于精确测试 AgentLoop。 */
private class ScriptedLlm(
    private val rounds: List<List<LlmStreamEvent>>,
) : LlmClient {
    private var idx = 0
    /** 每轮收到的 (messages, tools) 快照,便于断言「第 N 轮看到的 tool 包含 X」。 */
    val seenSchemas = mutableListOf<List<ToolSchema>>()
    val seenMessages = mutableListOf<List<Message>>()
    override fun chatStream(
        messages: List<Message>,
        tools: List<ToolSchema>?,
        toolChoice: String,
    ): Flow<LlmStreamEvent> = flow {
        seenSchemas.add(tools ?: emptyList())
        seenMessages.add(messages)
        val script = rounds.getOrElse(idx++) { listOf(LlmStreamEvent.Done("stop")) }
        for (e in script) emit(e)
    }
}

private class FakeSkill(
    override val id: String,
    override val name: String = id,
    override val description: String = "$id desc",
    private val instructions: String = "# $id manual",
    override val toolNames: Set<String> = emptySet(),
) : Skill {
    override suspend fun loadInstructions(): String = instructions
}

private const val TEST_PERSONA = "test persona"

private fun emptyRegistry(): SkillRegistry = SkillRegistry()
private fun newSession(
    registry: SkillRegistry = emptyRegistry(),
    baseTools: Set<String> = emptySet(),
    maxRounds: Int = AgentSession.DEFAULT_MAX_ROUNDS,
) = AgentSession(
    skillRegistry = registry,
    basePersona = TEST_PERSONA,
    baseToolNames = baseTools,
    maxRounds = maxRounds,
)

private class StubTool(
    override val name: String,
    private val output: String,
    override val requiresConfirmation: Boolean = false,
) : Tool {
    override val description: String = "stub"
    override val parametersJsonSchema: String = """{"type":"object"}"""
    var calls: Int = 0
    override suspend fun execute(args: JSONObject): ToolResult {
        calls++
        return ToolResult.Success(output)
    }
}

class AgentLoopTest {

    @Test
    fun pureTextRoundFinishesWithStop() = runTest {
        val llm = ScriptedLlm(listOf(listOf(
            LlmStreamEvent.ContentDelta("hi "),
            LlmStreamEvent.ContentDelta("there"),
            LlmStreamEvent.Done("stop"),
        )))
        val loop = AgentLoop(llm, ToolRegistry())
        val events = loop.run(newSession(), "你好").toList()
        assertEquals(2, events.count { it is AgentEvent.AssistantDelta })
        val final = events.filterIsInstance<AgentEvent.AssistantFinal>().single()
        assertEquals("hi there", final.text)
        assertTrue(final.toolCalls.isEmpty())
        assertEquals(FinishReason.Stop, (events.last() as AgentEvent.LoopFinished).reason)
    }

    @Test
    fun toolCallRoundExecutesAndContinues() = runTest {
        val tool = StubTool("get_user", """{"id":42}""")
        val tools = ToolRegistry().apply { register(tool) }
        val llm = ScriptedLlm(listOf(
            // round 1: 模型要调工具
            listOf(
                LlmStreamEvent.ToolCallDelta(0, "call_1", "get_user", "{}"),
                LlmStreamEvent.Done("tool_calls"),
            ),
            // round 2: 模型基于结果给最终回答
            listOf(
                LlmStreamEvent.ContentDelta("当前用户 42"),
                LlmStreamEvent.Done("stop"),
            ),
        ))
        val session = newSession(baseTools = setOf("get_user"))
        val events = AgentLoop(llm, tools).run(session, "我是谁").toList()
        assertEquals(1, tool.calls)
        assertTrue(events.any { it is AgentEvent.ToolCallStarted })
        assertTrue(events.any { it is AgentEvent.ToolCallCompleted })
        assertEquals(FinishReason.Stop, (events.last() as AgentEvent.LoopFinished).reason)
    }

    @Test
    fun confirmationDeniedSkipsExecutionAndPostsDeniedMessage() = runTest {
        val danger = StubTool("switch_acc", "ok", requiresConfirmation = true)
        val tools = ToolRegistry().apply { register(danger) }
        val llm = ScriptedLlm(listOf(
            listOf(
                LlmStreamEvent.ToolCallDelta(0, "c1", "switch_acc", "{}"),
                LlmStreamEvent.Done("tool_calls"),
            ),
            listOf(
                LlmStreamEvent.ContentDelta("已取消"),
                LlmStreamEvent.Done("stop"),
            ),
        ))
        val loop = AgentLoop(llm, tools, confirmDangerous = { _, _ -> false })
        val session = newSession(baseTools = setOf("switch_acc"))
        val events = loop.run(session, "切账号").toList()
        assertEquals(0, danger.calls)
        assertTrue(events.any { it is AgentEvent.ConfirmationDenied })
    }

    @Test
    fun maxRoundsHonoured() = runTest {
        val tool = StubTool("get_user", "{}")
        val tools = ToolRegistry().apply { register(tool) }
        // 永远要求调工具,触发 max rounds
        val llm = ScriptedLlm(List(10) {
            listOf(
                LlmStreamEvent.ToolCallDelta(0, "id_$it", "get_user", "{}"),
                LlmStreamEvent.Done("tool_calls"),
            )
        })
        val session = newSession(baseTools = setOf("get_user"), maxRounds = 3)
        val events = AgentLoop(llm, tools).run(session, "loop").toList()
        assertEquals(3, tool.calls)
        assertEquals(
            FinishReason.MaxRoundsReached,
            (events.last() as AgentEvent.LoopFinished).reason,
        )
    }

    @Test
    fun llmErrorBecomesLoopError() = runTest {
        val llm = ScriptedLlm(listOf(listOf(
            LlmStreamEvent.Error(RuntimeException("boom")),
        )))
        val events = AgentLoop(llm, ToolRegistry()).run(newSession(), "x").toList()
        assertTrue(events.last() is AgentEvent.LoopError)
        assertEquals("boom", (events.last() as AgentEvent.LoopError).cause.message)
    }

    @Test
    fun loadSkillUnlocksToolsOnNextRound() = runTest {
        val tool = StubTool("get_user", """{"id":1}""")
        val tools = ToolRegistry().apply { register(tool) }
        val registry = SkillRegistry().apply {
            register(FakeSkill(id = "account", toolNames = setOf("get_user")))
        }
        val llm = ScriptedLlm(listOf(
            // round 1: 模型先 load_skill
            listOf(
                LlmStreamEvent.ToolCallDelta(0, "c1", SkillCallTools.NAME_LOAD, """{"id":"account"}"""),
                LlmStreamEvent.Done("tool_calls"),
            ),
            // round 2: 模型已经看到 get_user,直接调
            listOf(
                LlmStreamEvent.ToolCallDelta(0, "c2", "get_user", "{}"),
                LlmStreamEvent.Done("tool_calls"),
            ),
            // round 3: 给最终答案
            listOf(
                LlmStreamEvent.ContentDelta("done"),
                LlmStreamEvent.Done("stop"),
            ),
        ))
        val session = AgentSession(
            skillRegistry = registry,
            basePersona = TEST_PERSONA,
        )
        AgentLoop(llm, tools).run(session, "切账号").toList()

        // round 1 schemas:只有 list_skills + load_skill,没有 get_user
        val r1 = llm.seenSchemas[0].map { it.name }.toSet()
        assertEquals(setOf(SkillCallTools.NAME_LIST, SkillCallTools.NAME_LOAD), r1)

        // round 2 schemas:get_user 已经被 load_skill 解锁
        val r2 = llm.seenSchemas[1].map { it.name }.toSet()
        assertTrue("get_user should appear after load_skill, got=$r2", "get_user" in r2)
        assertTrue(SkillCallTools.NAME_LOAD in r2)

        assertEquals(setOf("account"), session.loadedSkillIds)
        assertEquals(1, tool.calls)
    }

    @Test
    fun listSkillsReturnsRegistryContent() = runTest {
        val registry = SkillRegistry().apply {
            register(FakeSkill(id = "a", description = "alpha"))
            register(FakeSkill(id = "b", description = "beta"))
        }
        val llm = ScriptedLlm(listOf(
            listOf(
                LlmStreamEvent.ToolCallDelta(0, "c1", SkillCallTools.NAME_LIST, "{}"),
                LlmStreamEvent.Done("tool_calls"),
            ),
            listOf(
                LlmStreamEvent.ContentDelta("ok"),
                LlmStreamEvent.Done("stop"),
            ),
        ))
        val events = AgentLoop(llm, ToolRegistry())
            .run(
                AgentSession(
                    skillRegistry = registry,
                    basePersona = TEST_PERSONA,
                ),
                "列表",
            ).toList()
        val completed = events.filterIsInstance<AgentEvent.ToolCallCompleted>()
            .single { it.call.name == SkillCallTools.NAME_LIST }
        val content = (completed.result as ToolResult.Success).content
        assertTrue("expected 'a' in $content", content.contains("\"id\":\"a\""))
        assertTrue("expected 'b' in $content", content.contains("\"id\":\"b\""))
    }
}
