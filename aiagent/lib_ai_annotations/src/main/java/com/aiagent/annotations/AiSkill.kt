package com.aiagent.annotations

/**
 * 把一个 `object` 标记为 Skill 入口,集合下面带 [AiTool] 注解的函数为一组「可挂载工作包」。
 *
 * 没标 [AiSkill] 的 `object` 里 [AiTool] 函数也会被注册,只是默认无 skill 归属
 * (常驻可用)。需要做 Claude 风格 progressive disclosure 时,再用本注解打组。
 *
 * 标注示例:
 * ```
 *   @AiSkill(
 *       id = "account_ops",
 *       name = "账号运维",
 *       description = "查询当前用户 / 切换账号 / 解绑人脸",
 *   )
 *   object AccountOpsAi {
 *       @AiTool(...) suspend fun getCurrentUser(): String { ... }
 *       @AiTool(...) suspend fun switchAccount(): String { ... }
 *   }
 * ```
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class AiSkill(
    /** Skill 唯一 id。AssetSkill 走 markdown 时与 `assets/skills/<id>/SKILL.md` 一致。 */
    val id: String,

    /** 给模型看的简短名字,如 "账号运维"。 */
    val name: String,

    /**
     * 一两句话说清能力边界,会进 system 提示用于 progressive disclosure 路由。
     *
     * 留空时(默认)KSP 会自动用本 skill 下所有 [AiTool] 的 description 聚合出一份,
     * 加新工具时只需在工具自己的 description 里写明关键词,无需同步维护本字段。
     * 非空时原样使用,用于需要更精炼概括或额外业务约束的场景。
     */
    val description: String = "",
)
