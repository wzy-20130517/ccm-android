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
            onError("正在识别中，请稍候")
            return
        }
        val r = try {
            // 用 applicationContext —— 防止 Activity 泄漏（识别会话跨配置变化）
            SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
        } catch (t: Throwable) {
            // 有设备不带 Google/系统识别服务 —— 明确报错而不是静默失败
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
                    SpeechRecognizer.ERROR_CLIENT -> "客户端出错，请重试"
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
