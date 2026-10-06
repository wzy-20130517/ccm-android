package com.ccm.app.core.speech

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * 语音转文字（2026-09-29 —— 用户要求「没能力就补上，不要置灰逃避」）。
 *
 * 用**系统内置** SpeechRecognizer（Android 自带，无第三方依赖、无网络费用）：
 * - 录音权限：`RECORD_AUDIO` 由调用方在 UI 层请求（本类不碰权限）
 * - 语言：默认 zh-CN（设置页可考虑后续暴露）
 * - 一次性会话：start() 创建、onResults/onError 后自动关闭（不常驻，
 *   省电也不占系统的识别服务）
 *
 * 调用流程：
 * ```
 * SpeechToText.start(
 *     onResult = { text -> input += text },
 *     onError = { msg -> Toast },
 * )
 * ```
 * 要求**主线程**调用（SpeechRecognizer 明确要求）。
 */
object SpeechToText {

    private var recognizer: SpeechRecognizer? = null

    /** 上次 start 的时间（用于「卡死会话」超时兜底，见下）。 */
    private var lastStartAt: Long = 0

    /**
     * 会话超时（ms）—— 超过这个时长还挂着，视为卡死，允许新会话顶替。
     *
     * 【为什么需要】recognizer 是 object 的静态字段（进程级）。如果某次
     * 识别**既没回调 onResults 也没回调 onError**（ROM 的识别服务被杀、
     * 服务端无响应等），recognizer 会永远非 null —— 之后每次点麦克风都
     * 只得到「正在识别中，请稍候」，**再也用不了**（用户报「语音键不可用」
     * 的一种可能形态）。
     *
     * 60s：正常语音输入最长也就几十秒，超过必然异常。
     */
    private const val SESSION_TIMEOUT_MS = 60_000L

    /** 是否有设备在跑（防重复点麦克风起两个会话）。 */
    val isActive: Boolean get() = recognizer != null

    /**
     * 开始听写。
     *
     * @param onResult 识别成功（整句，已拼好）
     * @param onError  失败回调（含权限被拒/无网络/无服务等，UI 自行提示）
     * @param locale   识别语言，默认 zh-CN
     */
    @JvmStatic
    fun start(
        context: android.content.Context,
        onResult: (String) -> Unit,
        onError: (String) -> Unit = {},
        locale: Locale = Locale.CHINA,
    ) {
        if (recognizer != null) {
            // 卡死兜底：超过 SESSION_TIMEOUT_MS 的会话直接销毁重来，
            // 否则用户会永久卡在「正在识别中」（见 SESSION_TIMEOUT_MS 注释）
            val stuck = System.currentTimeMillis() - lastStartAt > SESSION_TIMEOUT_MS
            if (stuck) {
                finish()
            } else {
                onError("正在识别中，请稍候")
                return
            }
        }
        lastStartAt = System.currentTimeMillis()
        // 【2026-10-06 问题36】SpeechRecognizer 必须在**主线程**创建/调用，
        // 否则直接报 ERROR_CLIENT（Android 文档明确要求）。
        // Compose 的回调默认在主线程，但如果调用方从协程/IO 线程来，
        // 这里主动切回主线程 —— 避免「偶发 ERROR_CLIENT」。
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                start(context, onResult, onError, locale)
            }
            return
        }
        // ══════════════════════════════════════════════════════════════
        //  【2026-10-06 用户报「返回语音识别服务出错」修复】
        //
        //  两个问题：
        //
        //  ① **不能传 applicationContext**（原来就是这么传的，还写着
        //     「防止 Activity 泄漏」）—— MIUI 等 ROM 上 SpeechRecognizer
        //     需要能绑定到**界面**的 context，传 application 会直接
        //     ERROR_CLIENT。这是社区已知问题（Google 官方文档也建议用
        //     Activity context）。泄漏问题由「用完 finish()」解决，
        //     不靠换 context。
        //
        //  ② 没做**服务可用性预检** —— SpeechRecognizer.isRecognitionAvailable()
        //     为 false 时 createSpeechRecognizer 不报错但 startListening 必失败。
        //     本机实测：voice_recognition_service 是 null，但小米自带
        //     com.xiaomi.mibrain.speech.asr.AsrService 兜底（query-services
        //     能查到）—— 所以要用 isRecognitionAvailable 判断，
        //     而不是查那个 setting。
        // ══════════════════════════════════════════════════════════════
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError(
                "本机没有可用的语音识别服务。\n" +
                    "小米机型可在 设置 → 小爱同学/语音服务 里开启，或装一个" +
                    "带识别服务的输入法/App。"
            )
            return
        }
        val r = try {
            // ⚠️ 传原 context（Activity 级）—— 见上面 ① 的说明，别改回 applicationContext
            SpeechRecognizer.createSpeechRecognizer(context)
        } catch (t: Throwable) {
            // 有设备不带识别服务 —— 明确报错而不是静默失败
            onError("系统不可用：${t.message ?: "本机没有语音识别服务"}")
            return
        }
        recognizer = r

        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                finish()
                if (!text.isNullOrEmpty()) onResult(text)
                else onError("没听清，请再试一次")
            }

            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "录音出错"
                    // 【2026-10-06 问题36】用户报「语音键点击后安卓返回客户端错误」——
                    // ERROR_CLIENT 在小米/部分 ROM 上很常见，原因通常是：
                    //   · 系统语音服务被限制（省电策略杀了）
                    //   · 没有网络（小米用讯飞，需联网）
                    //   · 上次会话没干净结束（服务忙）
                    // 给出可操作的建议，而不是干巴巴的「请重试」。
                    SpeechRecognizer.ERROR_CLIENT ->
                        "语音识别服务出错（ERROR_CLIENT）。\n" +
                            "常见原因：系统语音服务被省电策略限制 / 无网络 / 服务忙。\n" +
                            "试试：1) 检查网络 2) 系统设置→应用→语音服务→允许后台运行 3) 重开 App"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺录音权限"
                    SpeechRecognizer.ERROR_NETWORK -> "网络不可用（系统识别需要联网）"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                    SpeechRecognizer.ERROR_NO_MATCH -> "没听清，请再试一次"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务忙，请重试"
                    SpeechRecognizer.ERROR_SERVER -> "识别服务端出错"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听到声音"
                    else -> "识别失败（code=$error）"
                }
                finish()
                onError(msg)
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // 等用户说完再切（约 3s 静音）
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
        }

        try {
            r.startListening(intent)
        } catch (t: Throwable) {
            finish()
            onError("启动识别失败：${t.message}")
        }
    }

    /** 主动停止（用户取消）。 */
    @JvmStatic
    fun cancel() {
        try { recognizer?.cancel() } catch (_: Throwable) {}
        finish()
    }

    private fun finish() {
        try { recognizer?.destroy() } catch (_: Throwable) {}
        recognizer = null
    }
}
