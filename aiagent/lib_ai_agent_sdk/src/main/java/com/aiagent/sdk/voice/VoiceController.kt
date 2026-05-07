package com.aiagent.sdk.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 语音输入控制器抽象。可插拔 —— 系统 SpeechRecognizer / Vosk / sherpa-onnx / 云 ASR
 * 都可以接进来。SDK 本身不实现任何引擎,接入方在 [com.aiagent.sdk.setup.AiAgentConfig]
 * 里注入即可。
 *
 * 单实例约束:同一时间只允许存在一段识别。重复 [start] 由实现决定语义(通常是先关掉
 * 上一段),调用方建议先 [stop] / cancel 当前段再 [start] 下一段。
 *
 * 生命周期:模型 / 引擎初始化通常是异步的(需要解压、下载、加载),通过 [availability]
 * StateFlow 暴露三态(Ready / Preparing(progress) / Unavailable);UI 层应据此渲染
 * mic 按钮的不同形态。[prepare] 触发一次准备(已 Ready 时是 no-op),首次调用
 * [start] 时实现侧也应隐式调用 [prepare]。
 */
interface VoiceController {

    /**
     * 当前可用状态;UI 层 collect 后据此渲染 mic 按钮(隐藏 / 进度 / 就绪)。
     * 默认值由实现决定:本地模型已就绪可以直接 [Availability.Ready],需要解压 / 下载
     * 的实现一般初始化为 [Availability.Unavailable] 等待用户触发 [prepare]。
     */
    val availability: StateFlow<Availability>

    /**
     * 触发模型准备(下载 / 解压 / 加载等)。已 [Availability.Ready] 时是 no-op,正在
     * [Availability.Preparing] 时也应去重避免重入。准备过程通过更新 [availability]
     * 反馈进度,完成后转 [Availability.Ready],失败转 [Availability.Unavailable]。
     */
    fun prepare()

    /**
     * 开始一段识别。返回的 [Flow] 在以下情况之一终止:
     *  - 收到 [VoiceEvent.Final]:正常识别完毕;
     *  - 收到 [VoiceEvent.Error]:引擎错误 / 权限缺失 / 超时;
     *  - 收到 [VoiceEvent.Cancelled]:被 [stop] / 收集协程取消等外因终止。
     *
     * 实现侧应在 Flow 终止前关闭底层引擎资源,允许调用方安全地起下一段。模型未
     * Ready 时应直接发 [VoiceEvent.Error] 并终止,不要在 [start] 内阻塞等待。
     */
    fun start(): Flow<VoiceEvent>

    /**
     * 主动收尾本段识别。push-to-talk「松手」语义 —— 引擎应当尽快产出本段最终结果,
     * 仍走 [VoiceEvent.Final] 通道。如果当前没有进行中的识别,本调用应是 no-op。
     */
    fun stop()
}

/** [VoiceController.availability] 的三种状态。 */
sealed interface Availability {
    /** 引擎 / 模型已就绪,可以立即 [VoiceController.start]。 */
    data object Ready : Availability

    /** 正在准备中。[progress] 范围 0..100,负数表示不确定进度。 */
    data class Preparing(val progress: Int) : Availability

    /** 不可用。[reason] 给 UI 提示用,默认空串表示「尚未准备」。 */
    data class Unavailable(val reason: String = "") : Availability
}

/** 实现可选:`MutableStateFlow<Availability>` 的便捷工厂,默认从 Unavailable 起步。 */
fun availabilityState(initial: Availability = Availability.Unavailable()): MutableStateFlow<Availability> =
    MutableStateFlow(initial)

/** [VoiceController.start] 流出的事件类型。 */
sealed interface VoiceEvent {
    /** 边说边出字的中间结果;同一段识别会发多次,文本通常是累计的。 */
    data class Partial(val text: String) : VoiceEvent

    /** 识别完毕的最终文本。空串视为本段没识别出内容,UI 应忽略不提交。 */
    data class Final(val text: String) : VoiceEvent

    /** 引擎错误。`message` 给 UI 展示用,可以是中文短句。 */
    data class Error(val message: String) : VoiceEvent

    /** 被外部取消(例如手势移出按钮区、Activity 销毁)。 */
    data object Cancelled : VoiceEvent
}
