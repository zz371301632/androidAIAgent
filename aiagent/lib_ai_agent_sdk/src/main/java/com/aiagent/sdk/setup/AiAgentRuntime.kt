package com.aiagent.sdk.setup

import com.aiagent.runtime.AiCapabilityRegistry
import com.aiagent.runtime.Tool
import com.aiagent.sdk.agent.AgentLoop
import com.aiagent.sdk.agent.SubAgentPreset
import com.aiagent.sdk.agent.SubAgentRegistry
import com.aiagent.sdk.llm.LlmClient
import com.aiagent.sdk.llm.LlmClientFactory
import com.aiagent.sdk.llm.LlmProviderProfile
import com.aiagent.sdk.log.AgentLogger
import com.aiagent.sdk.log.AgentLoggerHolder
import com.aiagent.sdk.log.NoopAgentLogger
import com.aiagent.sdk.memory.MemoryProvider
import com.aiagent.sdk.skill.CodeSkill
import com.aiagent.sdk.skill.SkillRegistry
import com.aiagent.sdk.tool.ToolRegistry
import com.aiagent.sdk.voice.VoiceController
import org.json.JSONObject

/**
 * 接入侧的**唯一接入面板**。集成方在自家 Application.onCreate 里:
 *
 * ```
 * AiAgentRuntime.install(AiAgentConfig(
 *     kspBootstraps   = listOf(::bootAiTools_<bootName>),     // 必填,多模块就列多个
 *     persona         = "...",                                // 必填,业务自家 system 提示
 *     profile         = LlmProviderProfile.deepSeekOfficial(...), // 想接哪家直接传哪家
 *     logger          = MyAgentLogger,                        // 可选,默认 noop
 *     memory          = StaticMemory(listOf(/* user facts */)), // 可选,默认 EMPTY
 *     subAgentPresets = listOf(SubAgentPreset(...)),          // 可选,默认空(不开 sub-agent)
 * ))
 * ```
 *
 * SDK 不替接入方做任何业务向决策——空就是真空,与「不开启该能力」等价。
 */
data class AiAgentConfig(
    /**
     * 各业务模块的 KSP 生成函数 `bootAiTools_<bootName>()` 引用,装机时按列表顺序依次调用,
     * 把每个模块的 @AiTool / @AiSkill 注册进全局 [AiCapabilityRegistry]。
     * 单模块就一个;多模块按需追加,顺序无关。
     */
    val kspBootstraps: List<() -> Unit>,
    /**
     * 顶层会话 persona。SDK 不内置任何业务向回退 —— 这是接入方对「我的 Agent 是谁」的
     * 显式声明,必填。
     */
    val persona: String,
    /**
     * LLM 供应商配置;留 null 时 [AiAgentRuntime.isReady] 为 false,UI / Headless 入口
     * 应当据此拒发请求。
     */
    val profile: LlmProviderProfile? = null,
    /** SDK 五条日志 channel 的接收方;默认 noop,接入方不接日志库时一律静默。 */
    val logger: AgentLogger = NoopAgentLogger,
    /** 长期记忆检索后端;默认 EMPTY 等价于「不启用」。 */
    val memory: MemoryProvider = MemoryProvider.EMPTY,
    /** Sub-Agent 预设列表;空列表等价于不开 sub-agent,SDK 不会向模型暴露 call_sub_agent。 */
    val subAgentPresets: List<SubAgentPreset> = emptyList(),
    /**
     * 语音输入控制器;留 null 时 UI 不展示 mic 按钮。可插拔 —— 测试可以接系统
     * SpeechRecognizer,生产可以接 Vosk / sherpa-onnx / 云 ASR。
     */
    val voiceController: VoiceController? = null,
)

/**
 * 装机入口 + 装机后产物 holder。
 *
 * 装机时序(单次,重复 install 抛错):
 *  1. 把 [AiAgentConfig.logger] 注入 SDK 的 [AgentLoggerHolder];
 *  2. 顺序调每个 [AiAgentConfig.kspBootstraps],把各模块的 @AiTool / @AiSkill 灌进
 *     [AiCapabilityRegistry];
 *  3. 后续访问 [tools] / [skills] / [llmClient] / [subAgents] 时 Lazy 从全局 registry
 *     snapshot 出本运行时持有的视图。
 *
 * 装机前访问任意字段都会抛错——保证「忘 install」是显式失败,不是隐性 NPE。
 *
 * 不持有 Android `Application` / `Context` —— SDK 对宿主平台保持中立,需要 Context 的
 * 工具自行在接入侧维护(例如一个 demo `AppContextHolder`)。
 */
object AiAgentRuntime {

    @Volatile private var installed = false
    @Volatile private var _config: AiAgentConfig? = null

    /** 接入方传入的 [AiAgentConfig]。 */
    val config: AiAgentConfig
        get() = _config
            ?: error("AiAgentRuntime.install(...) 未调用,请在 Application.onCreate 里装机")

    /** 是否已具备发起 LLM 请求的条件:profile 非空且 apiKey 非空。 */
    val isReady: Boolean
        get() = _config?.profile?.apiKey?.isNotBlank() == true

    /** 全局 ToolRegistry,首次访问触发一次 snapshot;装机前访问抛错。 */
    val tools: ToolRegistry by lazy {
        config // 触发未装机检查
        ToolRegistry().apply { registerAll(AiCapabilityRegistry.snapshotTools()) }
    }

    /** 全局 SkillRegistry,首次访问触发一次 snapshot;装机前访问抛错。 */
    val skills: SkillRegistry by lazy {
        config
        SkillRegistry().apply {
            AiCapabilityRegistry.snapshotSkills().forEach { register(CodeSkill(it)) }
        }
    }

    /** 由 profile 构造的 LlmClient;profile 缺失时抛错(请先确认 [isReady])。 */
    val llmClient: LlmClient by lazy {
        val profile = config.profile
            ?: error("AiAgentConfig.profile 未配置,无法发起 LLM 请求")
        LlmClientFactory.create(profile)
    }

    /**
     * 由 [AiAgentConfig.subAgentPresets] 灌入,顺序保留。空集即不开 sub-agent,
     * AgentLoop 不会向模型暴露 `call_sub_agent`。
     */
    val subAgents: SubAgentRegistry by lazy {
        SubAgentRegistry().apply { registerAll(config.subAgentPresets) }
    }

    /** [AiAgentConfig.memory] 的快捷访问。 */
    val memory: MemoryProvider get() = config.memory

    /** [AiAgentConfig.persona] 的快捷访问。 */
    val persona: String get() = config.persona

    /** [AiAgentConfig.voiceController] 的快捷访问;装机前 / 未配置时返回 null。 */
    val voiceController: VoiceController? get() = _config?.voiceController

    /**
     * 用本运行时持有的 LLM / 工具 / SubAgent 装一个 [AgentLoop]。每次会话各起一个,
     * 共享底层 registry。`confirmDangerous` 默认放行,UI 接入方一般要传一个挂 UI 的实现。
     */
    fun newAgentLoop(
        confirmDangerous: suspend (Tool, JSONObject) -> Boolean = { _, _ -> true },
    ): AgentLoop = AgentLoop(llmClient, tools, confirmDangerous, subAgents)

    @Synchronized
    fun install(config: AiAgentConfig) {
        check(!installed) { "AiAgentRuntime 已装机,不允许重复 install" }
        _config = config
        AgentLoggerHolder.install(config.logger)
        config.kspBootstraps.forEach { it.invoke() }
        installed = true
    }
}
