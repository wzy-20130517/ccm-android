package com.ccm.app.core.agent

import com.ccm.app.core.tool.SubAgentSpec
import com.ccm.app.core.tool.SubAgentResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 子 Agent 生命周期管理。
 *
 * 对应 Node 版 `core/plan.mjs` 的 `runningSubagents` 计数 + Agent 工具组的观察能力。
 *
 * ## 两个职责
 *
 * ### 1. 并发上限（Node 版踩过的坑）
 * Node 版设的是 **9**（`MAX_CONCURRENT_SUBAGENTS`），超限时**不抛错**，
 * 而是返回 `{ok:false, rejected:'concurrency_limit', ...}`。
 *
 * **为什么抛错是错的**：抛错会让子 Agent 误判「这条路走不通」直接放弃任务；
 * 而实际上只是**没排上号**，等一会儿重试就行。所以要用 `rejected` 字段
 * 和真正的失败区分开。
 *
 * ### 2. 观察窗（AgentStatus / AgentOutput / AgentStop 工具的数据源）
 * 子 Agent 在后台跑，主 Agent 需要能：
 * - 列出所有子 Agent 及状态（`AgentStatus`）
 * - 读某个的输出（`AgentOutput`，可阻塞等待）
 * - 中止某个（`AgentStop`，立刻释放并发名额）
 *
 * ## 与 Node 版的差异（有意为之）
 *
 * Node 版用模块级变量计数（`runningSubagents++` / `finally --`）。
 * 这里改成实例级 —— 模块级变量在多实例场景（测试并行跑）会互相干扰，
 * 且状态散落在模块里难清理。
 *
 * @param maxConcurrent 并发上限
 * @param scope 协程作用域（子 Agent 在这里跑）
 * @param spawn 实际派生子 Agent 的函数（由上层注入，避免 core 依赖具体实现）
 */
class SubAgentManager(
    private val maxConcurrent: Int = MAX_CONCURRENT,
    private val scope: CoroutineScope,
    private val spawn: suspend (SubAgentSpec, SubAgentHandle) -> SubAgentResult,
) {

    /** 运行中的子 Agent（taskId → handle）。 */
    private val running = ConcurrentHashMap<String, SubAgentHandle>()

    /** 已完成的历史（保留最近 N 个，供 AgentStatus 查看）。 */
    private val finished = ConcurrentHashMap<String, SubAgentHandle>()

    /** 当前运行数。 */
    private val runningCount = AtomicInteger(0)

    /** 当前运行数（给 UI / 提示用）。 */
    val activeCount: Int get() = runningCount.get()

    /** 是否已达上限。 */
    val isAtCapacity: Boolean get() = runningCount.get() >= maxConcurrent

    /**
     * 派生子 Agent（**后台运行**，立刻返回）。
     *
     * 超限时返回 `rejected = "concurrency_limit"` ——
     * **这不是错误，任务也没失败**，只是没排上号。
     *
     * @return 立刻返回的结果（含 taskId）；真正的输出之后通过 [output] 读
     */
    fun spawnAsync(spec: SubAgentSpec): SubAgentResult {
        if (isAtCapacity) {
            return SubAgentResult(
                ok = false,
                output = "并发已满（$maxConcurrent 个在跑）。这不是错误 —— 任务没失败，" +
                    "只是没排上号。等已有子 Agent 完成后重试，或改成串行。",
                rejected = "concurrency_limit",
            )
        }

        val handle = SubAgentHandle(spec)
        running[handle.taskId] = handle
        runningCount.incrementAndGet()

        handle.job = scope.launch {
            try {
                val result = spawn(spec, handle)
                handle.finish(result)
            } catch (e: kotlinx.coroutines.CancellationException) {
                handle.finish(
                    SubAgentResult(ok = false, output = "已中止", error = "killed", taskId = handle.taskId)
                )
                throw e
            } catch (e: Throwable) {
                handle.finish(
                    SubAgentResult(
                        ok = false,
                        output = e.message ?: "子 Agent 异常",
                        error = e.message,
                        taskId = handle.taskId,
                    )
                )
            } finally {
                runningCount.decrementAndGet()
                running.remove(handle.taskId)
                // 移到已完成表（保留供查询）
                finished[handle.taskId] = handle
                pruneFinished()
            }
        }

        return SubAgentResult(
            ok = true,
            output = "已派发（后台运行）",
            taskId = handle.taskId,
        )
    }

    /**
     * 派生子 Agent 并**等它跑完**（同步）。
     *
     * 用于「必须拿到结果才能继续」的场景。
     */
    suspend fun spawnAndWait(spec: SubAgentSpec): SubAgentResult {
        if (isAtCapacity) {
            return SubAgentResult(
                ok = false,
                output = "并发已满（$maxConcurrent 个在跑）。等已有子 Agent 完成后重试，或改成串行。",
                rejected = "concurrency_limit",
            )
        }

        val handle = SubAgentHandle(spec)
        running[handle.taskId] = handle
        runningCount.incrementAndGet()

        return try {
            val result = spawn(spec, handle)
            handle.finish(result)
            result
        } catch (e: kotlinx.coroutines.CancellationException) {
            handle.finish(SubAgentResult(ok = false, output = "已中止", error = "killed"))
            throw e
        } catch (e: Throwable) {
            val r = SubAgentResult(ok = false, output = e.message ?: "子 Agent 异常", error = e.message)
            handle.finish(r)
            r
        } finally {
            runningCount.decrementAndGet()
            running.remove(handle.taskId)
            finished[handle.taskId] = handle
            pruneFinished()
        }
    }

    /**
     * 列出所有子 Agent 快照（运行中 + 最近完成的）。
     *
     * **运行中的排在前面** —— AgentStatus 最常看的是「现在在跑什么」。
     */
    fun list(): List<SubAgentSnapshot> =
        running.values.map { it.snapshot() } + finished.values.map { it.snapshot() }

    /** 查一个。 */
    fun get(taskId: String): SubAgentSnapshot? =
        (running[taskId] ?: finished[taskId])?.snapshot()

    /**
     * 中止一个。
     *
     * **名额立刻释放** —— 这是 AgentStop 最重要的语义：
     * 发现派错方向时不该干等它跑完白占名额。
     *
     * @return 是否真的中止了（找不到返回 false）
     */
    fun stop(taskId: String, reason: String = ""): Boolean {
        val handle = running[taskId] ?: return false
        handle.stopReason = reason
        handle.job?.cancel()
        // 名额在 finally 里释放（cancel 会触发 finally）
        return true
    }

    /**
     * 读输出。
     *
     * @param block true = 阻塞等它结束；false = 立刻返回当前快照
     */
    suspend fun output(taskId: String, block: Boolean, timeoutSec: Int): SubAgentSnapshot? {
        val handle = running[taskId] ?: finished[taskId] ?: return null

        if (block && handle.job?.isActive == true) {
            // 等它跑完（或超时）
            try {
                kotlinx.coroutines.withTimeoutOrNull(timeoutSec.coerceAtLeast(1) * 1000L) {
                    handle.job?.join()
                }
            } catch (_: Throwable) {
            }
        }
        return handle.snapshot()
    }

    /** 清空历史（`/clear` 时调）。 */
    fun clearFinished() {
        finished.clear()
    }

    /** 保留最近 N 个已完成记录（防无限增长）。 */
    private fun pruneFinished() {
        if (finished.size <= MAX_FINISHED_KEEP) return
        finished.entries
            .sortedBy { it.value.finishedAt }
            .take(finished.size - MAX_FINISHED_KEEP)
            .forEach { finished.remove(it.key) }
    }

    companion object {
        /**
         * 并发上限：24。
         *
         * Node 版曾是 9，后调到 24（用户设定）。**这不是随意数字** ——
         * 它要同时满足：① 不撑爆手机资源；② 中转站不限并发时能跑满。
         *
         * ⚠️ 递归派生共用这个名额池（子 Agent 还能再派子 Agent），
         * 所以每层扇出都要算进总数。
         */
        const val MAX_CONCURRENT = 24

        /** 已完成记录保留数。 */
        private const val MAX_FINISHED_KEEP = 50
    }
}

