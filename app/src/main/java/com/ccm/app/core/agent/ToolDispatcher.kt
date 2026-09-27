package com.ccm.app.core.agent

import com.ccm.app.core.tool.Tool
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext

/**
 * 工具调度器 —— 决定「哪些工具能并发跑、哪些必须串行」，并管理取消域。
 *
 * 从 `AgentLoop` 抽出来是为了**能单独测**：并发分区和取消域是两块最容易出错的
 * 逻辑，但它们的测试不需要起 HTTP 服务器（不需要 ApiClient）。
 *
 * ## 一、并发分区（对齐 Node 版 `_partitionToolCalls`）
 *
 * 规则：**连续的**「可并发」工具合成一批并发跑，遇到不可并发的就断开。
 *
 * ```
 * 输入:  [Read, Read, Edit, Read]
 * 分区:  [[Read, Read] 并发] [[Edit] 串行] [[Read] 串行]
 *          ↑ 连续两个安全 → 合批        ↑ 不安全 → 单开   ↑ 上一个不安全，断开了
 * ```
 *
 * **为什么是「连续」而不是「全部安全的一起跑」**：
 * 模型给出的工具调用顺序**有语义**（先读再改）。如果把所有安全的抽出来一起跑，
 * 就可能让「第二个 Read」抢在「第一个 Edit」前面执行，读到旧内容。
 * 按连续段分区能同时保住并发收益和顺序语义。
 *
 * ## 二、双取消域（Node 版血泪教训）
 *
 * Node 版踩过：流式响应收尾时会 `streamController.abort()` 清理连接，
 * 而这个 abort **连带杀死了「提前启动的只读工具」**。表现极具迷惑性 ——
 * **本地工具全正常（几十毫秒内跑完），只有联网工具（WebSearch）每次 Interrupted**
 * （因为只有它耗时足够长，能跨过流收尾那一刻）。
 *
 * 定位方法（记下来，下次同类问题直接用）：看 trace 时序。
 * `tool_start 16125ms → stream_end 16177ms → tool_error 16233ms` ——
 * 工具只活了 108ms，网络请求不可能这么快结束，说明**是被外部掐死的**。
 *
 * 所以这里明确分成两个域：
 * - **工具域**（本类管的）：只跟随「用户中断」和「真正的流异常」
 * - **流清理域**（ApiClient 管的）：只负责断连接，**不碰工具**
 *
 * ⚠️ **绝不能把两者合成一个** —— 源码里那些注释和 `early-tool-abort` 测试就是防这个。
 */
object ToolDispatcher {

    /** 一个执行批次。 */
    data class Batch(
        val calls: List<IndexedCall>,
        /** true = 批内可并发；false = 必须串行。 */
        val concurrent: Boolean,
    )

    /** 带原始下标的工具调用（结果要按原序回填，不能按完成序）。 */
    data class IndexedCall(
        val index: Int,
        val id: String,
        val name: String,
        val arguments: String,
    )

    /**
     * 按「连续可并发」切分批次。
     *
     * @param calls 模型给出的工具调用（顺序即语义）
     * @param tools 当前可用工具（用来查 `isConcurrencySafe`）
     */
    fun partition(
        calls: List<IndexedCall>,
        tools: List<Tool>,
    ): List<Batch> {
        val byName = tools.associateBy { it.name }
        val out = mutableListOf<Batch>()

        for (call in calls) {
            // 工具不存在 / 自己抛异常 → 保守当不可并发
            val safe = try {
                byName[call.name]?.isConcurrencySafe == true
            } catch (_: Throwable) {
                false
            }

            val last = out.lastOrNull()
            if (safe && last?.concurrent == true) {
                out[out.size - 1] = last.copy(calls = last.calls + call)
            } else {
                out += Batch(listOf(call), safe)
            }
        }
        return out
    }

    /**
     * 按批次执行，返回**与输入等长**的结果列表（按原序）。
     *
     * @param calls 工具调用（顺序即语义）
     * @param tools 可用工具
     * @param execute 单个调用的执行函数（由 AgentLoop 提供，内部走 ToolRunner）
     */
    suspend fun <R> dispatch(
        calls: List<IndexedCall>,
        tools: List<Tool>,
        execute: suspend (IndexedCall, Job?) -> R,
    ): List<R> {
        if (calls.isEmpty()) return emptyList()

        val batches = partition(calls, tools)
        // 先按原始下标占位，最后按序返回 —— 并发完成顺序不确定，
        // 但回填给模型的结果**必须与请求顺序一致**（否则模型会配错）。
        val slots = arrayOfNulls<Any?>(calls.size)

        // 「工具域」取消信号 —— 本协程的 Job，**整个 dispatch 只取一次**。
        //
        // 用 `currentCoroutineContext()` 而不是裸写 `coroutineContext`：
        // 后者是 `kotlin.coroutines` 的 suspend 属性，**必须单独 import**
        // （`import kotlin.coroutines.coroutineContext`），漏了就是
        // `Unresolved reference 'coroutineContext'`，还会连带把
        // `execute(...)` 的类型推断带崩（报一串看不懂的泛型错误）。
        // `currentCoroutineContext()` 是 kotlinx.coroutines 的挂起函数，
        // 语义相同、在任何 suspend 上下文都能调，不会踩这个坑。
        val toolJob = currentCoroutineContext()[Job]

        for (batch in batches) {
            if (batch.concurrent) {
                // ── 并发批：每个调用一个子协程，全跑完才继续 ──
                coroutineScope {
                    val jobs = batch.calls.map { call ->
                        async { call.index to execute(call, toolJob) }
                    }
                    for ((idx, result) in jobs.awaitAll()) {
                        slots[idx] = result
                    }
                }
            } else {
                // ── 串行批：一个一个来（有顺序依赖） ──
                //
                // ⚠️ 必须传**真实的 Job**，不能传 null。
                // 早期实现传 null，而 AgentLoop 里 `cancelSignal = parentJob ?: Job()`
                // 会新建一个**永远 active 的 Job** —— 于是串行工具完全无法取消：
                // 用户按了中断，工具照样跑到自己结束（长命令 = 卡住不动）。
                // 传真实 Job 后，用户中断 → 协程取消 → Job 失效 → ctx.isCancelled 为真。
                for (call in batch.calls) {
                    slots[call.index] = execute(call, toolJob)
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        return slots.map { it as R }
    }
}
