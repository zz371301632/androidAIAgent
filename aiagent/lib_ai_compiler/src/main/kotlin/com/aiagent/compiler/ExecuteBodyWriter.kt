package com.aiagent.compiler

import java.io.Writer

/**
 * 生成 [ParsedTool] 的 `execute` 方法体。
 *
 * 三类参数处理:
 *  - 必填(非空 + 无默认)\t→ 直接 args.getXxx,缺失就抛 JSONException 走 catch;
 *  - 可空(`T?`,无默认)\t→ args 没有或为 JSON null 时给 null;
 *  - 有默认值\t\t→ 用 `hasXxx` 标记是否提供,powerset 拼接命名调用。
 *
 * 有默认值参数数量 > 4 时拒绝处理(2^4=16 分支,再多调用面爆炸,这种函数应拆 tool)。
 */
internal object ExecuteBodyWriter {

    private const val MAX_DEFAULT_PARAMS = 4

    fun write(w: Writer, t: ParsedTool, indent: String) {
        val defaultParams = t.params.filter { it.hasDefault }
        require(defaultParams.size <= MAX_DEFAULT_PARAMS) {
            "@AiTool ${t.toolName}: too many default-valued parameters " +
                "(${defaultParams.size}, max $MAX_DEFAULT_PARAMS). Split into smaller tools."
        }
        // 1) 抽参数
        for (p in t.params) writeParamDecl(w, p, indent)

        // 2) 拼调用。Unit 返回时不需要 `val result: String = ` 前缀,fire-and-forget。
        val valDecl = if (t.returnsUnit) "" else "val result: String = "
        if (defaultParams.isEmpty()) {
            writeStraightCall(w, t, indent, valDecl)
        } else {
            writePowersetCall(w, t, defaultParams, indent, valDecl)
        }
        // 3) 落 Success。Unit 返回时统一兜底为 "ok",免去业务方手写状态串。
        val resultExpr = if (t.returnsUnit) "\"ok\"" else "result"
        w.write("${indent}ToolResult.Success($resultExpr)\n")
    }

    private fun writeParamDecl(w: Writer, p: ParsedParam, indent: String) {
        when {
            p.hasDefault -> {
                w.write("${indent}val ${has(p)} = args.has(\"${p.name}\") && !args.isNull(\"${p.name}\")\n")
                w.write("${indent}val ${p.name} = if (${has(p)}) ${extract(p)} else ${placeholder(p)}\n")
            }
            p.nullable -> {
                w.write("${indent}val ${p.name} = if (!args.has(\"${p.name}\") || args.isNull(\"${p.name}\")) null else ${extract(p)}\n")
            }
            else -> {
                w.write("${indent}val ${p.name} = ${extract(p)}\n")
            }
        }
    }

    private fun writeStraightCall(w: Writer, t: ParsedTool, indent: String, valDecl: String) {
        val args = t.params.joinToString(", ") { "${it.name} = ${it.name}" }
        w.write("${indent}${valDecl}${t.ownerFqn}.${t.functionName}($args)\n")
    }

    /**
     * 以 default 参数的 has 位作为 N 位二进制,枚举所有 2^N 子集。
     * 必填 / 可空参数永远进调用;default 参数仅当对应 has 位为 1 才进。
     */
    private fun writePowersetCall(w: Writer, t: ParsedTool, defaults: List<ParsedParam>, indent: String, valDecl: String) {
        w.write("${indent}${valDecl}when {\n")
        val n = defaults.size
        // 高位优先,这样最完整的调用排在第一行,可读性更好
        for (mask in (1 shl n) - 1 downTo 0) {
            val cond = if (mask == 0) "else" else {
                val parts = ArrayList<String>(n)
                for (i in 0 until n) {
                    val bit = 1 shl (n - 1 - i)
                    parts += if (mask and bit != 0) has(defaults[i]) else "!${has(defaults[i])}"
                }
                parts.joinToString(" && ")
            }
            val args = buildList {
                for (p in t.params) {
                    if (p.hasDefault) {
                        val idx = defaults.indexOf(p)
                        val bit = 1 shl (n - 1 - idx)
                        if (mask and bit == 0) continue
                    }
                    add("${p.name} = ${p.name}")
                }
            }.joinToString(", ")
            w.write("$indent    $cond -> ${t.ownerFqn}.${t.functionName}($args)\n")
        }
        w.write("${indent}}\n")
    }

    private fun has(p: ParsedParam): String = "has${p.name.replaceFirstChar { it.uppercaseChar() }}"

    /** 把 args 取值表达式按类型给出。enum 走 valueOf。 */
    private fun extract(p: ParsedParam): String = when (p.type) {
        ParamType.STRING -> "args.getString(\"${p.name}\")"
        ParamType.INT -> "args.getInt(\"${p.name}\")"
        ParamType.LONG -> "args.getLong(\"${p.name}\")"
        ParamType.BOOLEAN -> "args.getBoolean(\"${p.name}\")"
        ParamType.DOUBLE -> "args.getDouble(\"${p.name}\")"
        ParamType.FLOAT -> "args.getDouble(\"${p.name}\").toFloat()"
        ParamType.Enum -> "${p.enumDecl!!.fqn}.valueOf(args.getString(\"${p.name}\"))"
    }

    /**
     * default 参数缺失时的占位值。仅用于变量声明的类型推断,powerset 调用永远不会走到
     * 它(分支条件 hasX 为 false 时,该参数不会被 named 进调用)。
     */
    private fun placeholder(p: ParsedParam): String = when (p.type) {
        ParamType.STRING -> "\"\""
        ParamType.INT -> "0"
        ParamType.LONG -> "0L"
        ParamType.BOOLEAN -> "false"
        ParamType.DOUBLE -> "0.0"
        ParamType.FLOAT -> "0.0f"
        ParamType.Enum -> "${p.enumDecl!!.fqn}.values().first()"
    }
}
