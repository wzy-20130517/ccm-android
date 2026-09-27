package com.ccm.app.core.provider

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * API Key 轮换池。
 *
 * ══════════════════════════════════════════════════════════════
 * 【核心语义（照搬 CCM key-pool.mjs，每条都有踩坑背景）】
 * ══════════════════════════════════════════════════════════════
 *
 * 1. **只有「这个 key 本身不行」的错误才切 key**
 *    余额/配额/鉴权失效 → 切。网络抖动、500、超时 → 不切（换 key 也没用，交给重试逻辑）。
 *    判断逻辑见 [KeyHealth.isKeyExhaustedError]。
 *
 * 2. **冷却状态必须落盘**
 *    早期版本只存内存，重启后冷却全清、index 归零，于是每次重启都从第一个
 *    （往往已耗尽的）key 开始撞，白浪费一轮请求 + 退避。而重启远比冷却期频繁。
 *    落盘只写 key 的 sha256 前 16 位指纹，**不写明文** —— 状态文件泄漏也不漏密钥。
 *
 * 3. **轮转从上次成功的 key 开始**
 *    否则每轮都从第一个已耗尽的 key 试起。
 *
 * 4. **冷却时长 5 小时**（用户 2026-08-30 设定）
 *    免费站日额度按天重置，24h 会让 key 闲置一整天；30 分钟又会反复撞同一个死 key。
 *    5h 折中。
 *
 * 5. **全部冷却时用「最早耗尽」的兜底**
 *    死等会把能用的 key 闲置（额度可能因签到/充值提前恢复）。
 */
class KeyPool(
    keys: List<String>,
    private val stateFile: File? = null,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    companion object {
        /** 冷却时长：5 小时。理由见类注释第 4 条。 */
        const val DEFAULT_COOLDOWN_MS = 5 * 60 * 60 * 1000L

        private val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }

        /** key 指纹：sha256 前 16 位。够区分且不可逆。 */
        fun fingerprint(key: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }.take(16)
        }
    }

    private val keys: List<String> = keys.filter { it.isNotBlank() }

    /** 每个 key 的冷却到期时间戳（毫秒）。不在表里 = 未冷却。 */
    private val cooldowns = mutableMapOf<String, Long>()

    /** 上次成功使用的 key 下标。下次从这里开始轮转。 */
    private var lastGoodIndex = 0

    /** 当前使用的 key 下标。 */
    private var currentIndex = 0

    init {
        loadState()
    }

    val size: Int get() = keys.size
    val isEmpty: Boolean get() = keys.isEmpty()
    val isNotEmpty: Boolean get() = keys.isNotEmpty()

    /** 当前 key。池空时返回 null。 */
    fun current(): String? {
        if (keys.isEmpty()) return null
        // 当前 key 在冷却中 → 找一个可用的
        if (isCoolingDown(currentIndex)) {
            val idx = pickAvailable()
            if (idx >= 0) currentIndex = idx
            // 全部冷却 → 用最早耗尽的那个（见类注释第 5 条）
            else currentIndex = oldestExhaustedIndex()
        }
        return keys.getOrNull(currentIndex)
    }

    /**
     * 轮换到下一个可用 key。
     *
     * @param reason 轮换原因（写日志/trace 用）
     * @return 新 key；池空返回 null
     */
    fun rotate(reason: String = "auth failed"): String? {
        if (keys.size <= 1) return current()
        markCurrentCoolingDown()
        val idx = pickAvailable()
        if (idx >= 0) {
            currentIndex = idx
            saveState()
        }
        return keys.getOrNull(currentIndex)
    }

    /** 标记当前 key 已耗尽，进入冷却。 */
    fun markCurrentCoolingDown() {
        if (keys.isEmpty()) return
        cooldowns[fingerprint(keys[currentIndex])] = now() + cooldownMs
        saveState()
    }

    /** 报告一次成功 —— 更新 lastGoodIndex，下次从这里开始。 */
    fun reportSuccess() {
        lastGoodIndex = currentIndex
        // 成功后清掉自己的冷却（它显然是好的）
        cooldowns.remove(fingerprint(keys[currentIndex]))
        saveState()
    }

    /** 描述池状态，供 `/config list` 展示。 */
    fun describe(): String {
        if (keys.isEmpty()) return "无 key"
        if (keys.size == 1) return "单 key"
        val available = keys.indices.count { !isCoolingDown(it) }
        val cooling = keys.size - available
        return if (cooling == 0) {
            "$size 个 key 轮换"
        } else {
            "$size 个 key 轮换（$cooling 个冷却中）"
        }
    }

    /** 每个 key 的状态详情（脱敏），供 UI 展示。 */
    fun describeDetailed(): List<KeyStatus> = keys.mapIndexed { i, key ->
        val until = cooldowns[fingerprint(key)] ?: 0L
        KeyStatus(
            index = i,
            fingerprint = fingerprint(key),
            isCurrent = i == currentIndex,
            coolingUntilMs = if (until > now()) until else null,
        )
    }

    // ── 内部 ──────────────────────────────────────────────

    /** 找一个未冷却的 key 下标。全在冷却返回 -1。 */
    private fun pickAvailable(): Int {
        if (keys.isEmpty()) return -1
        // 从 lastGoodIndex 开始轮转（不是从 0 —— 见类注释第 3 条）
        for (offset in 0 until keys.size) {
            val idx = (lastGoodIndex + offset) % keys.size
            if (!isCoolingDown(idx)) return idx
        }
        return -1
    }

    /** 全部冷却时，返回最早到期的那个（最可能已恢复）。 */
    private fun oldestExhaustedIndex(): Int {
        var bestIdx = 0
        var bestUntil = Long.MAX_VALUE
        keys.forEachIndexed { i, key ->
            val until = cooldowns[fingerprint(key)] ?: 0L
            if (until < bestUntil) {
                bestUntil = until
                bestIdx = i
            }
        }
        return bestIdx
    }

    private fun isCoolingDown(index: Int): Boolean {
        if (index !in keys.indices) return false
        val until = cooldowns[fingerprint(keys[index])] ?: return false
        if (until <= now()) {
            // 冷却已过期，顺手清理
            cooldowns.remove(fingerprint(keys[index]))
            return false
        }
        return true
    }

    // ── 落盘（只存指纹，不存明文）──────────────────────────

    private fun loadState() {
        val f = stateFile ?: return
        if (!f.exists()) return
        try {
            val state = json.decodeFromString(KeyPoolState.serializer(), f.readText())
            cooldowns.clear()
            cooldowns.putAll(state.cooldowns)
            lastGoodIndex = state.lastGoodIndex.coerceIn(0, maxOf(0, keys.size - 1))
            currentIndex = state.currentIndex.coerceIn(0, maxOf(0, keys.size - 1))
        } catch (_: Exception) {
            // 状态文件损坏 → 当作全新开始，不影响主流程
        }
    }

    private fun saveState() {
        val f = stateFile ?: return
        try {
            f.parentFile?.mkdirs()
            val state = KeyPoolState(
                cooldowns = cooldowns.toMap(),
                lastGoodIndex = lastGoodIndex,
                currentIndex = currentIndex,
            )
            f.writeText(json.encodeToString(KeyPoolState.serializer(), state))
        } catch (_: Exception) {
            // 落盘失败不影响本次运行
        }
    }
}

