package com.ccm.app.core.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * 应用配置 —— `config.json` 的顶层结构。
 *
 * ## 与 Node 版的兼容性（**关键约束**）
 * 字段名必须与 `~/.claude-code-mobile/config.json` 一致，
 * 因为**用户会直接把 Node 的配置搬过来**（APK 首次启动尝试读取旧配置）。
 * 用 `@SerialName` 显式绑定，不依赖 Kotlin 属性名自动映射。
 *
 * Node 版的顶层形态：
 * ```json
 * {
 *   "providers": { "wb": { "name": "...", "url": "...", ... } },
 *   "current": "wb",
 *   "thinking": {...},
 *   "stream": true,
 *   "permissionMode": "bypassPermissions",
 *   "vision": true,
 *   "visionProviderId": "...",
 *   "imageGen": { "apiKey": "...", "url": "..." },
 *   "greeting": true,
 *   "keyRotateEvery": 0,
 *   "temperature": 1,
 *   "maxContextTokens": 1000000
 * }
 * ```
 *
 * ## 容错原则
 * - **所有字段都有默认值** —— 缺字段不该导致解析失败（旧配置文件必然缺新字段）
 * - **未知字段忽略**（`ignoreUnknownKeys`）—— 新版本加的字段不该让旧版崩
 * - 解析失败**不抛异常**，返回默认配置 + 错误信息（让 UI 能提示用户）
 */
@Serializable
data class AppConfig(
    /** 所有 Provider，键是 ID。 */
    val providers: Map<String, ProviderConfig> = emptyMap(),

    /** 当前使用的 Provider ID。 */
    val current: String = "",

    /** 是否开启开场白（关掉省一次 API 调用）。 */
    val greeting: Boolean = true,

    /** 是否流式输出。 */
    val stream: Boolean = true,

    /** 全局温度（Provider 未指定时用）。 */
    val temperature: Double = 1.0,

    /** 模型上下文窗口（用于判断「该压缩了」）。 */
    @SerialName("maxContextTokens")
    val maxContextTokens: Int = 1_000_000,

    /** 权限模式：default / acceptEdits / plan / bypassPermissions。 */
    @SerialName("permissionMode")
    val permissionMode: String = "default",

    /** 是否启用识图路由。 */
    val vision: Boolean = false,

    /** 识图专用 Provider ID（vision 路由回退用）。 */
    @SerialName("visionProviderId")
    val visionProviderId: String? = null,

    /** key 轮换间隔（每 N 次请求换 key；0 = 不主动轮换）。 */
    @SerialName("keyRotateEvery")
    val keyRotateEvery: Int = 0,

    /** 生图配置。 */
    @SerialName("imageGen")
    val imageGen: ImageGenConfig? = null,

    /** 深度思考强度：none/minimal/low/medium/high/xhigh/max。 */
    val effort: String? = null,

    /**
     * WebSearch 的 Tavily key（第24批，2026-09-28）。
     *
     * 之前设置页有输入框但**全链路无处可存**：AppConfig 没这字段，
     * buildSettings 的 map 也没这个键 → `map["tavilyApiKey"]` 永远 null，
     * 用户填了等于没填（WebSearch 工具永远报没 key）。
     */
    @SerialName("tavilyKey")
    val tavilyKey: String? = null,

    /** WebSearch 工具总开关（设置页开关的落盘位；null = 未设置过 = 开）。 */
    @SerialName("webSearch")
    val webSearch: Boolean? = null,

    /** 其他未建模的字段原样保留（防保存时丢失用户的手工配置）。 */
    @SerialName("_extra")
    val extra: JsonObject? = null,
) {

    /** 当前 Provider（找不到返回 null）。 */
    val currentProvider: ProviderConfig?
        get() = providers[current]

    /** 识图 Provider（vision 路由用）。 */
    val visionProvider: ProviderConfig?
        get() = visionProviderId?.let { providers[it] }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
            prettyPrint = true
        }

        /** 从文件加载。**永不抛异常** —— 失败返回默认配置 + 错误信息。 */
        fun load(file: File): Result {
            if (!file.exists()) return Result(AppConfig(), null)
            return try {
                Result(json.decodeFromString(serializer(), file.readText()), null)
            } catch (e: Throwable) {
                // 配置坏了不该让 App 起不来 —— 用默认值 + 把错误带给 UI
                Result(AppConfig(), e.message ?: "配置解析失败")
            }
        }

        /** 保存到文件（原子写：先写 .tmp 再 rename）。 */
        fun save(config: AppConfig, file: File): Boolean = try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(serializer(), config))
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            true
        } catch (_: Throwable) {
            false
        }

        /** 解析结果：配置 + 可能的错误信息（UI 据此提示用户）。 */
        data class Result(val config: AppConfig, val error: String?)
    }
}

/**
 * 生图配置（`config.json` 的 `imageGen` 字段）。
 *
 * 对齐 Node 版 `/imagegen` 的配置项。
 */
@Serializable
data class ImageGenConfig(
    @SerialName("apiKey") val apiKey: String? = null,
    @SerialName("url") val url: String? = null,
    @SerialName("model") val model: String? = null,
    @SerialName("size") val size: String? = null,
    @SerialName("dir") val dir: String? = null,
) {
    /** 是否配置完整（能用来生图）。 */
    val isUsable: Boolean get() = !apiKey.isNullOrBlank() && !url.isNullOrBlank()
}
