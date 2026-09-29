package com.ccm.app.ui.common

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.ccm.app.core.speech.SpeechToText

/**
 * 语音输入按钮的共用逻辑（2026-09-29 —— 用户要求补能力而非置灰）。
 *
 * 三步：查 RECORD_AUDIO 权限 → 没有就拉系统授权框 → 有就开系统听写，
 * 结果整句回调给调用方（回填输入框）。
 *
 * 返回的 lambda 每次组合都是新的（内部经 rememberUpdatedState 拿最新回调），
 * 调用方直接 `clickable { voiceClick() }` 即可，无闭包过期问题。
 *
 * ```kotlin
 * val voice = rememberVoiceInput { text -> input = input + text }
 * // IconButton(onClick = voice)
 * ```
 */
@Composable
fun rememberVoiceInput(
    onText: (String) -> Unit,
    onError: ((String) -> Unit)? = null,
): () -> Unit {
    val ctx = LocalContext.current
    val currentOnText by rememberUpdatedState(onText)
    val currentOnError by rememberUpdatedState(onError)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            SpeechToText.start(
                ctx,
                onResult = { currentOnText(it) },
                onError = { msg ->
                    currentOnError?.invoke(msg) ?: toast(ctx, msg)
                },
            )
        } else {
            val m = "需要麦克风权限才能语音输入（去系统设置里开）"
            currentOnError?.invoke(m) ?: toast(ctx, m)
        }
    }

    return {
        val granted = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            SpeechToText.start(
                ctx,
                onResult = { currentOnText(it) },
                onError = { msg ->
                    currentOnError?.invoke(msg) ?: toast(ctx, msg)
                },
            )
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

private fun toast(ctx: android.content.Context, msg: String) {
    try {
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
    } catch (_: Throwable) {}
}