/** 落盘状态。只含指纹，无明文。 */
@Serializable
data class KeyPoolState(
    val cooldowns: Map<String, Long> = emptyMap(),
    val lastGoodIndex: Int = 0,
    val currentIndex: Int = 0,
)

/** 单个 key 的状态（脱敏，给 UI 用）。 */
data class KeyStatus(
    val index: Int,
    val fingerprint: String,
    val isCurrent: Boolean,
    val coolingUntilMs: Long?,
) {
    val isCooling: Boolean get() = coolingUntilMs != null
}

/**
 * key 耗尽的判定。
 *
 * 【为什么需要这么多模式】不同网关的报错措辞完全不同，而且**不一定带 4xx 状态码**：
 * - NewAPI：`code=pre_consume_token_quota_failed`，message 形如
 *   "token quota is not enough, token remain quota: $0.25"，type=new_api_error
 *   ⚠️ **可能不带 4xx**，光看 status 会漏判
 * - one-api：`insufficient_user_quota` / 「用户额度不足」/「令牌额度不足」
 * - OpenAI：429 + type=insufficient_quota
 * - DeepSeek：402 专用于余额不足
 * - 智谱：429 + 错误码 1316/1317
 */
object KeyHealth {

    /** 明确是「key 自己的问题」→ 值得换 key。 */
    private val EXHAUSTED_PATTERNS = listOf(
        // NewAPI / one-api 系
        Regex("pre_consume_token_quota_failed", RegexOption.IGNORE_CASE),
        Regex("insufficient[_\\s-]?user[_\\s-]?quota", RegexOption.IGNORE_CASE),
        Regex("quota\\s+is\\s+not\\s+enough", RegexOption.IGNORE_CASE),
        Regex("(token|user|channel)\\s*quota\\s*(is\\s*)?(not\\s*enough|exhausted|used\\s*up)", RegexOption.IGNORE_CASE),
        Regex("(用户|令牌|渠道|分组)?额度(不足|已用完|耗尽|已用尽)"),
        Regex("剩余额度不足|额度已耗尽"),
        // OpenAI 系
        Regex("insufficient[_\\s-]?(balance|quota|credit)", RegexOption.IGNORE_CASE),
        Regex("exceeded?\\s+your\\s+current\\s+quota", RegexOption.IGNORE_CASE),
        Regex("quota[_\\s-]?(exceeded|exhausted)", RegexOption.IGNORE_CASE),
        // 通用余额/计费
        Regex("balance\\s*(is\\s*)?(insufficient|not\\s+enough|too\\s+low)", RegexOption.IGNORE_CASE),
        Regex("insufficient\\s+balance", RegexOption.IGNORE_CASE),
        Regex("余额(不足|为负|已用完)|欠费|请充值|请先充值"),
        Regex("no\\s+credit|out\\s+of\\s+credit|run\\s+out\\s+of\\s+credits?", RegexOption.IGNORE_CASE),
        Regex("payment\\s+required|billing\\s+(error|issue|problem)", RegexOption.IGNORE_CASE),
        // key 本身失效
        Regex("invalid[_\\s-]?api[_\\s-]?key", RegexOption.IGNORE_CASE),
        Regex("incorrect\\s+api\\s+key", RegexOption.IGNORE_CASE),
        Regex("api\\s*key.*(invalid|expired|disabled|revoked|not\\s+found)", RegexOption.IGNORE_CASE),
        Regex("(令牌|密钥|key).*(已失效|无效|已禁用|不存在|已过期)"),
        Regex("token\\s+(is\\s+)?(invalid|expired|disabled)", RegexOption.IGNORE_CASE),
        Regex("unauthorized|authentication[_\\s-]?(failed|error)", RegexOption.IGNORE_CASE),
        Regex("account.*(suspended|disabled|banned|frozen)", RegexOption.IGNORE_CASE),
        Regex("(账号|账户).*(已封禁|被禁用|已冻结|已停用)"),
    )

