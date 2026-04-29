package com.aiagent.sdk.agent

import com.aiagent.runtime.Tool
import com.aiagent.runtime.ToolResult
import com.aiagent.runtime.ToolSchema
import com.aiagent.sdk.llm.LlmClient
import com.aiagent.sdk.llm.LlmStreamEvent
import com.aiagent.sdk.llm.Message
import com.aiagent.sdk.skill.SkillRegistry
import com.aiagent.sdk.tool.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 注意:父循环和子循环共享同一个 [ScriptLlm] —— 内部 idx 单调递增,所以脚本顺序就是
 * 「父第 1 轮、子第 1 轮、子第 2 轮、父第 2 轮…」。本测试文件用这一约定串出嵌套行为。
 */
private class ScriptLlm(private val rounds: List<List<LlmStreamEvent>>) : LlmClient {
    private var idx = 0
    val seenSchemas = mutableListOf<List<ToolSchema>>()
    override fun chatStream(
        messages: List<Message>,
        tools: List<ToolSchema>?,
        toolChoice: String,
    ): Flow<LlmStreamEvent> = flow {
        seenSchemas += tools ?: emptyList()
        val script = rounds.getOrElse(idx++) { listOf(LlmStreamEvent.Done("stop")) }
        for (e in script) emit(e)
    }
}

private class CountingTool(
    override val name: String,
    private val output: String = "ok",
    override val requiresConfirmation: Boolean = false,
) : Tool {
    override val description: String = "stub"
    override val parametersJsonSchema: String = """{"type":"object"}"""
    var calls: Int = 0
    override suspend fun execute(args: JSONObject): ToolResult {
        calls++; return ToolResult.Success(output)
    }
}

private fun callSubAgent(id: String, agentType: String, task: String) =
    LlmStreamEvent.ToolCallDelta(0, id, SubAgentTools.NAME_CALL, """{"agent_type":"$agentType","task":"$task"}""")

private fun newRegistry(vararg presets: SubAgentPreset) = SubAgentRegistry().apply {
    presets.forEach(::register)
}

class SubAgentInvokerTest {

    private val researcher = SubAgentPreset(
        id = "researcher", displayName = "研究员", description = "信息整理",
        persona = "你是研究员", maxRounds = 3,
    )

    @Test
    fun basicDelegationReturnsChildFinalTextToParent() = runTest {
        // 父第 1 轮:派 researcher;子第 1 轮:纯文本收尾;父第 2 轮:基于 tool 结果继续。
        val llm = ScriptLlm(listOf(
            listOf(callSubAgent("p1", "researcher", "归纳一下"), LlmStreamEvent.Done("tool_calls")),
            listOf(LlmStreamEvent.ContentDelta("子答案"), LlmStreamEvent.Done("stop")),
            listOf(LlmStreamEvent.ContentDelta("父收尾"), LlmStreamEvent.Done("stop")),
        ))
        val loop = AgentLoop(llm, ToolRegistry(), subAgents = newRegistry(researcher))
        val session = AgentSession(skillRegistry = SkillRegistry(), basePersona = "p")
        val events = loop.run(session, "go").toList()

        assertNotNull(events.firstOrNull { it is AgentEvent.SubAgentStarted })
        val finished = events.filterIsInstance<AgentEvent.SubAgentFinished>().single()
        assertEquals("researcher", finished.agentType)
        assertEquals("子答案", finished.finalText)
        assertEquals(FinishReason.Stop, finished.reason)

        val toolCompleted = events.filterIsInstance<AgentEvent.ToolCallCompleted>()
            .single { it.call.name == SubAgentTools.NAME_CALL }
        assertEquals("子答案", (toolCompleted.result as ToolResult.Success).content)
    }

    @Test
    fun unknownAgentTypeRejected() = runTest {
        val llm = ScriptLlm(listOf(
            listOf(callSubAgent("p1", "ghost", "x"), LlmStreamEvent.Done("tool_calls")),
            listOf(LlmStreamEvent.ContentDelta("收"), LlmStreamEvent.Done("stop")),
        ))
        val loop = AgentLoop(llm, ToolRegistry(), subAgents = newRegistry(researcher))
        val events = loop.run(AgentSession(SkillRegistry(), "p"), "x").toList()

        assertTrue(events.none { it is AgentEvent.SubAgentStarted })
        val tc = events.filterIsInstance<AgentEvent.ToolCallCompleted>()
            .single { it.call.name == SubAgentTools.NAME_CALL }
        assertTrue(tc.result is ToolResult.Failure)
        assertTrue((tc.result as ToolResult.Failure).message.contains("unknown_agent_type"))
    }

    @Test
    fun depthLimitRejected() = runTest {
        val llm = ScriptLlm(listOf(
            listOf(callSubAgent("p1", "researcher", "x"), LlmStreamEvent.Done("tool_calls")),
            listOf(LlmStreamEvent.ContentDelta("收"), LlmStreamEvent.Done("stop")),
        ))
        // 直接构造一个已经触底的 session(depth = MAX_DEPTH),应当拒绝再次嵌套。
        val session = AgentSession(SkillRegistry(), "p", depth = SubAgentInvoker.MAX_DEPTH)
        val loop = AgentLoop(llm, ToolRegistry(), subAgents = newRegistry(researcher))
        val events = loop.run(session, "x").toList()

        assertTrue(events.none { it is AgentEvent.SubAgentStarted })
        val tc = events.filterIsInstance<AgentEvent.ToolCallCompleted>()
            .single { it.call.name == SubAgentTools.NAME_CALL }
        assertTrue((tc.result as ToolResult.Failure).message.contains("max_depth_reached"))
    }

    @Test
    fun childCannotRunDangerousToolEvenIfParentCould() = runTest {
        // 父放行 dangerous,但子不应继承 —— 子调 dangerous 应触发 ConfirmationDenied。
        val danger = CountingTool("danger", requiresConfirmation = true)
        val tools = ToolRegistry().apply { register(danger) }
        val pre = SubAgentPreset(
            id = "researcher", displayName = "r", description = "d",
            persona = "p", baseToolNames = setOf("danger"), maxRounds = 3,
        )
        val llm = ScriptLlm(listOf(
            listOf(callSubAgent("p1", "researcher", "x"), LlmStreamEvent.Done("tool_calls")),
            // 子第 1 轮:试图调 dangerous
            listOf(LlmStreamEvent.ToolCallDelta(0, "c1", "danger", "{}"), LlmStreamEvent.Done("tool_calls")),
            // 子第 2 轮:被拒后兜底文本
            listOf(LlmStreamEvent.ContentDelta("被拒"), LlmStreamEvent.Done("stop")),
            // 父第 2 轮:收尾
            listOf(LlmStreamEvent.ContentDelta("收"), LlmStreamEvent.Done("stop")),
        ))
        val loop = AgentLoop(
            llm, tools,
            confirmDangerous = { _, _ -> true },
            subAgents = newRegistry(pre),
        )
        val events = loop.run(AgentSession(SkillRegistry(), "p"), "x").toList()

        assertEquals(0, danger.calls)
        // 子拒绝信号会被包成 SubAgentInnerEvent(ConfirmationDenied) 浮到父 flow。
        val deniedInner = events.filterIsInstance<AgentEvent.SubAgentInnerEvent>()
            .any { it.inner is AgentEvent.ConfirmationDenied }
        assertTrue("expected ConfirmationDenied to surface from child", deniedInner)
    }
}
