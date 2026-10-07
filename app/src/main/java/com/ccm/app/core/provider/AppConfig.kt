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
    // 【2026-10-06 问题13 修复】默认值原来是 "default"（拦截装饰性工具），
    // 而 CLI 侧用户配置的是 "bypassPermissions"（全放行）。
    // 用户报「默认权限与 CLI 不同」—— APK 里工具调用老被拦，行为不一致。
    // 对齐 CLI：默认全放行（用户可去设置页改）。
    @SerialName("permissionMode")
    val permissionMode: String = "bypassPermissions",

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

    /**
     * Pexels 图库 key（FindImage 用）—— 问题40。
     *
     * 【为什么补】原来全链路缺失：AppConfig 没这字段、buildSettings 没传、
     * 设置页没输入框 → FindImage 永远报「未配置 key」。
     */
    @SerialName("pexelsKey")
    val pexelsKey: String? = null,

    /** WebSearch 工具总开关（设置页开关的落盘位；null = 未设置过 = 开）。 */
    @SerialName("webSearch")
    val webSearch: Boolean? = null,

    /**
     * 输出风格（2026-09-29 互通）。
     *
     * 字段名与 CLI `/style`（core/cmd-style.mjs 存 config.outputStyle）和
     * Web `server.mjs:2411` 一致 —— 三端字段通用，用户手动拷 config 即互通。
     * 内置 default / explanatory / learning；自定义风格文件路径
     * `.claude/output-styles/<名字>.md` 的读取 APK 暂未做（后续）。
     */
    @SerialName("outputStyle")
    val outputStyle: String? = null,

    /** 工作区路径（2026-10-01，与 CLI /workspace 同语义）。空 = 默认 files/workspace。 */
    @SerialName("workspacePath")
    val workspacePath: String? = null,

    /**
     * 自动压缩阈值（问题40：/compact-threshold）。
     *
     * ⚠️ **已废弃（2026-10-07 语义统一）**：原来是「百分比 0-100」，
     * 现在 CLI 对齐为「token 数 + 消息条数」双参数 —— 新字段见
     * [compactTokenLimit] / [compactMessageLimit]。本字段仅为兼容旧配置保留：
     * 新字段全为 0 且本字段 >0 时，仍按旧百分比换算（见 AppContainer 构造）。
     * `/compact-threshold` 命令不再读写本字段。
     */
    @SerialName("compactThreshold")
    val compactThreshold: Int = 0,

    /**
     * 自动压缩的 token 阈值（0 = 不按 token 触发）。
     *
     * 对齐 CLI `/compact-threshold <tokens> [messages]`（config 的 tokenLimit）。
     * 与 [compactMessageLimit] 都为 0 时自动压缩关闭。
     */
    @SerialName("compactTokenLimit")
    val compactTokenLimit: Int = 0,

    /**
     * 自动压缩的消息条数阈值（0 = 不按条数触发）。
     *
     * 对齐 CLI 的 messageLimit。与 [compactTokenLimit] 都为 0 时自动压缩关闭。
     */
    @SerialName("compactMessageLimit")
    val compactMessageLimit: Int = 0,

    /**
     * 进会话时是否显示历史正文（问题40：/replay）。
     *
     * 默认 false（2026-10-03 拍板：不想被历史刷屏）。
     */
    @SerialName("replayHistory")
    val replayHistory: Boolean = false,

    /**
     * Prompt Cache 开关（问题40：/cache）。
     *
     * 控制是否发 prompt_cache_key / retention 字段。
     * 默认 false（未知兼容网关不要盲开）。
     */
    @SerialName("promptCache")
    val promptCache: Boolean = false,

    /**
     * Prompt Cache 保留时间（2026-10-07 对齐 CLI `/cache retention 24h|off`）。
     *
     * `"24h"` = 请求带 24h retention；null/其他 = 默认（不带）。
     */
    @SerialName("promptCacheRetention")
    val promptCacheRetention: String? = null,

    /**
     * 运行环境模式（2026-10-06 加）：首次引导时选一次，之后不再问。
     *
     * - `"proot"`（默认）：内置 Ubuntu proot 环境，Bash 命令跑在
     *   `/data/data/com.ccm.app/files/rootfs` 里
     * - `"termux"`：外接 Termux，Bash 命令通过 RUN_COMMAND Intent
     *   发给 `com.termux` 执行
     *
     * ⚠️ 空串 = 老用户/尚未选择（MainActivity 视为已选 proot，不打扰）。
     * 只有首次全新安装（rootfs 未装）才会进引导页问一次。
     */
    @SerialName("envMode")
    val envMode: String = "",

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
        /** 已建模字段名单（load 时差集保留、save 时顶层合并用）。 */
        private val KNOWN_KEYS = setOf(
            "providers", "current", "greeting", "stream", "temperature",
            "maxContextTokens", "permissionMode", "vision", "visionProviderId",
            "keyRotateEvery", "imageGen", "effort", "tavilyKey", "webSearch", "pexelsKey",
            "compactThreshold", "compactTokenLimit", "compactMessageLimit",
            "replayHistory", "promptCache", "promptCacheRetention", "envMode",
            "outputStyle", "workspacePath",
            "_extra",
        )

        fun load(file: File): Result {
            if (!file.exists()) return Result(AppConfig(), null)
            return try {
                val text = file.readText()
                val cfg = json.decodeFromString(serializer(), text)
                // ★ audit-core #5：ignoreUnknownKeys 会把用户手工加的键
                //   （hooks/markdown 等）直接丢掉，save 后永久消失。
                //   这里把「原始 JSON − 已知键」存进 extra，save 时再展平回去。
                val withExtra = try {
                    val root = json.parseToJsonElement(text)
                        as? kotlinx.serialization.json.JsonObject
                    if (root == null) cfg else {
                        val extras = root.keys
                            .filter { it !in KNOWN_KEYS }
                            .associateWith { root[it]!! }
                        if (extras.isEmpty()) cfg
                        else cfg.copy(
                            extra = kotlinx.serialization.json.JsonObject(extras),
                        )
                    }
                } catch (_: Throwable) { cfg }
                Result(withExtra, null)
            } catch (e: Throwable) {
                // 配置坏了不该让 App 起不来 —— 用默认值 + 把错误带给 UI
                Result(AppConfig(), e.message ?: "配置解析失败")
            }
        }

        /** 保存到文件（原子写：先写 .tmp 再 rename）。 */
        fun save(config: AppConfig, file: File): Boolean = try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            var out = json.encodeToString(serializer(), config)
            // ★ audit-core #5：把 extra 的键**展平**回顶层（已建模字段优先，
            //   不让 extra 覆盖同名真字段），并去掉 "_extra" 嵌套本身。
            config.extra?.let { ex ->
                if (ex.isNotEmpty()) {
                    try {
                        val root = json.parseToJsonElement(out)
                            as kotlinx.serialization.json.JsonObject
                        val merged = kotlinx.serialization.json.JsonObject(
                            ex.toMap() + root.toMap().filterKeys { it != "_extra" },
                        )
                        out = json.encodeToString(
                            kotlinx.serialization.json.JsonObject.serializer(),
                            merged,
                        )
                    } catch (_: Throwable) {}
                }
            }
            tmp.writeText(out)
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
