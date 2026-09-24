package com.ccm.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机自启 —— 重启手机后自动拉起核心服务，让 AI 保持在线。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i("BootReceiver", "开机完成，启动 Claude Code Mobile 服务")
        try {
            val i = Intent(context, CcmService::class.java)
            context.startForegroundService(i)
        } catch (t: Throwable) {
            Log.e("BootReceiver", "启动失败", t)
        }
    }
}