/**
 * 一个子 Agent 的运行时句柄。
 *
 * 由 [SubAgentManager] 创建并维护，子 Agent 本身通过它上报进度。
 */
class SubAgentHandle(val spec: SubAgentSpec) {

    /** 任务 id。 */
    val taskId: String = "sa-" + java.util.UUID.randomUUID().toString().take(8)

    /** 显示名（spec 没给就用 taskId）。 */
    val agentName: String = spec.agentName ?: taskId

    /** 协程 Job（用于中止）。 */
    var job: Job? = null

    /** 启动时间。 */
    val startedAt: Long = System.currentTimeMillis()

    /** 结束时间（0 = 未结束）。 */
    @Volatile
    var finishedAt: Long = 0

    /** 状态：pending / running / completed / failed / killed。 */
    @Volatile
    var status: String = "running"

    /** 轮次（子 Agent 上报）。 */
    @Volatile
    var turns: Int = 0

    /** 输出尾部（子 Agent 上报，给 AgentStatus 看）。 */
    @Volatile
    var outputTail: String = ""

    /** 最终结果。 */
    @Volatile
    var result: String = ""

    /** 错误信息。 */
    @Volatile
    var error: String? = null

    /** 中止原因。 */
    @Volatile
    var stopReason: String = ""

    /** 子 Agent 上报进度（它自己调）。 */
    fun report(turns: Int? = null, outputTail: String? = null) {
        turns?.let { this.turns = it }
        outputTail?.let { this.outputTail = it.takeLast(2000) }
    }

    /** 标记结束。 */
    fun finish(result: SubAgentResult) {
        finishedAt = System.currentTimeMillis()
        this.result = result.output
        this.error = result.error
        status = when {
            result.error == "killed" -> "killed"
            result.ok -> "completed"
            else -> "failed"
        }
        if (turns == 0) turns = result.turns
    }

    /** 生成快照。 */
    fun snapshot(): SubAgentSnapshot = SubAgentSnapshot(
        taskId = taskId,
        agentName = agentName,
        agentType = spec.subagentType,
        description = spec.description,
        status = status,
        turns = turns,
        durationMs = (if (finishedAt > 0) finishedAt else System.currentTimeMillis()) - startedAt,
        outputPreview = outputTail,
        resultPreview = result,
        error = error,
    )
}

/** 子 Agent 快照（给 AgentStatus 工具 / UI 用）。 */
data class SubAgentSnapshot(
    val taskId: String,
    val agentName: String,
    val agentType: String,
    val description: String,
    /** pending / running / completed / failed / killed */
    val status: String,
    val turns: Int,
    val durationMs: Long,
    val outputPreview: String,
    val resultPreview: String,
    val error: String?,
) {
    /** 是否是终态。 */
    val isTerminal: Boolean get() = status in setOf("completed", "failed", "killed")

    /** 给 UI/工具的一句话描述。 */
    fun describe(): String {
        val dur = "%.1fs".format(durationMs / 1000.0)
        return "[$taskId] $agentName ($agentType) · $status · ${turns}轮 · $dur" +
            if (error != null) " · 错误: $error" else ""
    }
}
