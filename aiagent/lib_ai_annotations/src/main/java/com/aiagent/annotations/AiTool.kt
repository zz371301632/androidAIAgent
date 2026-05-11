package com.aiagent.annotations

/**
 * 把一个函数标记为「可被 AI Agent 调用的工具」。
 *
 * 编译期 KSP 处理器(`lib_ai_compiler`)会扫描全部 [AiTool],为每个函数生成一个
 * 实现 [com.aiagent.runtime.Tool] 的对象,并通过其 init{} 块注册到
 * [com.aiagent.runtime.AiCapabilityRegistry]。
 *
 * 标注约束(KSP 会校验):
 *  - 必须是 `object` 的成员函数或顶层函数(实例字段无法在编译期 new);
 *  - 必须 `suspend`;
 *  - 返回类型必须是 String(已序列化好的 JSON / 文本,直接回灌给模型);
 *  - 参数类型限于:String / Int / Long / Boolean / Double / Float / enum,
 *    可空 / 有默认值 / 无参 都支持;Unit 参数禁用。
 *
 * 业务侧示例:
 * ```
 *   object AccountOpsAi {
 *       @AiTool(description = "查询当前登录用户的 userId / 阶段")
 *       suspend fun getCurrentUser(): String { ... }
 *
 *       @AiTool(description = "退出登录并跳到登录页", requiresConfirmation = true)
 *       suspend fun switchAccount(): String { ... }
 *   }
 * ```
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class AiTool(
    /**
     * 工具名(模型在 tool_calls 里看到的 name)。留空则使用函数名 snake_case 化:
     * `getCurrentUser` → `get_current_user`。
     */
    val name: String = "",

    /**
     * 工具中文名,用于在 QuickToolBar 等 UI 组件中展示给用户。
     * 留空时 UI 回退到 [name]。对模型不可见。
     */
    val nameCN: String = "",

    /** 给模型看的功能简介。一句话讲清楚「能做什么 / 用在什么场景」。 */
    val description: String,

    /**
     * 是否高危:true 时 AgentLoop 会先弹用户确认,确认通过才会真正执行。
     * 切账号 / 删数据 / 解绑生物认证 等不可逆操作建议设 true。
     */
    val requiresConfirmation: Boolean = false,

    /**
     * 工具分类标签,用于 UI 分组展示与日志归类(对模型不可见)。
     * 留空表示未分类。规范例子:`account` / `network` / `debug`。
     */
    val category: String = "",
)
