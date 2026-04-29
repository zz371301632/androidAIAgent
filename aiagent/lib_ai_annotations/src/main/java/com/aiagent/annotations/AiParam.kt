package com.aiagent.annotations

/**
 * 描述 [AiTool] 函数的某个参数,会被 KSP 写进生成的 JSON Schema 里。
 *
 * 没标 [AiParam] 的参数也会被纳入 schema(类型 / 是否必填仍由签名推断),
 * 只是 `description` 字段会缺省;模型仍能用,但调用准确率会差一点。
 *
 * 示例:
 * ```
 *   @AiTool(description = "切换账号,可选是否清除 cookie")
 *   suspend fun switchAccount(
 *       @AiParam("是否清除本地 cookie,默认清除") clearCookie: Boolean = true,
 *   ): String
 * ```
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
annotation class AiParam(
    /** 给模型看的参数说明。 */
    val description: String = "",
)
