package com.aiagent.sdk.log

/**
 * SDK 内部日志单例入口。装机时(通常在 Application 起步阶段)调一次
 * [install],之后 SDK 各处 `AgentLoggerHolder.logger.loop("...")` 即可。
 *
 * 之所以拆出 holder 而不是把方法直接挂在 [AgentLogger] 静态上:
 *  - SDK 不应假定有「全局唯一」的 logger,集成方可以在测试 / 多进程场景换实现;
 *  - 接口本身保持纯净,便于实现侧 mock / spy。
 */
object AgentLoggerHolder {

    @Volatile
    var logger: AgentLogger = NoopAgentLogger
        private set

    /** 注入业务侧的日志实现。重复调用以最后一次为准。 */
    fun install(logger: AgentLogger) {
        this.logger = logger
    }
}
