package com.aiagent.sdk.skill

/**
 * SKILL.md 解析器。文件格式参考 Anthropic Agent Skills:
 *
 *   ---
 *   name: 账号运维
 *   description: 查询当前用户 / 切换账号 / 解绑人脸
 *   tools:
 *     - get_current_user
 *     - switch_account
 *     - unbind_face
 *   ---
 *
 *   # 操作手册
 *   ...这里是给模型看的详细指引...
 *
 * 解析独立成纯函数方便单测,不碰 IO。语法故意只支持最小子集:
 *   - frontmatter 必须以单独一行 `---` 开头,以单独一行 `---` 结尾
 *   - 每条键值是 `key: value` 或 `key:` 后面接 `- item` 列表
 *   - 不支持嵌套对象 / 多行字符串,够用就行
 *
 * 注:类名故意区别于 [com.aiagent.runtime.SkillManifest](后者由 KSP 生成,带 id 字段)。
 * 本文件聚焦 markdown 资产场景,id 由调用方按目录名提供。
 */
data class AssetSkillManifest(
    val name: String,
    val description: String,
    val toolNames: Set<String>,
    val instructions: String,
)

object SkillManifestParser {

    private const val DELIM = "---"

    fun parse(raw: String): AssetSkillManifest {
        val text = raw.replace("\r\n", "\n").trimStart()
        require(text.startsWith("$DELIM\n") || text.startsWith("$DELIM\r")) {
            "SKILL.md 必须以 '---' 起始的 YAML frontmatter 开头"
        }
        val lines = text.split('\n')
        var endIdx = -1
        for (i in 1 until lines.size) {
            if (lines[i].trim() == DELIM) {
                endIdx = i
                break
            }
        }
        require(endIdx > 0) { "SKILL.md frontmatter 没有闭合 '---'" }

        val frontmatter = lines.subList(1, endIdx)
        val body = lines.subList(endIdx + 1, lines.size).joinToString("\n").trim()

        var name: String? = null
        var description: String? = null
        val tools = linkedSetOf<String>()
        var collectingTools = false

        for (rawLine in frontmatter) {
            val line = rawLine.trimEnd()
            if (line.isBlank()) continue
            if (collectingTools && line.trimStart().startsWith("- ")) {
                tools.add(line.trimStart().removePrefix("- ").trim())
                continue
            }
            collectingTools = false
            val colon = line.indexOf(':')
            if (colon < 0) continue
            val key = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            when (key) {
                "name" -> name = value.unquote()
                "description" -> description = value.unquote()
                "tools" -> {
                    if (value.isEmpty()) {
                        collectingTools = true
                    } else {
                        value.trim('[', ']').split(',').forEach {
                            val v = it.trim().unquote()
                            if (v.isNotEmpty()) tools.add(v)
                        }
                    }
                }
            }
        }

        return AssetSkillManifest(
            name = requireNotNull(name) { "SKILL.md frontmatter 缺少 name" },
            description = requireNotNull(description) { "SKILL.md frontmatter 缺少 description" },
            toolNames = tools.toSet(),
            instructions = body,
        )
    }

    private fun String.unquote(): String =
        when {
            length >= 2 && startsWith('"') && endsWith('"') -> substring(1, length - 1)
            length >= 2 && startsWith('\'') && endsWith('\'') -> substring(1, length - 1)
            else -> this
        }
}
