package com.ccm.app.core.market

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 市场安装/卸载的全局进度状态（对齐 PluginDeployState 的模式）。
 *
 * 【两个动机】
 * 1. **切页不断**：原来安装跑在 MarketScreen 的 rememberCoroutineScope ——
 *    用户返回主页窗口离开 composition → 协程取消 → 下载断在半路。
 *    现在跑在 AppGraph.appScope（应用级），与窗口生命周期无关。
 * 2. **进度可见**：原来日志渲染在滚动列表**最底部** —— 点下载后要滚到底
 *    才看得到有没有开始、进行到哪。进度条移到 tab 条下方固定位置，
 *    不随列表滚动，UI 直接绑本单例的字段。
 */
object MarketInstallState {
    /** 是否有安装/卸载在跑 */
    var running by mutableStateOf(false)

    /** 正在操作的条目（id + 显示名，卡片高亮与标题用） */
    var targetId by mutableStateOf("")
    var targetName by mutableStateOf("")

    /** 操作类型（install / uninstall），进度条文案用 */
    var action by mutableStateOf("")

    /** 实时日志（onLog 逐行回传，UI 显示最后一行 + 可展开） */
    var log by mutableStateOf("")

    /** 结果提示（成功/失败文案；running=false 后显示，可手动清除） */
    var result by mutableStateOf("")

    /** 完成信号：每完成一次 +1 —— MarketScreen 订阅它触发 refresh */
    var doneTick by mutableStateOf(0)

    /**
     * 投递一次操作（由 UI 在协程里调用，实际执行仍在 appScope ——
     * 调用方只负责拼好任务；统一放这里是为了所有入口共用一份状态协议）。
     */
    fun begin(id: String, name: String, action: String) {
        running = true
        targetId = id
        targetName = name
        this.action = action
        log = ""
        result = ""
    }

    /** 追加一行日志（工作线程可调，Compose state 写入线程安全） */
    fun appendLog(line: String) {
        if (line.isNotBlank()) log = line
    }

    /** 完成（成功或失败都走这里；[result] 为结果文案） */
    fun finish(result: String) {
        running = false
        this.result = result
        doneTick++
    }

    /** 用户清掉结果提示 */
    fun clearResult() {
        result = ""
        log = ""
    }
}
