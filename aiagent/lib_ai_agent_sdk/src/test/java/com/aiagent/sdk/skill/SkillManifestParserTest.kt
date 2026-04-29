package com.aiagent.sdk.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SkillManifestParserTest {

    @Test
    fun parsesBlockToolList() {
        val raw = """
            ---
            name: 账号运维
            description: 查询当前用户/切换账号/解绑人脸
            tools:
              - get_current_user
              - switch_account
              - unbind_face
            ---

            # 操作手册
            正文内容
        """.trimIndent()

        val m = SkillManifestParser.parse(raw)
        assertEquals("账号运维", m.name)
        assertEquals("查询当前用户/切换账号/解绑人脸", m.description)
        assertEquals(
            setOf("get_current_user", "switch_account", "unbind_face"),
            m.toolNames,
        )
        assertTrue(m.instructions.startsWith("# 操作手册"))
        assertTrue(m.instructions.endsWith("正文内容"))
    }

    @Test
    fun parsesInlineToolList() {
        val raw = """
            ---
            name: a
            description: b
            tools: [t1, "t2", 't3']
            ---
            body
        """.trimIndent()

        val m = SkillManifestParser.parse(raw)
        assertEquals(setOf("t1", "t2", "t3"), m.toolNames)
        assertEquals("body", m.instructions)
    }

    @Test
    fun unquotesQuotedValues() {
        val raw = """
            ---
            name: "包含 冒号: 的名字"
            description: '单引号也行'
            tools:
            ---
            body
        """.trimIndent()
        val m = SkillManifestParser.parse(raw)
        assertEquals("包含 冒号: 的名字", m.name)
        assertEquals("单引号也行", m.description)
        assertEquals(emptySet<String>(), m.toolNames)
    }

    @Test
    fun missingFrontmatterFails() {
        try {
            SkillManifestParser.parse("# no frontmatter\nbody")
            fail("should throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("frontmatter"))
        }
    }

    @Test
    fun unclosedFrontmatterFails() {
        try {
            SkillManifestParser.parse("---\nname: x\ndescription: y\n")
            fail("should throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("---"))
        }
    }

    @Test
    fun missingNameFails() {
        try {
            SkillManifestParser.parse("---\ndescription: x\n---\nbody")
            fail("should throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("name"))
        }
    }
}
