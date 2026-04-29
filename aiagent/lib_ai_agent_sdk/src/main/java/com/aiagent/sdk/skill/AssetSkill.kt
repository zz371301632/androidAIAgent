package com.aiagent.sdk.skill

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 把 assets/skills/<id>/SKILL.md 一份 markdown 文件包装成 [Skill]。
 *
 * frontmatter 给 metadata,正文做指引。首次访问 [name]/[description]/[toolNames]
 * 会触发同步解析(只读 frontmatter,文件再大也没事),正文只在 [loadInstructions]
 * 第一次被调用时才进内存,之后缓存复用。
 *
 * 与原 module_ai_agent 版本相比,**Context 改成显式构造参数**:SDK 不能假设宿主项目
 * 有 BaseApplication 之类的全局 context,集成方在创建 AssetSkill 时传 Application
 * Context 即可。
 */
class AssetSkill(
    private val context: Context,
    override val id: String,
    private val assetPath: String = "skills/$id/SKILL.md",
) : Skill {

    private val manifest: AssetSkillManifest by lazy { readManifest() }

    private val bodyLock = Mutex()
    @Volatile private var cachedBody: String? = null

    override val name: String get() = manifest.name
    override val description: String get() = manifest.description
    override val toolNames: Set<String> get() = manifest.toolNames

    override suspend fun loadInstructions(): String {
        cachedBody?.let { return it }
        return bodyLock.withLock {
            cachedBody ?: withContext(Dispatchers.IO) {
                manifest.instructions
            }.also { cachedBody = it }
        }
    }

    private fun readManifest(): AssetSkillManifest {
        val raw = context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return SkillManifestParser.parse(raw)
    }
}
