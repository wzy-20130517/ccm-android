package com.ccm.app.core.tool

/**
 * 工具侧的**只读**配置快照。
 *
 * ## 为什么是快照而不是引用
 * 工具执行期间配置不该变。如果传的是活引用，用户在设置页改 Provider
 * 可能让**正在跑的请求**中途换了端点 —— 轻则请求发错站，重则 key 泄漏到别处。
 * 所以由 Agent 层在 run 开始时**冻结一份**传进来。
 *
 * ## 为什么不用一个 Map<String, String>
 * 强类型字段能编译期发现拼错（`settings.tavilyKey` vs `settings["tavlyKey"]`），
 * 也自带文档作用。可空字段明确表达「这个服务可能没配」。
 *
 * ## 可空性约定
 * - Provider 三件套（[providerBaseUrl] / [providerApiKey] / [providerModel]）非空 ——
 *   没有 Provider 根本跑不起来，Agent 层不会构造出空的 ToolSettings。
 * - 各服务的 key（[tavilyApiKey] 等）可空 —— 工具遇到 null 要给出
 *   **「未配置 + 怎么配」**的提示，而不是崩溃或静默失败。
 */
interface ToolSettings {

    // ───────────────────── Provider（当前正在用的那个） ─────────────────────

    /** API base URL（已按协议规范化，如 `https://x.com/v1`）。 */
    val providerBaseUrl: String

    /** API key（池模式下是当前选中的那个）。 */
    val providerApiKey: String

    /** 模型名，如 `deepseek-v4.1-flash`。 */
    val providerModel: String

    /** 协议：`openai` / `anthropic` / `responses`。 */
    val providerProtocol: String

    // ───────────────────── 第三方服务（都可空） ─────────────────────

    /** Tavily API key —— WebSearch 用。 */
    val tavilyApiKey: String?

    /** Pexels API key —— FindImage 用。 */
    val pexelsApiKey: String?

    /** 生图服务的 base URL —— ImageGen 用。 */
    val imageGenBaseUrl: String?

    /** 生图服务的 API key。 */
    val imageGenApiKey: String?

    /** 生图模型名。 */
    val imageGenModel: String?

    /** 识图专用 Provider 的 base URL（vision 路由回退用）。 */
    val visionBaseUrl: String?

    /** 识图专用 Provider 的 API key。 */
    val visionApiKey: String?

    /** 识图专用模型名。 */
    val visionModel: String?

    /**
     * 通用取值 —— 给上面没列到、但工具临时需要的配置项兜底。
     *
     * 用法：`settings.get("someKey")`。
     * 返回 null = 未配置。
     */
    fun get(key: String): String? = null

    companion object {
        /**
         * 空实现 —— 单测 / 无配置场景用。
         *
         * Provider 三件套给空串（调用方自己判空），各服务 key 给 null。
         */
        val Empty: ToolSettings = object : ToolSettings {
            override val providerBaseUrl: String = ""
            override val providerApiKey: String = ""
            override val providerModel: String = ""
            override val providerProtocol: String = "openai"
            override val tavilyApiKey: String? = null
            override val pexelsApiKey: String? = null
            override val imageGenBaseUrl: String? = null
            override val imageGenApiKey: String? = null
            override val imageGenModel: String? = null
            override val visionBaseUrl: String? = null
            override val visionApiKey: String? = null
            override val visionModel: String? = null
        }
    }
}

/**
 * 由 Map 驱动的 [ToolSettings] 实现 —— 配置从 config.json 读出来后直接包一层。
 *
 * 好处：新增配置项时不用改这个类（走 [get] 兜底），
 * 但常用的那几项仍然有强类型字段。
 */
class MapToolSettings(private val map: Map<String, String?>) : ToolSettings {
    override val providerBaseUrl: String get() = map["providerBaseUrl"] ?: ""
    override val providerApiKey: String get() = map["providerApiKey"] ?: ""
    override val providerModel: String get() = map["providerModel"] ?: ""
    override val providerProtocol: String get() = map["providerProtocol"] ?: "openai"
    override val tavilyApiKey: String? get() = map["tavilyApiKey"]
    override val pexelsApiKey: String? get() = map["pexelsApiKey"]
    override val imageGenBaseUrl: String? get() = map["imageGenBaseUrl"]
    override val imageGenApiKey: String? get() = map["imageGenApiKey"]
    override val imageGenModel: String? get() = map["imageGenModel"]
    override val visionBaseUrl: String? get() = map["visionBaseUrl"]
    override val visionApiKey: String? get() = map["visionApiKey"]
    override val visionModel: String? get() = map["visionModel"]

    override fun get(key: String): String? = map[key]
}
