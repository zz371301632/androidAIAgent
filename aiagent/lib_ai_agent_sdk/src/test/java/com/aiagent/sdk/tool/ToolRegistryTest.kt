package com.aiagent.sdk.tool

import com.aiagent.runtime.Tool
import com.aiagent.runtime.ToolResult
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ToolRegistryTest {

    private class EchoTool(override val name: String = "echo") : Tool {
        override val description: String = "echo back the message"
        override val parametersJsonSchema: String = """
            {"type":"object","properties":{"msg":{"type":"string"}},"required":["msg"]}
        """.trimIndent()
        override suspend fun execute(args: JSONObject): ToolResult =
            ToolResult.Success("echo:" + args.optString("msg"))
    }

    private class BoomTool : Tool {
        override val name: String = "boom"
        override val description: String = "always throws"
        override val parametersJsonSchema: String = """{"type":"object"}"""
        override suspend fun execute(args: JSONObject): ToolResult = error("kaboom")
    }

    @Test
    fun registerAndDispatch() = runTest {
        val reg = ToolRegistry()
        reg.register(EchoTool())
        val r = reg.dispatch("echo", """{"msg":"hi"}""")
        assertTrue(r is ToolResult.Success)
        assertEquals("echo:hi", (r as ToolResult.Success).content)
    }

    @Test
    fun emptyArgsBecomeEmptyObject() = runTest {
        val reg = ToolRegistry()
        reg.register(EchoTool())
        val r = reg.dispatch("echo", null) as ToolResult.Success
        // 没传 msg -> optString 返回 ""
        assertEquals("echo:", r.content)
    }

    @Test
    fun unknownToolReturnsFailure() = runTest {
        val reg = ToolRegistry()
        val r = reg.dispatch("ghost", "{}")
        assertTrue(r is ToolResult.Failure)
        assertTrue((r as ToolResult.Failure).message.startsWith("unknown_tool:"))
    }

    @Test
    fun badJsonReturnsFailure() = runTest {
        val reg = ToolRegistry()
        reg.register(EchoTool())
        val r = reg.dispatch("echo", "not-json{")
        assertTrue(r is ToolResult.Failure)
        assertTrue((r as ToolResult.Failure).message.startsWith("bad_arguments:"))
    }

    @Test
    fun exceptionInsideExecuteIsCaught() = runTest {
        val reg = ToolRegistry()
        reg.register(BoomTool())
        val r = reg.dispatch("boom", "{}")
        assertTrue(r is ToolResult.Failure)
        assertTrue((r as ToolResult.Failure).message.contains("kaboom"))
    }

    @Test
    fun duplicateRegistrationFails() {
        val reg = ToolRegistry()
        reg.register(EchoTool())
        try {
            reg.register(EchoTool())
            fail("should have thrown")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("echo"))
        }
    }

    @Test
    fun listSchemasRespectsFilter() {
        val reg = ToolRegistry()
        reg.register(EchoTool("a"))
        reg.register(EchoTool("b"))
        reg.register(EchoTool("c"))
        val all = reg.listSchemas()
        assertEquals(3, all.size)
        val filtered = reg.listSchemas(setOf("a", "c"))
        assertEquals(listOf("a", "c"), filtered.map { it.name })
    }

    @Test
    fun unregisterReturnsRemoved() {
        val reg = ToolRegistry()
        val t = EchoTool()
        reg.register(t)
        assertNotNull(reg.unregister("echo"))
        assertNull(reg.get("echo"))
    }
}
