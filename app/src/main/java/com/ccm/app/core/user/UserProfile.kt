package com.ccm.app.core.user

import com.ccm.app.core.AppStorage
import org.json.JSONObject
import java.io.File

/**
 * 用户资料（称呼 / 职业 / 回复偏好）。
 *
 * ## 为什么独立存储
 * 对齐 CCM 的 `~/.claude-code-mobile/cli-profile.json`：
 * - Provider 配置（config.json）是「连接配置」，语义上不该混入用户资料
 * - 用户资料是「我在终端里希望被怎么对待」，改动频率低、字段少
 *
 * ## 字段（对齐 CLI 端 `user-profile.mjs`）
 * - `display_name` — 称呼（「下午好，X」里的 X）
 * - `full_name` — 全名（备用，display_name 为空时降级用）
 * - `work_function` — 职业
 * - `personal_preferences` — 回复偏好（注入 system prompt）
 *
 * ## 降级链（对齐 Web 的 MainContent.tsx:1586）
 * ```
 * display_name → full_name → null（调用方用默认问候语）
 * ```
 *
 * ## 容错
 * 文件不存在 / 损坏 / 字段类型不对 → 返回空对象，绝不抛。
 */
data class UserProfile(
    val displayName: String = "",
    val fullName: String = "",
    val workFunction: String = "",
    val personalPreferences: String = "",
) {
    /**
     * 展示用称呼：display_name → full_name → 空。
     * 调用方拿到空后应该不显示名字（用「今天需要什么帮助？」这种通用问候）。
     */
    val callName: String
        get() = displayName.ifBlank { fullName }

    /** 是否完全为空（没配过任何字段） */
    val isEmpty: Boolean
        get() = displayName.isBlank() && fullName.isBlank()
                && workFunction.isBlank() && personalPreferences.isBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        put("display_name", displayName)
        put("full_name", fullName)
        put("work_function", workFunction)
        put("personal_preferences", personalPreferences)
    }

    companion object {
        /** 从 JSON 解析；任何字段缺失或类型不对都给空字符串 */
        fun fromJson(json: JSONObject): UserProfile = UserProfile(
            displayName = json.optString("display_name", ""),
            fullName = json.optString("full_name", ""),
            workFunction = json.optString("work_function", ""),
            personalPreferences = json.optString("personal_preferences", ""),
        )

        /** 空对象（所有字段为空） */
        val EMPTY = UserProfile()
    }
}

/**
 * 用户资料存储（持久化到 `<root>/user-profile.json`）。
 *
 * ## 用法
 * ```kotlin
 * val store = UserProfileStore(AppGraph.storage!!)
 * val profile = store.load()
 * greetingFor(profile.callName)   // 空 → "今天需要什么帮助？"
 * ```
 */
class UserProfileStore(private val storage: AppStorage) {
    private val file: File get() = storage.resolve("user-profile.json")

    /** 读取资料；文件不存在或损坏时返回 EMPTY（绝不抛） */
    fun load(): UserProfile {
        return try {
            if (!file.exists()) return UserProfile.EMPTY
            val text = file.readText()
            val json = JSONObject(text)
            UserProfile.fromJson(json)
        } catch {
            UserProfile.EMPTY
        }
    }

    /** 整体覆盖保存 */
    fun save(profile: UserProfile) {
        file.writeText(profile.toJson().toString(2))
    }

    /** 更新单个字段；value 为空字符串表示清除该字段 */
    fun setField(field: String, value: String): UserProfile {
        val cur = load()
        val next = when (field) {
            "display_name", "displayName" -> cur.copy(displayName = value.trim())
            "full_name", "fullName" -> cur.copy(fullName = value.trim())
            "work_function", "workFunction" -> cur.copy(workFunction = value.trim())
            "personal_preferences", "personalPreferences" -> cur.copy(personalPreferences = value.trim())
            else -> throw IllegalArgumentException(
                "不认识的字段: $field（可用: display_name / full_name / work_function / personal_preferences）"
            )
        }
        save(next)
        return next
    }

    /** 清空所有字段 */
    fun clear() {
        save(UserProfile.EMPTY)
    }
}
