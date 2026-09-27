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

    /** light / auto / dark（ThemeMode.key 同集） */
    val themeMode: MutableState<String> = mutableStateOf("auto")

    /** default / sans / system / dyslexic（ChatFont.key 同集） */
    val chatFont: MutableState<String> = mutableStateOf("default")

    private var prefs: SharedPreferences? = null

    /** MainActivity.onCreate 调一次。重复调用安全（幂等）。 */
    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        themeMode.value = p.getString(KEY_THEME, "auto") ?: "auto"
        chatFont.value = p.getString(KEY_FONT, "default") ?: "default"
    }

    fun setThemeMode(mode: String) {
        themeMode.value = mode
        prefs?.edit()?.putString(KEY_THEME, mode)?.apply()
    }

    fun setChatFont(font: String) {
        chatFont.value = font
        prefs?.edit()?.putString(KEY_FONT, font)?.apply()
    }
}
