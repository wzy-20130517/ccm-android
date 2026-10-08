package com.ccm.app.core.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Provider 配置 —— 一个「API 站点 + 模型」的组合。
 *
 * 【与 Node 版的兼容性】字段名必须与 `config.json` 里的 provider 对象一致，
 * 用户可能直接把 Node 的配置搬过来（APK 首次启动会尝试读取旧配置）。
 * 用 `@SerialName` 显式绑定，不要依赖 Kotlin 属性名自动映射。
 *
 * 【config.json 里的 provider 形态（Node 版）】
 * ```json
 * {
 *   "current": "wb",
 *   "providers": {
 *     "wb": {
 *       "name": "WorkBuddy",
 *       "url": "https://xxx/v1",
 *       "model": "deepseek-v4.1-flash",
 *       "protocol": "openai",
 *       "apiKey": "sk-...",          // 单 key
 *       "apiKeys": ["sk-1", "sk-2"]  // 或轮换池（两者不同时存在）
 *     }
 *   }
 * }
 * ```
 */
@Serializable
data class ProviderConfig(
    /** Provider ID（在 providers 字典里的键）。 */
    @SerialName("id") val id: String = "",

    /** 显示名（用户起的名字，如 "WorkBuddy"）。 */
    @SerialName("name") val name: String = "",

    /** API base URL。注意：是否带 `/v1` 由协议决定，见 [normalizeBaseUrl]。 */
    @SerialName("url") val url: String = "",

    /** 模型名，如 `deepseek-v4.1-flash`。 */
    @SerialName("model") val model: String = "",
    /**
     * 模型池（第24批）—— 设置页「模型清单」的落盘位。
     *
     * 之前那个清单写死 deepseek-v4.1-flash/pro/lite 三行假数据，
     * 勾选状态还是组件内 remember（关页即丢）。现在：
     * 清单 = `models ∪ {model}`（model 是基准项），改动直接写回。
     * null/空 = 只有当前 model（未配置过清单的向后兼容）。
     */
    @SerialName("models") val models: List<String>? = null,

    /** 协议：openai / anthropic / responses。 */
    @SerialName("protocol") val protocol: String = PROTOCOL_OPENAI,

    /** 单 key 形式。与 [apiKeys] 二选一。 */
    @SerialName("apiKey") val apiKey: String? = null,

    /** key 轮换池。与 [apiKey] 二选一。 */
    @SerialName("apiKeys") val apiKeys: List<String>? = null,

    /**
     * 是否启用识图（vision）路由。
     * 开启时优先用本 Provider 的模型识图，失败回退到备用识图 Provider。
     */
    @SerialName("vision") val vision: Boolean = false,

    /** 深度思考强度：none/minimal/low/medium/high/xhigh/max。 */
    @SerialName("effort") val effort: String? = null,

    /** 是否把历史思考回传给模型（默认 false，省上下文）。 */
    @SerialName("replayReasoning") val replayReasoning: Boolean = false,

    /**
     * Prompt Cache 开关（2026-10-07 从全局挪到 provider 级）。
     *
     * 【字段名对齐 CLI】CLI 用 `promptCacheEnabled`（实测 config.json
     * provider 2 里的字段名），不是 `promptCache` —— 命名不一致的话
     * 迁移 CLI 配置时读不到。
     */
    @SerialName("promptCacheEnabled") val promptCacheEnabled: Boolean = false,

    /** Prompt Cache 保留时间（"24h" = 带 24h retention；null = 默认）。 */
    @SerialName("promptCacheRetention") val promptCacheRetention: String? = null,

    /** 温度。null = 用默认值 1。 */
    @SerialName("temperature") val temperature: Double? = null,

    /** 最大输出 token。null = 用默认值。 */
    @SerialName("maxOutputTokens") val maxOutputTokens: Int? = null,

    /** 额外透传参数（非标字段兜底）。 */
    @SerialName("extra") val extra: JsonObject? = null,
) {
    /** 全部可用的 key（池优先，其次单 key）。 */
    fun allKeys(): List<String> = when {
        !apiKeys.isNullOrEmpty() -> apiKeys
        !apiKey.isNullOrBlank() -> listOf(apiKey)
        else -> emptyList()
    }

    /** 协议规范化。未知值回退 openai（兼容性最好）。 */
    val protocolType: Protocol get() = Protocol.parse(protocol)

    /** 显示名兜底：没起名字就用 id。 */
    val displayName: String get() = name.ifBlank { id }

    companion object {
        const val PROTOCOL_OPENAI = "openai"
        const val PROTOCOL_ANTHROPIC = "anthropic"
        const val PROTOCOL_RESPONSES = "responses"
    }
}

