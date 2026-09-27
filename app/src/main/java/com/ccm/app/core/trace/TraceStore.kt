package com.ccm.app.core.trace

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * 运行 trace —— 每轮对话的**结构化日志**（JSONL）。
 *
 * 对应 Node 版 `core/trace.mjs`（307 行）。
 *
 * ## 为什么要有它（不是「加个 log 而已」）
 *
 * Node 版那些最难查的 bug，**全靠 trace 定位**，光看现象根本猜不到：
 *
 * | 现象 | trace 揭示的真相 |
 * |---|---|
 * | 「只有 WebSearch 每次 Interrupted」 | `tool_start 16125ms → stream_end 16177ms → tool_error 16233ms` —— 工具只活 108ms，网络请求不可能这么快结束，说明**是被外部掐死的**，不是自己失败 |
 * | 「一次超时后所有请求永久卡死」 | `_lastPhase` 显示卡在 `request_start`（请求根本没发出去）vs `headers_received`（响应头没回来）—— 两者排查方向完全不同 |
 * | 「十几分钟一声不响」 | `api_retry` 事件暴露了**静默重试**：一条错误实际是 3 次请求的合计 |
 *
 * **没有 trace，这三个问题都只能靠猜。**
 *
 * ## 三条设计约束
 *
 * ### 1. 只存统计，不存正文
 * `api_response` 只记 usage/duration_ms，**不记 assistant 正文**。
 * 理由：① 正文可能几 KB，写盘量大；② 正文含用户隐私。
 * 代价：**子 Agent 的结论拿不到，必须让它自己 Write 到文件**。
 *
 * ### 2. 脱敏是硬要求
 * API key、token、密码绝不能进 trace。见 [Redactor]。
 *
 * ### 3. 写盘失败不能影响主流程
 * trace 是诊断设施 —— **宁可丢一条详情，也不能写爆磁盘或拖慢对话**。
 * 所以：文件达上限就停写、单条事件过大就降级、一切异常都吞掉。
 *
 * ## 用法
 * ```kotlin
 * val trace = TraceStore(dir = tracesDir)
 * trace.emit(TraceEvents.RUN_START, mapOf("input" to text))
 * // ... 跑 ...
 * trace.end(mapOf("status" to "completed", "turns" to 5))
 * ```
 */
