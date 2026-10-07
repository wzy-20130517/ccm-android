package com.ccm.app.core.plugin

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 插件宿主部署的全局进度状态。
 *
 * 【为什么不用 Compose 的 remember】
 * 部署（Node + 334MB npm ci）动辄十几分钟，原来跑在 PluginPanel 的
 * rememberCoroutineScope 里 —— 用户切个页面、或虚拟副屏被系统回收
 * （实测 11 分钟不动就会），窗口销毁 → composition 离开 → scope 取消
 * → 协程被杀 → npm ci 中断，前功尽弃。
 *
 * 现在：任务跑在 AppGraph.appScope（应用级，与窗口生命周期无关），
 * 进度写进本单例（MutableState 线程安全），PluginPanel 只做订阅。
 * UI 挂了任务照跑，回来接着看进度。
 */
object PluginDeployState {
    /** 部署是否在跑 */
    var running by mutableStateOf(false)

    /** 进度日志窗口（最后 6 行，换行拼接） */
    var log by mutableStateOf("")

    /** 失败原因（成功后清空） */
    var error by mutableStateOf("")

    /** 完成信号：成功一次 +1（UI 借它触发刷新） */
    var doneTick by mutableStateOf(0)

    /** 内部日志窗口（只在部署协程里动） */
    private val window = ArrayDeque<String>()

    /** 追加一行日志（工作线程可调，Compose state 写入线程安全） */
    fun appendLog(line: String) {
        if (line.isBlank()) return
        window.addLast(line)
        while (window.size > 6) window.removeFirst()
        log = window.joinToString("\n")
    }

    fun reset() {
        running = true
        error = ""
        log = ""
        window.clear()
    }

    fun fail(message: String) {
        running = false
        if (message.isNotBlank()) {
            appendLog(message)
            error = log
        }
    }

    fun succeed() {
        running = false
        error = ""
        doneTick++
    }
}
