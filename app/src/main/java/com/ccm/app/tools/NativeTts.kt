package com.ccm.app.tools

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 原生 TTS —— 用 Android 系统语音引擎朗读。
 *
 * 【和 Node 侧 edge-tts 的分工】
 * - edge-tts（Node）：音质好（神经网络语音），但需要联网 + 依赖网络端点
 * - 原生 TTS：离线可用、响应快，音质取决于手机装的引擎（小米/华为自带的通常还行）
 *
 * 策略：默认用原生（离线可靠），需要好音质时回退 edge-tts。
 *
 * 【初始化是异步的】
 * TextToSpeech 构造后要等 onInit 回调，这里用 latch 做同步等待，
 * 第一次调用会阻塞约 100~500ms，之后就走缓存了。
 */
object NativeTts {

    private const val TAG = "NativeTts"

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    @Volatile
    private var initFailed = false

    private val lock = Any()

    /** 是否已就绪 */
    fun isReady(): Boolean = ready

    /** 初始化（幂等，可重复调用） */
    fun ensureInit(context: Context): Boolean {
        if (ready) return true
        if (initFailed) return false

        synchronized(lock) {
            if (ready) return true

            val latch = CountDownLatch(1)
            var ok = false

            try {
                val engine = TextToSpeech(context.applicationContext) { status ->
                    ok = status == TextToSpeech.SUCCESS
                    if (ok) {
                        tts?.language = Locale.CHINA
                    }
                    latch.countDown()
                }
                tts = engine
                latch.await(5, TimeUnit.SECONDS)

                if (ok) {
                    ready = true
                    Log.i(TAG, "TTS 初始化成功")
                } else {
                    initFailed = true
                    Log.w(TAG, "TTS 初始化失败（设备可能没装语音引擎）")
                }
            } catch (t: Throwable) {
                initFailed = true
                Log.e(TAG, "TTS 异常", t)
            }
            return ready
        }
    }

    /**
     * 朗读文本。
     *
     * @param text 要读的内容
     * @param flush true = 打断当前朗读，false = 排队
     * @param rate 语速（1.0 = 正常）
     * @param pitch 音调（1.0 = 正常）
     */
    fun speak(
        context: Context,
        text: String,
        flush: Boolean = true,
        rate: Float = 1.0f,
        pitch: Float = 1.0f
    ): Boolean {
        if (!ensureInit(context)) return false
        val engine = tts ?: return false

        return try {
            engine.setSpeechRate(rate.coerceIn(0.1f, 3.0f))
            engine.setPitch(pitch.coerceIn(0.1f, 2.0f))
            val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val r = engine.speak(text, mode, null, "ccm-${System.currentTimeMillis()}")
            r == TextToSpeech.SUCCESS
        } catch (t: Throwable) {
            Log.w(TAG, "朗读失败: ${t.message}")
            false
        }
    }

    /** 停止朗读 */
    fun stop() {
        try { tts?.stop() } catch (_: Throwable) {}
    }

    /** 释放（进程退出时调） */
    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Throwable) {}
        tts = null
        ready = false
    }
}
