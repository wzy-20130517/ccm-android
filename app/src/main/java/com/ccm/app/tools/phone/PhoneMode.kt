package com.ccm.app.tools.phone

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 手机操作模式（对齐 CLI `core/tools/tools-phone.mjs:55` 的两层语义）。
 *
 * ══════════════════════════════════════════════════════════════
 *  两层语义（CLI 原设计，2026-09-26 用户拍板）
 * ══════════════════════════════════════════════════════════════
 *
 * 1. **偏好**（持久，存 device.json 的 phoneMode 字段）：
 *      'foreground' → 以后都用主屏，**不再弹选择**
 *      'background' → 以后都用副屏，**不再弹选择**
 *      'ask'        → **每次用手机工具都问**
 *      null/缺失    → 从没设过 → 第一次弹选择
 *
 * 2. **本次会话的生效值**（内存 sessionMode）：由偏好决定，工具读它。
 *
 * APK 的额外模式：'idle'（本次不操作手机）—— 只在会话内有效，
 * **不落盘**（用户选「这次别动」不该变成永久设置）。
 *
 * ══════════════════════════════════════════════════════════════
 *  为什么必须有这个（2026-10-06 加）
 * ══════════════════════════════════════════════════════════════
 *
 * 用户报「未找用户要模式」—— APK 原来压根没有模式概念：
 * 所有手机操作硬编码在虚拟副屏上，用户既不知道在操作哪，
 * 也没法让它操作主屏（前台，看得见）。
 */
object PhoneMode {

    /** 偏好值（持久） */
    const val FOREGROUND = "foreground"
    const val BACKGROUND = "background"
    const val ASK = "ask"

    /** 本次会话生效值（内存，不落盘）—— 含 idle */
    const val IDLE = "idle"

    /** device.json（与 CLI 同名文件，但各自独立存） */
    private fun configFile(ctx: Context): File =
        File(ctx.filesDir, "device.json")

    /**
     * 读偏好。返回 'foreground' | 'background' | 'ask' | null（从没设过）。
     *
     * ⚠️ 白名单过滤 —— CLI 那边踩过「存进去了但读不到」的坑
     * （loadDeviceConfig 的返回值是白名单，新增字段忘加就永远 undefined）。
     */
    @Volatile private var cachedPref: String? = null
    @Volatile private var loaded = false

    fun preference(ctx: Context): String? {
        if (loaded) return cachedPref
        return try {
            val f = configFile(ctx)
            val raw = if (f.exists()) JSONObject(f.readText()).optString("phoneMode", "") else ""
            val v = raw.takeIf { it in setOf(FOREGROUND, BACKGROUND, ASK) }
            cachedPref = v
            loaded = true
            v
        } catch (_: Throwable) {
            cachedPref = null
            loaded = true
            null
        }
    }

    /** 写偏好（持久）。传 null 清掉，回到「从没设过」。 */
    fun setPreference(ctx: Context, v: String?) {
        val ok = v?.takeIf { it in setOf(FOREGROUND, BACKGROUND, ASK) }
        try {
            val f = configFile(ctx)
            val obj = if (f.exists()) JSONObject(f.readText()) else JSONObject()
            if (ok == null) obj.remove("phoneMode") else obj.put("phoneMode", ok)
            f.writeText(obj.toString(2))
        } catch (_: Throwable) { /* 存不下不影响本次会话 */ }
        cachedPref = ok
        loaded = true
        // 偏好改变 → 本次生效值作废（下次按新偏好重新决定）
        sessionMode = if (ok == FOREGROUND || ok == BACKGROUND) ok else null
    }

    /**
     * 本次会话的生效模式。
     * null = 还没定（首次调用时由 UI 弹选择）。
     */
    @Volatile var sessionMode: String? = null
        private set

    /** 设置本次生效值（不落盘）。 */
    fun setSession(mode: String?) {
        sessionMode = mode?.takeIf { it in setOf(FOREGROUND, BACKGROUND, IDLE) }
    }

    /** 重置本次会话（下次调用重新按偏好决定）。 */
    fun resetSession() {
        sessionMode = null
    }

    /** 中文标签（给模型/用户看）。 */
    fun label(mode: String? = sessionMode): String = when (mode) {
        FOREGROUND -> "前台（操作主屏，用户可见）"
        BACKGROUND -> "后台（虚拟副屏，静默）"
        IDLE -> "未操作（idle）"
        else -> "未选"
    }

    /** 目标屏参数：0 = 主屏，-1 = 副屏（AIDL setTargetDisplay 的入参）。 */
    fun targetDisplayArg(mode: String?): Int = if (mode == FOREGROUND) 0 else -1
}
