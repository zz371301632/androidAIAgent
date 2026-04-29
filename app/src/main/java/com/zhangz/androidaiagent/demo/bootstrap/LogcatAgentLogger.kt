package com.zhangz.androidaiagent.demo.bootstrap

import android.util.Log
import com.aiagent.sdk.log.AgentLogger

/**
 * Demo 用的 [AgentLogger] 实现:把 SDK 五条 channel 桥到 Android Logcat,
 * 统一 tag 前缀 `AiAgent_`,方便 logcat 过滤:
 *
 *   adb logcat -s AiAgent_VM,AiAgent_Loop,AiAgent_Req,AiAgent_Resp
 *
 * 业务自家工程接入时,把这里的 `Log.x` 换成自家日志库(TLog / Timber / Logger)即可。
 */
object LogcatAgentLogger : AgentLogger {

    private const val TAG_PREFIX = "AiAgent_"
    private const val TAG_VM = TAG_PREFIX + "VM"
    private const val TAG_LOOP = TAG_PREFIX + "Loop"
    private const val TAG_REQ = TAG_PREFIX + "Req"
    private const val TAG_RESP = TAG_PREFIX + "Resp"

    /** 单条日志的最大长度,防止被 logcat 单行限制截走。 */
    private const val MAX_LOG_LEN = 4000

    override fun vm(msg: String) = chunked(TAG_VM, msg)
    override fun loop(msg: String) = chunked(TAG_LOOP, msg)
    override fun req(msg: String) = chunked(TAG_REQ, msg)
    override fun resp(msg: String) = chunked(TAG_RESP, msg)
    override fun loopError(msg: String, t: Throwable?) {
        if (t != null) Log.e(TAG_LOOP, msg, t) else Log.e(TAG_LOOP, msg)
    }
    override fun respError(msg: String, t: Throwable?) {
        if (t != null) Log.e(TAG_RESP, msg, t) else Log.e(TAG_RESP, msg)
    }

    private fun chunked(tag: String, msg: String) {
        if (msg.length <= MAX_LOG_LEN) {
            Log.i(tag, msg)
            return
        }
        var i = 0
        while (i < msg.length) {
            val end = minOf(i + MAX_LOG_LEN, msg.length)
            Log.i(tag, msg.substring(i, end))
            i = end
        }
    }
}
