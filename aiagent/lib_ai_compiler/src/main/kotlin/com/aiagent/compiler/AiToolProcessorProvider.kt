package com.aiagent.compiler

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

/**
 * KSP 入口。由 Gradle 通过 `ksp project(':aiagent:lib_ai_compiler')` 装配,扫描业务模块里
 * `@AiTool` / `@AiSkill` 注解,生成 `Tool` 实现类与 `bootAiTools_<module>()` 顶层
 * 函数,业务方在 `AgentBootstrap` 显式调用即可完成注册。
 *
 * 实例化策略由 KSP 框架管理,本类只是工厂入口。
 */
class AiToolProcessorProvider : SymbolProcessorProvider {
    override fun create(env: SymbolProcessorEnvironment): SymbolProcessor =
        AiToolProcessor(env)
}
