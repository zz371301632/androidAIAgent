package com.aiagent.compiler

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier

/**
 * 主处理器。一次性扫所有 `@AiTool` / `@AiSkill`,做校验 + 解析,交给 SourceWriter
 * 落盘。失败用 `env.logger.error` 输出,KSP 会让编译失败而不是默默过。
 */
internal class AiToolProcessor(
    private val env: SymbolProcessorEnvironment,
) : SymbolProcessor {

    private val bootName: String =
        env.options["aiagent.bootName"]?.takeIf { it.isNotBlank() } ?: "default"
    private var done = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (done) return emptyList()
        done = true

        val toolFns = resolver.getSymbolsWithAnnotation(AI_TOOL_FQN)
            .filterIsInstance<KSFunctionDeclaration>()
            .toList()
        val skillCls = resolver.getSymbolsWithAnnotation(AI_SKILL_FQN)
            .filterIsInstance<KSClassDeclaration>()
            .toList()
        if (toolFns.isEmpty() && skillCls.isEmpty()) return emptyList()

        val tools = toolFns.mapNotNull { parseTool(it) }
        val skills = skillCls.mapNotNull { parseSkill(it, tools) }
        val sources = collectSources(toolFns, skillCls)

        SourceWriter(env, bootName).write(tools, skills, sources)
        env.logger.info(
            "lib_ai_compiler[$bootName]: generated ${tools.size} tools, ${skills.size} skills"
        )
        return emptyList()
    }

    private fun parseTool(fn: KSFunctionDeclaration): ParsedTool? {
        val parent = fn.parentDeclaration as? KSClassDeclaration
        if (parent == null || parent.classKind != ClassKind.OBJECT) {
            env.logger.error(
                "@AiTool must be declared inside an `object`: ${fn.qualifiedName?.asString()}", fn
            )
            return null
        }
        if (Modifier.SUSPEND !in fn.modifiers) {
            env.logger.error("@AiTool must be `suspend`: ${fn.qualifiedName?.asString()}", fn)
            return null
        }
        val retFqn = fn.returnType?.resolve()?.declaration?.qualifiedName?.asString()
        val returnsUnit = when (retFqn) {
            "kotlin.String" -> false
            "kotlin.Unit" -> true
            else -> {
                env.logger.error(
                    "@AiTool must return String or Unit, got $retFqn: ${fn.qualifiedName?.asString()}", fn
                )
                return null
            }
        }
        val ann = fn.annotations.firstOrNull { it.shortName.asString() == "AiTool" } ?: return null
        val params = fn.parameters.mapNotNull { parseParam(it, fn) }
        if (params.size != fn.parameters.size) return null

        return ParsedTool(
            ownerFqn = parent.qualifiedName!!.asString(),
            functionName = fn.simpleName.asString(),
            toolName = ann.arg("name", "").ifBlank { snakeCase(fn.simpleName.asString()) },
            description = ann.arg("description", ""),
            requiresConfirmation = ann.arg("requiresConfirmation", false),
            category = ann.arg("category", ""),
            params = params,
            returnsUnit = returnsUnit,
        )
    }

    private fun parseParam(p: KSValueParameter, fn: KSFunctionDeclaration): ParsedParam? {
        val pname = p.name?.asString().orEmpty()
        if (pname.isEmpty()) {
            env.logger.error("@AiTool ${fn.qualifiedName?.asString()}: anonymous parameter not supported", p)
            return null
        }
        val type = p.type.resolve()
        val decl = type.declaration as? KSClassDeclaration
        val fqn = decl?.qualifiedName?.asString()
        val (ptype, enumDecl) = mapType(decl, fqn) ?: run {
            env.logger.error(
                "@AiTool ${fn.qualifiedName?.asString()}: unsupported parameter type `$fqn` for `$pname`. " +
                    "Only String/Int/Long/Boolean/Double/Float/enum are supported in MVP.",
                p,
            )
            return null
        }
        val desc = p.annotations.firstOrNull { it.shortName.asString() == "AiParam" }
            ?.arg("description", "").orEmpty()
        return ParsedParam(
            name = pname,
            description = desc,
            type = ptype,
            nullable = type.isMarkedNullable,
            hasDefault = p.hasDefault,
            enumDecl = enumDecl,
        )
    }

    private fun mapType(decl: KSClassDeclaration?, fqn: String?): Pair<ParamType, EnumDecl?>? {
        if (decl != null && decl.classKind == ClassKind.ENUM_CLASS) {
            val entries = decl.declarations
                .filterIsInstance<KSClassDeclaration>()
                .filter { it.classKind == ClassKind.ENUM_ENTRY }
                .map { it.simpleName.asString() }
                .toList()
            return ParamType.Enum to EnumDecl(fqn = fqn!!, entries = entries)
        }
        val pt = when (fqn) {
            "kotlin.String" -> ParamType.STRING
            "kotlin.Int" -> ParamType.INT
            "kotlin.Long" -> ParamType.LONG
            "kotlin.Boolean" -> ParamType.BOOLEAN
            "kotlin.Double" -> ParamType.DOUBLE
            "kotlin.Float" -> ParamType.FLOAT
            else -> return null
        }
        return pt to null
    }

    private fun parseSkill(cls: KSClassDeclaration, allTools: List<ParsedTool>): ParsedSkill? {
        if (cls.classKind != ClassKind.OBJECT) {
            env.logger.error("@AiSkill must be on an `object`: ${cls.qualifiedName?.asString()}", cls)
            return null
        }
        val ann = cls.annotations.firstOrNull { it.shortName.asString() == "AiSkill" } ?: return null
        val ownerFqn = cls.qualifiedName!!.asString()
        val ownTools = allTools.filter { it.ownerFqn == ownerFqn }
        val name = ann.arg("name", "")
        val rawDesc = ann.arg("description", "")
        // 用户写了就用用户的;留空时聚合本 skill 下所有 tool 的 description,
        // 关键词维护回归到工具本身,加新工具不必再同步改 skill 描述。
        val description = rawDesc.ifBlank { synthesizeSkillDescription(name, ownTools) }
        return ParsedSkill(
            ownerFqn = ownerFqn,
            id = ann.arg("id", ""),
            name = name,
            description = description,
            toolNames = ownTools.map { it.toolName },
        )
    }

    private fun synthesizeSkillDescription(name: String, tools: List<ParsedTool>): String {
        if (tools.isEmpty()) return name
        val joined = tools.joinToString(separator = "; ") { it.description }
        return "$name。包含工具:$joined"
    }

    private fun collectSources(
        fns: List<KSFunctionDeclaration>,
        cls: List<KSClassDeclaration>,
    ): Set<KSFile> = buildSet {
        fns.forEach { it.containingFile?.let(::add) }
        cls.forEach { it.containingFile?.let(::add) }
    }
}
