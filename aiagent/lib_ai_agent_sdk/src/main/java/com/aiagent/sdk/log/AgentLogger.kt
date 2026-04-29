package com.aiagent.sdk.log

/**
 * SDK 通用日志接口。SDK **不直接依赖任何宿主项目的日志实现**(TLog / Logger / Timber 都不行),
 * 集成方在装机时通过 [AgentLoggerHolder.install] 注入自己的实现,SDK 内部一律走 holder。
 *
 * 五条 channel 各自的语义与原 module_ai_agent 的 `AiAgent_*` tag 严格对应:
 *  - [vm]      UI / ViewModel 层(用户输入、取消、危险动作确认)。SDK 内部不会用,留给上层。
 *  - [loop]    AgentLoop:轮次、assistant 收尾、工具开始/结束、循环结束。
 *  - [req]     发给大模型的请求(URL / provider / model / body,不含 Authorization)。
 *  - [resp]    流式返回(content / tool_call delta、HTTP 错误、SSE Done)。
 *  - [loopError] / [respError] 循环 / 流式响应内的异常,带 throwable 供落盘上传。
 *
 * 所有方法默认空实现,集成方按需 override。
 */
interface AgentLogger {
    fun vm(msg: String) {}
    fun loop(msg: String) {}
    fun req(msg: String) {}
    fun resp(msg: String) {}
    fun loopError(msg: String, t: Throwable? = null) {}
    fun respError(msg: String, t: Throwable? = null) {}
}

/** 空实现:未注入时使用。生产配置务必通过 [AgentLoggerHolder.install] 替换。 */
object NoopAgentLogger : AgentLogger