/**
 * 协议类型。
 *
 * 【三者的差异 —— 决定请求体怎么构造、URL 怎么拼】
 * | 协议 | 路径 | 认证头 | 备注 |
 * |---|---|---|---|
 * | [OPENAI] | `{base}/chat/completions` | `Authorization: Bearer` | base 带 `/v1`，兼容性最好 |
 * | [ANTHROPIC] | `{base}/v1/messages` | `x-api-key` + `anthropic-version` | base **不带** `/v1` |
 * | [RESPONSES] | `{base}/responses` | `Authorization: Bearer` | base 带 `/v1`，OpenAI 新协议 |
 *
 * ⚠️ anthropic 的 base 不带 `/v1` 是**官方约定**（官方 base 是 `https://api.anthropic.com`），
 * 而 openai 的 base 通常写成 `https://api.openai.com/v1`。这个差异导致切换协议时
 * URL 必须跟着规范化 —— 见 [normalizeBaseUrl]，否则会拼出 `/v1/v1/messages`。
 */
enum class Protocol {
    OPENAI,
    ANTHROPIC,
    RESPONSES,
    ;

    /** 请求路径（拼在规范化后的 base 后面）。 */
    val path: String get() = when (this) {
        OPENAI -> "/chat/completions"
        ANTHROPIC -> "/v1/messages"
        RESPONSES -> "/responses"
    }

    /** 这个协议的 base URL 是否应该带 `/v1` 后缀。 */
    val baseShouldHaveV1: Boolean get() = this != ANTHROPIC

    companion object {
        fun parse(raw: String?): Protocol = when (raw?.trim()?.lowercase()) {
            "anthropic" -> ANTHROPIC
            "responses" -> RESPONSES
            else -> OPENAI
        }

        fun toConfigString(p: Protocol): String = when (p) {
            OPENAI -> "openai"
            ANTHROPIC -> "anthropic"
            RESPONSES -> "responses"
        }

        /**
         * 规范化 base URL —— **切换协议时必须调这个**。
         *
         * 规则：
         * - [OPENAI] / [RESPONSES]：base 应以 `/v1` 结尾。若用户给的是裸域名（`https://x.com`）
         *   就补上 `/v1`；已经是 `https://x.com/v1` 就原样。
         * - [ANTHROPIC]：base **不应**带 `/v1`。若带了就削掉（避免拼出 `/v1/v1/messages`）。
         *
         * 【为什么不做成自动魔法】用户填 URL 时经常直接粘贴站点首页或带路径的中转地址
         * （如 `https://x.com/api/v1`），无脑增删 `/v1` 会改坏路径。
         * 所以这里只处理**最明确的两种情形**：结尾恰好是 `/v1` 或完全没有路径段。
         * 更复杂的路径（`/api/v1`、`/openai/v1`）一律原样保留，由用户自己保证正确。
         */
        fun normalizeBaseUrl(rawUrl: String, protocol: Protocol): String {
            val url = rawUrl.trim().trimEnd('/')
            if (url.isEmpty()) return url

            // 判断结尾是否为 /v1
            val endsWithV1 = url.endsWith("/v1")
            // 判断是否「只有 host，没有路径」（形如 https://x.com）
            val pathPart = url.substringAfter("://", "").substringAfter("/", "")
            val isBareHost = pathPart.isEmpty()

            return when (protocol) {
                ANTHROPIC -> if (endsWithV1) url.removeSuffix("/v1") else url
                OPENAI, RESPONSES -> when {
                    endsWithV1 -> url
                    isBareHost -> "$url/v1"
                    else -> url // 复杂路径，原样保留
                }
            }
        }
    }
}
