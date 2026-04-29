package com.aiagent.runtime

/**
 * 编译期 KSP 生成的工具实现在自己的 init{} 块里调用 [register] 把自己塞进来,
 * SDK(AgentRuntime)启动时调 [snapshot] 一次性拿全量。
 *
 * 设计要点:
 *  - 「sink」语义:注册表只存 [Tool] 和 [SkillManifest],不持有运行态(loaded 状态由
 *    AgentSession 管);
 *  - 线程安全:用 synchronized 兜住装机时多模块 ClassLoader 触发并发 init 的极端场景;
 *  - 重名冲突 → 抛异常,绝不静默覆盖。
 */
object AiCapabilityRegistry {

    private val lock = Any()
    private val tools = linkedMapOf<String, Tool>()
    private val skills = linkedMapOf<String, SkillManifest>()

    /** 由生成代码调用,业务侧不应手动调。 */
    fun register(tool: Tool) {
        synchronized(lock) {
            check(!tools.containsKey(tool.name)) {
                "AI tool '${tool.name}' already registered, check duplicate @AiTool"
            }
            tools[tool.name] = tool
        }
    }

    /** 由生成代码调用,声明一个 Skill 及其工具白名单。 */
    fun registerSkill(manifest: SkillManifest) {
        synchronized(lock) {
            check(!skills.containsKey(manifest.id)) {
                "AI skill '${manifest.id}' already registered, check duplicate @AiSkill"
            }
            skills[manifest.id] = manifest
        }
    }

    /** 全量工具,按注册顺序。SDK 在 boot 时调一次,把它们灌进自己的 ToolRegistry。 */
    fun snapshotTools(): List<Tool> = synchronized(lock) { tools.values.toList() }

    /** 全量 Skill,按注册顺序。 */
    fun snapshotSkills(): List<SkillManifest> = synchronized(lock) { skills.values.toList() }

    /** 仅测试用,清空注册状态。生产代码不要调。 */
    fun resetForTesting() {
        synchronized(lock) {
            tools.clear()
            skills.clear()
        }
    }
}

/**
 * 由 KSP 从 [com.aiagent.annotations.AiSkill] 生成,描述一个代码版 Skill。
 *
 * 与 markdown 版 Skill(`assets/skills/<id>/SKILL.md`)等价,只是写法不同:
 *  - markdown 版:在 SDK 启动时手动 `register(AssetSkill(id="..."))`;
 *  - 代码版:KSP 自动注册,无需在 bootstrap 里写代码。
 *
 * SDK 内会把它适配成 `Skill` 接口(toolNames 直接用本字段,instructions 来自 KDoc 或空)。
 */
data class SkillManifest(
    val id: String,
    val name: String,
    val description: String,
    val toolNames: Set<String>,
    /** 详细操作指引,可空。markdown 版从 SKILL.md 读;代码版默认 null,由 SDK 兜成 description。 */
    val instructions: String? = null,
)
