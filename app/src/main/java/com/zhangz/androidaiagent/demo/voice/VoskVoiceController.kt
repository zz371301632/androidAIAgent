package com.zhangz.androidaiagent.demo.voice

import android.content.Context
import android.util.Log
import com.aiagent.sdk.voice.Availability
import com.aiagent.sdk.voice.VoiceController
import com.aiagent.sdk.voice.VoiceEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/**
 * 基于 Vosk 离线 ASR 的 [VoiceController] 实现(demo 注入)。
 *
 * 模型分发:`assets/vosk-model-small-cn-0.22.zip`(~42MB,Gradle `downloadVoskModel`
 * 任务幂等下载)。首次 [prepare] 时把 zip 解压到 `filesDir/vosk-model-small-cn-0.22/`
 * 并写一个 `.ready` 标记文件用于后续启动跳过解压。
 *
 * 状态投影:解压期间通过 [availability] 流推 `Preparing(0..99)`,完成转 [Availability.Ready]
 * 失败转 [Availability.Unavailable];UI 据此渲染 mic 按钮。
 *
 * 单段识别:[start] 走 [callbackFlow],创建 [Recognizer] + [SpeechService](内部 AudioRecord),
 * 监听器把 partial / final / error / timeout 翻译成 [VoiceEvent];Flow 取消(awaitClose)
 * 时停掉 SpeechService 并 close [Recognizer],下一段重新创建,无泄漏。
 */
class VoskVoiceController(private val appContext: Context) : VoiceController {

    private val _availability = MutableStateFlow<Availability>(Availability.Unavailable("点 mic 准备模型"))
    override val availability: StateFlow<Availability> = _availability.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preparing = AtomicBoolean(false)
    @Volatile private var model: Model? = null
    @Volatile private var speechService: SpeechService? = null
    @Volatile private var recognizer: Recognizer? = null
    private var prepareJob: Job? = null

    override fun prepare() {
        if (_availability.value is Availability.Ready) return
        if (!preparing.compareAndSet(false, true)) return
        prepareJob = scope.launch {
            try {
                _availability.value = Availability.Preparing(0)
                val modelDir = ensureModelExtracted()
                val m = Model(modelDir.absolutePath)
                model = m
                _availability.value = Availability.Ready
            } catch (t: Throwable) {
                Log.e(TAG, "prepare failed", t)
                _availability.value = Availability.Unavailable(t.message ?: "模型准备失败")
            } finally {
                preparing.set(false)
            }
        }
    }

    override fun start(): Flow<VoiceEvent> = callbackFlow {
        val m = model
        if (m == null) {
            trySend(VoiceEvent.Error("模型未就绪"))
            close(); return@callbackFlow
        }
        val rec = Recognizer(m, SAMPLE_RATE)
        val ss = try {
            SpeechService(rec, SAMPLE_RATE)
        } catch (t: Throwable) {
            rec.close()
            trySend(VoiceEvent.Error(t.message ?: "麦克风不可用,检查 RECORD_AUDIO 权限"))
            close(); return@callbackFlow
        }
        recognizer = rec
        speechService = ss
        val listener = object : RecognitionListener {
            override fun onPartialResult(hypothesis: String?) {
                val txt = parseField(hypothesis, "partial")
                if (txt.isNotEmpty()) trySend(VoiceEvent.Partial(txt))
            }
            // mid-segment 自动断句结果忽略 —— PTT 场景以松手 stop() 触发的 Final 为准
            override fun onResult(hypothesis: String?) = Unit
            override fun onFinalResult(hypothesis: String?) {
                trySend(VoiceEvent.Final(parseField(hypothesis, "text")))
                close()
            }
            override fun onError(exception: Exception?) {
                trySend(VoiceEvent.Error(exception?.message ?: "Vosk 识别错误"))
                close()
            }
            override fun onTimeout() {
                trySend(VoiceEvent.Final(""))
                close()
            }
        }
        ss.startListening(listener)
        awaitClose {
            runCatching { ss.stop() }
            runCatching { ss.shutdown() }
            runCatching { rec.close() }
            speechService = null
            recognizer = null
        }
    }

    /** 松手:让 SpeechService 收尾本段并通过 onFinalResult 给出 Final。 */
    override fun stop() {
        runCatching { speechService?.stop() }
    }

    /**
     * 解压 assets/[MODEL_ZIP_NAME] 到 filesDir;zip 顶层目录名即 [MODEL_DIR_NAME],解压后
     * 路径 = `filesDir/vosk-model-small-cn-0.22`。已存在 `.ready` 标记直接复用。
     * 进度 = 已读 zip 字节 / 总 zip 字节(`AssetFileDescriptor.declaredLength`,因 build
     * 配置了 `noCompress("zip")` 所以是真实长度)。
     */
    private fun ensureModelExtracted(): File {
        val targetDir = File(appContext.filesDir, MODEL_DIR_NAME)
        val marker = File(targetDir, ".ready")
        if (marker.exists()) return targetDir
        if (targetDir.exists()) targetDir.deleteRecursively()
        targetDir.mkdirs()

        val totalBytes = runCatching {
            appContext.assets.openFd(MODEL_ZIP_NAME).use { it.declaredLength }
        }.getOrDefault(-1L)
        var readBytes = 0L
        var lastPct = -1
        appContext.assets.open(MODEL_ZIP_NAME).use { raw ->
            val counting = object : FilterInputStream(raw) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n > 0 && totalBytes > 0) {
                        readBytes += n
                        val pct = (readBytes * 100 / totalBytes).toInt().coerceIn(0, 99)
                        if (pct != lastPct) {
                            lastPct = pct
                            _availability.value = Availability.Preparing(pct)
                        }
                    }
                    return n
                }
            }
            ZipInputStream(counting).use { zin ->
                val buf = ByteArray(BUF_SIZE)
                val baseCanonical = appContext.filesDir.canonicalPath
                while (true) {
                    val entry = zin.nextEntry ?: break
                    val outFile = File(appContext.filesDir, entry.name)
                    if (!outFile.canonicalPath.startsWith(baseCanonical)) {
                        throw SecurityException("zip entry escapes filesDir: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out ->
                            while (true) {
                                val n = zin.read(buf); if (n < 0) break
                                out.write(buf, 0, n)
                            }
                        }
                    }
                    zin.closeEntry()
                }
            }
        }
        marker.createNewFile()
        return targetDir
    }

    private fun parseField(hypothesis: String?, key: String): String {
        if (hypothesis.isNullOrBlank()) return ""
        return runCatching { JSONObject(hypothesis).optString(key, "") }.getOrDefault("")
    }

    companion object {
        private const val TAG = "VoskVoiceController"
        private const val MODEL_ZIP_NAME = "vosk-model-small-cn-0.22.zip"
        private const val MODEL_DIR_NAME = "vosk-model-small-cn-0.22"
        private const val SAMPLE_RATE = 16000.0f
        private const val BUF_SIZE = 16 * 1024
    }
}