    /**
     * 明确「换 key 也没用」→ **必须优先判定**。
     *
     * ⚠️ 顺序很重要：如果不先排除这些，会把限流误当余额不足，
     * 白白把好 key 打进 5 小时冷却（一天就这么几个 key，误判代价很大）。
     */
    private val NOT_KEY_FAULT_PATTERNS = listOf(
        Regex("rate[_\\s-]?limit(_reached)?(_error)?", RegexOption.IGNORE_CASE),
        Regex("too\\s+many\\s+requests", RegexOption.IGNORE_CASE),
        Regex("请求(过于频繁|速率|频率)"),
        Regex("(TPM|RPM|TPD|QPS)\\s*(限制|上限|exceeded|limit)", RegexOption.IGNORE_CASE),
        Regex("上游负载(已)?饱和|分组.*饱和"),
        Regex("(服务器|上游|渠道).*(繁忙|过载|超载)"),
        Regex("overload|capacity\\s+issue|server\\s+busy", RegexOption.IGNORE_CASE),
        Regex("invalid[_\\s-]?request", RegexOption.IGNORE_CASE),
        Regex("model\\s+config|unsupported|reasoning|thinking", RegexOption.IGNORE_CASE),
        Regex("context\\s+(length|window)|too\\s+long", RegexOption.IGNORE_CASE),
        Regex("参数(错误|无效)"),
    )

    /**
     * 判断某个错误是否值得换 key 重试。
     *
     * @param status HTTP 状态码（0 表示未知/非 HTTP 错误）
     * @param bodyText 响应体文本
     */
    fun isKeyExhaustedError(status: Int, bodyText: String = ""): Boolean {
        val text = bodyText

        // 1) 限流/过载/参数错误 → 换 key 无用。
        //    但若同一段文本里同时出现明确余额特征（有些网关把两者混报），余额优先。
        val looksLikeQuota = EXHAUSTED_PATTERNS.any { it.containsMatchIn(text) }
        if (!looksLikeQuota && NOT_KEY_FAULT_PATTERNS.any { it.containsMatchIn(text) }) return false

        // 2) 文本有明确余额/失效特征 → 换 key（覆盖 NewAPI 那种不带 4xx 的情况）
        if (looksLikeQuota) return true

        // 3) 状态码兜底
        //    401/403 一般是 key 无效；402 是余额不足（DeepSeek 惯例）。
        //    429 不在这里 —— 它是限流，已在第 1 步排除。
        if (status == 401 || status == 403 || status == 402) return true

        return false
    }
}