class TraceStore(
    private val dir: File,
    val runId: String = newRunId(),
    enabled: Boolean = true,
    /** 单文件字节上限（超过停写）。 */
    private val maxFileBytes: Long = MAX_FILE_BYTES,
) {

    /** 事件序号（从 1 开始）。 */
    private var seq: Int = 0

    /** 启动时间（算 elapsed_ms）。 */
    private val startedAt: Long = System.currentTimeMillis()

    /** 父 trace id（子 Agent 用）。 */
    var parentRunId: String? = null

    /** 是否还在记录（达上限后置 false）。 */
    @Volatile
    private var active: Boolean = enabled

    private val file: File get() = File(dir, "$runId.jsonl")

    /**
     * 记一条事件。
     *
     * @param type 事件类型（见 [TraceEvents] 的常量）
     * @param data 事件数据（会自动脱敏 + 截断）
     */
    fun emit(type: String, data: Map<String, Any?> = emptyMap()) {
        if (!active) return
        try {
            // 文件达上限 → 停写（防无限增长）
            if (file.exists() && file.length() >= maxFileBytes) {
                active = false
                return
            }

            val event = buildJsonObject {
                put("seq", JsonPrimitive(++seq))
                put("ts", JsonPrimitive(Instant.now().toString()))
                put("elapsed_ms", JsonPrimitive(System.currentTimeMillis() - startedAt))
                put("run_id", JsonPrimitive(runId))
                put("type", JsonPrimitive(type))
                put("data", Redactor.redact(data))
            }

            var line = event.toString()
            // 【最后一道闸门】redact 已限制节点数/字符数，但序列化本身
            // 仍可能因形状怪异（超长键名、深嵌套）而变大。
            // 超限就只留类型和摘要 —— 诊断设施不能写爆磁盘。
            if (line.length > MAX_EVENT_BYTES) {
                line = buildJsonObject {
                    put("seq", JsonPrimitive(seq))
                    put("ts", JsonPrimitive(Instant.now().toString()))
                    put("run_id", JsonPrimitive(runId))
                    put("type", JsonPrimitive(type))
                    put("data", buildJsonObject {
                        put("_oversized", JsonPrimitive(true))
                        put("bytes", JsonPrimitive(line.length))
                    })
                }.toString()
            }

            dir.mkdirs()
            file.appendText(line + "\n")
        } catch (_: Throwable) {
            // 写盘失败绝不影响主流程
        }
    }

    /**
     * 派生一个子 trace（子 Agent 用）。
     *
     * 子 trace 有**自己的文件**，但记下 `parent_run_id` —— 这样能从主 trace
     * 追到所有子 Agent 的完整过程。
     */
    fun child(label: String, data: Map<String, Any?> = emptyMap()): TraceStore {
        val c = TraceStore(dir = dir, enabled = active, maxFileBytes = maxFileBytes)
        c.parentRunId = runId
        c.emit(TraceEvents.CHILD_START, data + mapOf("label" to label, "parent_run_id" to runId))
        return c
    }

    /** 结束 trace（记 run_end + 耗时）。 */
    fun end(data: Map<String, Any?> = emptyMap()) {
        emit(TraceEvents.RUN_END, data + mapOf("duration_ms" to (System.currentTimeMillis() - startedAt)))
        active = false
    }

    companion object {
        /** 单文件上限：8MB（对齐 Node 版）。 */
        const val MAX_FILE_BYTES: Long = 8L * 1024 * 1024

        /** 单条事件上限：64KB（超了就只留摘要）。 */
        const val MAX_EVENT_BYTES: Int = 64 * 1024

        /** 生成一个 run id。 */
        fun newRunId(): String =
            Instant.now().toString().replace(":", "-").take(19) + "-" +
                UUID.randomUUID().toString().take(6)

        /** 列出 trace 文件（最新在前）。 */
        fun list(dir: File, limit: Int = 20): List<TraceFile> = try {
            dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
                ?.sortedByDescending { it.lastModified() }
                ?.take(limit)
                ?.map { TraceFile(it.name.removeSuffix(".jsonl"), it.length(), it.lastModified()) }
                ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }

        /**
         * 读一个 trace 的事件。
         *
         * @param runId trace id（**会做白名单校验**，防路径穿越）
         */
        fun read(dir: File, runId: String, limit: Int = 200): List<String> {
            // 防路径穿越：只允许字母数字和连字符
            if (!runId.matches(Regex("^[a-zA-Z0-9\\-]+$"))) return emptyList()
            val f = File(dir, "$runId.jsonl")
            if (!f.exists()) return emptyList()
            return try {
                f.readLines().takeLast(limit)
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }
}

/** trace 文件摘要。 */
data class TraceFile(val runId: String, val sizeBytes: Long, val modifiedAt: Long)

/**
 * trace 事件类型常量。
 *
 * ⚠️ **不要改这些字符串** —— 它们写进 jsonl 文件，改了会让历史日志和新日志对不上。
 * 与 Node 版 `trace.mjs` 的 type 值一一对应。
 */
object TraceEvents {
    const val RUN_START = "run_start"
    const val RUN_END = "run_end"
    const val RUN_ERROR = "run_error"

    /** 一次 API 请求开始。 */
    const val API_REQUEST = "api_request"

    /** 一次 API 响应完成（**只存统计不存正文**）。 */
    const val API_RESPONSE = "api_response"

    /** API 错误（含 category / retryable）。 */
    const val API_ERROR = "api_error"

    /**
     * api 层内部重试。
     *
     * ⚠️ **这个事件至关重要** —— 没有它，一次「181 秒的错误」在 trace 里
     * 看起来像「一次请求」，完全看不出实际发了 3 次。
     * Node 版为此专门加过：静默重试是排查黑洞。
     */
    const val API_RETRY = "api_retry"

    /** 流式 watchdog 触发（含 phase，用于定位卡在哪一段）。 */
    const val STREAM_WATCHDOG = "stream_watchdog"

    const val TOOL_START = "tool_start"
    const val TOOL_END = "tool_end"
    const val TOOL_ERROR = "tool_error"

    const val CHILD_START = "child_start"

    /** 重试调度（含 backoff_ms / category）。 */
    const val RETRY_SCHEDULED = "retry_scheduled"
}

/**
 * 脱敏器 —— 把 API key / token / 密码替换成 `[REDACTED]`。
 *
 * ## 为什么必须有
 * trace 文件会长期保留、可能被用户分享出来求助。
 * 一条带 key 的日志泄漏 = 用户的 key 泄漏。
 *
 * ## 脱敏策略（双保险）
 * 1. **按 key 名**：字段名含 `key` / `token` / `secret` / `password` 的一律打码
 * 2. **按值形态**：`sk-xxx` / `ghp_xxx` / `Bearer` 后面跟的长串
 *
 * ## 三道闸门（防「脱敏本身把内存跑爆」）
 * - 节点数上限（防扇出爆炸：30 个子节点各自再扇 30^4）
 * - 深度上限
 * - 字符总量上限
 *
 * Node 版踩过：只 `slice(0,30)` 数组是不够的 —— 30 个子节点各自还能再扇出，
 * 要跑满 2000 节点才停。
 */
object Redactor {

    private const val MAX_NODES = 2000
    private const val MAX_DEPTH = 5
    private const val MAX_CHARS = 32_000
    private const val MAX_FIELD_CHARS = 2000

    /** 字段名命中这些词就打码（不区分大小写）。 */
    private val SECRET_KEY_RE = Regex(
        "key|token|secret|password|passwd|authorization|auth|cookie|credential",
        RegexOption.IGNORE_CASE,
    )

    /** 值形态命中这些模式就打码。 */
    private val SECRET_VALUE_RE = Regex(
        "sk-[A-Za-z0-9_\\-]{8,}" +
            "|ghp_[A-Za-z0-9]{20,}" +
            "|gho_[A-Za-z0-9]{20,}" +
            "|Bearer\\s+[A-Za-z0-9_\\-.]{16,}",
    )

    private class Budget {
        var nodes = 0
        var chars = 0
    }

    /** 脱敏一个 Map（常用入口）。 */
    fun redact(map: Map<String, Any?>): JsonObject = buildJsonObject {
        val budget = Budget()
        for ((k, v) in map) {
            if (SECRET_KEY_RE.containsMatchIn(k)) {
                put(k, JsonPrimitive("[REDACTED]"))
            } else {
                put(k, redact(v, budget, 1))
            }
        }
    }

    private fun redact(value: Any?, budget: Budget, depth: Int): JsonElement {
        // 三道闸门
        if (++budget.nodes > MAX_NODES) return JsonPrimitive("[truncated]")
        if (depth > MAX_DEPTH) return JsonPrimitive("[depth omitted]")

        return when (value) {
            null -> JsonNull
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is String -> JsonPrimitive(capString(value, budget))
            is Map<*, *> -> buildJsonObject {
                for ((k, v) in value) {
                    val key = k?.toString() ?: continue
                    if (SECRET_KEY_RE.containsMatchIn(key)) {
                        put(key, JsonPrimitive("[REDACTED]"))
                    } else {
                        put(key, redact(v, budget, depth + 1))
                    }
                }
            }
            is Iterable<*> -> {
                val room = (MAX_NODES - budget.nodes).coerceAtLeast(0)
                if (room == 0) {
                    JsonPrimitive("[truncated]")
                } else {
                    val list = value.toList()
                    val cap = minOf(list.size, 30, maxOf(1, room))
                    val arr = JsonArray((0 until cap).map { redact(list[it], budget, depth + 1) })
                    if (list.size > cap) {
                        JsonArray(arr + JsonPrimitive("…[${list.size - cap} more]"))
                    } else {
                        arr
                    }
                }
            }
            is ByteArray -> JsonPrimitive("[Binary ${value.size}B]")
            else -> JsonPrimitive(capString(value.toString(), budget))
        }
    }

    private fun capString(raw: String, budget: Budget): String {
        val clean = raw.replace(SECRET_VALUE_RE, "[REDACTED]")
        if (budget.chars > MAX_CHARS) return "[${clean.length} chars omitted]"
        val room = MAX_CHARS - budget.chars
        val limit = minOf(MAX_FIELD_CHARS, room)
        val capped = if (clean.length > limit) {
            clean.take(limit) + "…[${clean.length} chars]"
        } else {
            clean
        }
        budget.chars += capped.length
        return capped
    }

    /** 便捷：预览一个长文本（trace 里记输入用）。 */
    fun preview(value: Any?, max: Int = 800): String {
        val s = value?.toString() ?: return ""
        return if (s.length > max) s.take(max) + "…[${s.length} chars]" else s
    }
}
