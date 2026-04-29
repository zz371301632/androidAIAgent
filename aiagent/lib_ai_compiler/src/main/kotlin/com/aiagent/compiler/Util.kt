package com.aiagent.compiler

import com.google.devtools.ksp.symbol.KSAnnotation

internal const val AI_TOOL_FQN = "com.aiagent.annotations.AiTool"
internal const val AI_SKILL_FQN = "com.aiagent.annotations.AiSkill"

/**
 * 读取注解参数,缺省 / 类型不匹配时回落到 [default]。
 *
 * KSP 的 [KSAnnotation.arguments] 在源码里如果没显式给值,**仍然会出现在列表里**带
 * 默认值;所以这里只判 name + 类型即可,不需要管「argument 不存在」的情况。
 */
@Suppress("UNCHECKED_CAST")
internal inline fun <reified T> KSAnnotation.arg(name: String, default: T): T {
    val raw = arguments.firstOrNull { it.name?.asString() == name }?.value ?: return default
    return raw as? T ?: default
}

/**
 * camelCase / PascalCase → snake_case。
 * 例:`getCurrentUser` → `get_current_user`,`HTTPServer` → `h_t_t_p_server`
 * (后者是边角情况,实际工具名不会这么写;够用)。
 */
internal fun snakeCase(s: String): String {
    if (s.isEmpty()) return s
    val sb = StringBuilder(s.length + 4)
    s.forEachIndexed { i, c ->
        if (c.isUpperCase()) {
            if (i > 0) sb.append('_')
            sb.append(c.lowercaseChar())
        } else {
            sb.append(c)
        }
    }
    return sb.toString()
}
