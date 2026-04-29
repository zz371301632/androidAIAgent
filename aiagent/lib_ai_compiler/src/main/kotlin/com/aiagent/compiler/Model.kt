package com.aiagent.compiler

import com.google.devtools.ksp.symbol.KSClassDeclaration

/**
 * 解析阶段从 KSP 抽出来的中性数据,后面 SchemaWriter / SourceWriter 只读这个,
 * 不再触碰 KSP API。这样写测试 / 二次维护都更轻。
 */

/** 一个 `@AiTool` 标注函数的全部用得上的元信息。 */
internal data class ParsedTool(
    /** 工具所属 `object` 的 FQN,如 `com.example.app.ai.AccountOpsAi`。 */
    val ownerFqn: String,
    /** 函数名(原始 Kotlin 标识符,用于生成调用代码)。 */
    val functionName: String,
    /** 工具名(模型可见,snake_case,默认从 functionName 推)。 */
    val toolName: String,
    /** 工具描述。 */
    val description: String,
    /** 是否需要二次确认。 */
    val requiresConfirmation: Boolean,
    /** 分类标签。 */
    val category: String,
    /** 参数列表,顺序与函数声明一致。 */
    val params: List<ParsedParam>,
    /** 函数返回类型是否是 `Unit`(true 时由生成代码兜底成 `ToolResult.Success("ok")`)。 */
    val returnsUnit: Boolean,
)

/** 单个参数的元信息。 */
internal data class ParsedParam(
    /** 参数名(原始 Kotlin 标识符)。 */
    val name: String,
    /** 给模型看的描述,可空。 */
    val description: String,
    /** 参数语义类型。 */
    val type: ParamType,
    /** 是否可空(Kotlin `T?`)。 */
    val nullable: Boolean,
    /** 是否在 Kotlin 声明里写了默认值。 */
    val hasDefault: Boolean,
    /** 仅当 [type] 为 [ParamType.Enum] 时非空,对应 enum 类的 FQN 与可选枚举值列表。 */
    val enumDecl: EnumDecl? = null,
) {
    /** 在 JSON Schema `required` 列表里出现的条件。 */
    val isRequired: Boolean get() = !nullable && !hasDefault
}

internal data class EnumDecl(
    val fqn: String,
    val entries: List<String>,
)

/** MVP 支持的参数类型集合。 */
internal enum class ParamType(val jsonSchemaType: String) {
    STRING("string"),
    INT("integer"),
    LONG("integer"),
    BOOLEAN("boolean"),
    DOUBLE("number"),
    FLOAT("number"),
    /** Enum 在 JSON Schema 里也是 string,但带 `enum` 限制。 */
    Enum("string"),
}

/** `@AiSkill` 标注的 `object` 解析结果。 */
internal data class ParsedSkill(
    val ownerFqn: String,
    val id: String,
    val name: String,
    val description: String,
    /** 该 skill 下挂的工具名(snake_case),由处理器在收集阶段填好。 */
    val toolNames: List<String>,
)
