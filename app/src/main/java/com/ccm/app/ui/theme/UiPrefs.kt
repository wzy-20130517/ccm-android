package com.ccm.app.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * UI 偏好存储（主题 / 聊天字体）—— 2026-09-27 加。
 *
 * ## 为什么需要
 * 设置页的外观选项原来是 `remember { mutableStateOf(...) }` 本地状态：
 * 选完**重启就丢**，而且主题选择根本没消费端（`CcmApp` 永远
 * `isSystemInDarkTheme()` 跟随系统）。对标 Web：localStorage.theme。
 *
 * ## 为什么用 SharedPreferences 而不是 config.json
 * - 这是 UI 偏好，与 API 配置（AppConfig）生命周期/写入频率都不同；
 * - 主题切换是**高频小写**，SharedPreferences 内存缓存 + apply 异步落盘，
 *   每次重组读也不卡（config.json 是同步读+解析）；
 * - 全项目没有别处写 config.json 的 UI 字段，不加 `_extra` 脏数据。
 *
 * ## 为什么是 MutableState
 * 设置页改值 → [themeMode] 立即变化 → 读它的 `CcmApp` 自动重组换主题。
 * 用普通 var 得手动通知，Compose 里等于没接。
 *
 * ## init 时机
 * `MainActivity.onCreate` 最先调（setContent 之前）——晚了首帧会闪
 * 系统主题再跳到用户主题。
 */
object UiPrefs {

    private const val FILE = "ccm_ui_prefs"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_FONT = "chat_font"
    private const val KEY_SEND = "send_key"
    private const val KEY_NEWLINE = "newline_key"
    private const val KEY_COWORK_PROJECT = "cowork_project"
    private const val KEY_COWORK_DRAFT = "cowork_draft"

    /** light / auto / dark（ThemeMode.key 同集） */
    val themeMode: MutableState<String> = mutableStateOf("auto")

    /** default / sans / system / dyslexic（ChatFont.key 同集） */
    val chatFont: MutableState<String> = mutableStateOf("default")

    /**
     * 发送键行为 —— 决定输入框的 imeAction（2026-09-28 打通）。
     * 原来设置页选了只是本地 remember，输入框根本不理会：
     * 永远 ImeAction.Send（回车=发送），选「仅按钮」的用户没法换行。
     * - "仅按钮（回车只换行）"     → Default（回车换行，只能点发送钮）
     * - "回车发送（Shift+Enter 换行）" → Send（IME 出发送键）
     * - "Ctrl+Enter 发送"          → Default（Android IME 无 Ctrl，等价仅按钮）
     */
    val sendKey: MutableState<String> = mutableStateOf("仅按钮（回车只换行）")

    /** 换行键提示（纯显示 —— Android IME 的换行由 sendKey 决定）。 */
    val newlineKey: MutableState<String> = mutableStateOf("Enter")

    /**
     * 回车是否=发送（输入框 imeAction 直接读它）。
     * 「回车发送（Shift+Enter 换行）」为 true；另两项都是回车换行
     * （Android IME 没有 Ctrl 修饰键，「Ctrl+Enter 发送」等价仅按钮）。
     */
    val sendByEnter: MutableState<Boolean> = mutableStateOf(false)

    /** 协作页「在哪个项目工作」选择（第24批 —— 原来本地 remember 重启丢）。 */
    val coworkProject: MutableState<String> = mutableStateOf("在项目中工作")

    /** 协作页输入草稿（H1 —— 发送前退出不丢字）。 */
    val coworkDraft: MutableState<String> = mutableStateOf("")

    private var prefs: SharedPreferences? = null

    /** MainActivity.onCreate 调一次。重复调用安全（幂等）。 */
    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        themeMode.value = p.getString(KEY_THEME, "auto") ?: "auto"
        chatFont.value = p.getString(KEY_FONT, "default") ?: "default"
        // 默认「仅按钮」对齐 Web（回车只换行，发送靠按钮）
        sendKey.value = p.getString(KEY_SEND, "仅按钮（回车只换行）") ?: "仅按钮（回车只换行）"
        sendByEnter.value = sendKey.value.contains("回车发送")
        coworkProject.value = p.getString(KEY_COWORK_PROJECT, "在项目中工作") ?: "在项目中工作"
        coworkDraft.value = p.getString(KEY_COWORK_DRAFT, "") ?: ""
        newlineKey.value = p.getString(KEY_NEWLINE, "Enter") ?: "Enter"
    }

    fun setThemeMode(mode: String) {
        themeMode.value = mode
        prefs?.edit()?.putString(KEY_THEME, mode)?.apply()
    }

    fun setChatFont(font: String) {
        chatFont.value = font
        prefs?.edit()?.putString(KEY_FONT, font)?.apply()
    }

    fun setSendKey(v: String) {
        sendKey.value = v
        sendByEnter.value = v.contains("回车发送")
        prefs?.edit()?.putString(KEY_SEND, v)?.apply()
    }

    fun setNewlineKey(v: String) {
        newlineKey.value = v
        prefs?.edit()?.putString(KEY_NEWLINE, v)?.apply()
    }

    fun setCoworkProject(v: String) {
        coworkProject.value = v
        prefs?.edit()?.putString(KEY_COWORK_PROJECT, v)?.apply()
    }

    fun setCoworkDraft(v: String) {
        coworkDraft.value = v
        prefs?.edit()?.putString(KEY_COWORK_DRAFT, v)?.apply()
    }
}
