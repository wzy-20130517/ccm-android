package com.ccm.app.tools.task

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GoalRuntime 的回归测试 —— 跨轮驱动、终止判定、预算收尾。
 *
 * ## 为什么必须有
 * goal 模式的每条终止路径都有「静默失效」或「静默跑飞」的风险：
 * - 预算判定错了 → 要么无限跑（烧光额度），要么第一轮就停（白设目标）
 * - 终止判定错了 → 模型说完就 complete 却继续跑，或没做完就停
 * - 收尾轮错了 → 预算用尽直接断，用户不知道做到哪
 *
 * 这些都是**跑起来才知道**的行为，所以用假的 `runTurn` 把整条循环
 * 在 JVM 上跑一遍 —— 不碰网络、不碰 Android。
 *
 * ## 测试策略
 * 注入一个记账用的 `runTurn`，它按脚本改 store 的状态（模拟模型调
 * GoalStatus）。这样能把「模型自报完成」「预算耗尽」「外部叫停」
 * 三条路径全部走一遍。
 */
class GoalRuntimeTest {

    private fun tempStore(): Pair<GoalStore, File> {
        val dir = File(System.getProperty("java.io.tmpdir"), "goal-test-${System.nanoTime()}")
        dir.mkdirs()
        return GoalStore(dir) to dir
    }

    // ═══════════════════════ 基本推进 ═══════════════════════

    @Test
    fun `模型自报完成 —— 循环立刻结束且不再消耗轮次`() = runBlocking {
        val (store, _) = tempStore()
        val sid = "s1"
        store.set(sid, "把 X 改成 Y", proof = "测试全绿", turnsBudget = 10)

        var calls = 0
        val rt = GoalRuntime(
            store = store,
            sessionId = sid,
            runTurn = { _ ->
                calls++
                if (calls == 2) {
                    // 第二轮模型宣布完成
                    store.tryStatus(sid, "complete", "测试跑了，全绿")
                }
            },
        )

        val out = rt.run("开始")
        assertEquals("应跑 2 轮", 2, out.turns)
        assertEquals("结束原因应是 complete", "complete", out.reason)
        assertEquals("状态应为 complete", "complete", store.get(sid)?.status)
        assertEquals("不应多跑", 2, calls)
    }

    @Test
    fun `未宣布完成 —— 一直跑到预算耗尽`() = runBlocking {
        val (store, _) = tempStore()
        val sid = "s2"
        store.set(sid, "永远做不完的任务", turnsBudget = 3)

        var calls = 0
        val rt = GoalRuntime(store = store, sessionId = sid, runTurn = { calls++ })

        val out = rt.run("开始")
        assertEquals("预算 3 轮 → 3 轮 + 1 轮收尾 = 4", 4, out.turns)
        assertEquals("结束原因应是 budget", "budget", out.reason)
        assertNotNull("收尾后状态应被标记", store.get(sid)?.status)
    }

    @Test
    fun `预算耗尽时最后一轮必须是收尾指令`() = runBlocking {
        val (store, _) = tempStore()
        val sid = "s3"
        store.set(sid, "任务", turnsBudget = 1)

        val prompts = mutableListOf<String>()
        val rt = GoalRuntime(store = store, sessionId = sid, runTurn = { prompts += it })
        rt.run("开始")

        assertTrue("至少要有两轮（正常 + 收尾）", prompts.size >= 2)
        val last = prompts.last()
        assertTrue(
            "最后一轮必须是收尾指令（不许静默中断），实际: ${last.take(80)}",
            last.contains("预算耗尽"),
        )
        assertTrue("收尾轮要禁止改文件", last.contains("不要再改任何文件"))
        assertTrue("收尾轮要给出四段交接模板", last.contains("已完成") && last.contains("下一步"))
    }

    @Test
    fun `续轮注入 —— 必须带契约且标明非用户发言`() = runBlocking {
        val (store, _) = tempStore()
        val sid = "s4"
        store.set(sid, "目标 A", proof = "判据 B", turnsBudget = 3)

        val prompts = mutableListOf<String>()
        val rt = GoalRuntime(store = store, sessionId = sid, runTurn = { prompts += it })
        rt.run("首轮输入")

        assertEquals("首轮应是调用方给的原文", "首轮输入", prompts.first())
        val second = prompts[1]
        assertTrue("续轮要标明非用户发言", second.contains("非用户发言"))
        assertTrue("续轮要带目标", second.contains("目标 A"))
        assertTrue("续轮要带完成判据", second.contains("判据 B"))
        assertTrue("续轮要带完成审计", second.contains("完成审计"))
        assertTrue("续轮要带阻塞审计", second.contains("阻塞审计"))
    }

    // ═══════════════════════ 阻塞判定 ═══════════════════════

    @Test
    fun `阻塞未达阈值 —— 提示继续想办法`() = runBlocking {
        val (store, _) = tempStore()
        val sid = "s5"
        store.set(sid, "任务", turnsBudget = 2)

        val rt = GoalRuntime(store = store, sessionId = sid, runTurn = { })
        rt.run("开始")

        val contract = GoalRuntime.buildGoalContract(store.get(sid)!!)
        assertTrue(
            "未达阈值时应提示「继续想别的办法」，实际: $contract",
            contract.contains("继续想别的办法"),
        )
    }

    @Test
    fun `阻塞达阈值 —— 提示可以判 blocked`() {
        val (store, _) = tempStore()
        val sid = "s6"
        store.set(sid, "任务", turnsBudget = 10)
        // 同一障碍连续 3 次
        repeat(GoalStore.BLOCKED_STREAK_THRESHOLD) { store.noteBlocker(sid, "缺 API key") }

        val contract = GoalRuntime.buildGoalContract(store.get(sid)!!)
        assertTrue(
            "达阈值时应允许判 blocked，实际: $contract",
            contract.contains("已达阈值，可以判 blocked"),
        )
    }

    // ═══════════════════════ 外部叫停 ═══════════════════════

    @Test
    fun `外部叫停 —— 转 paused 保留进度`() = runBlocking {
        val (store, _) = tempStore()
        val sid = "s7"
        store.set(sid, "任务", turnsBudget = 10)

        var stop = false
        var calls = 0
        val rt = GoalRuntime(
            store = store,
            sessionId = sid,
            runTurn = { calls++; if (calls == 2) stop = true },
            shouldStop = { stop },
        )

        val out = rt.run("开始")
        assertEquals("结束原因应是 stopped", "stopped", out.reason)
        assertEquals("状态应转 paused", "paused", store.get(sid)?.status)
        assertTrue("进度应保留（turnsUsed > 0）", (store.get(sid)?.turnsUsed ?: 0) > 0)
    }

    @Test
    fun `用户中断 —— 转 paused 且异常继续上抛`() = runBlocking {
        val (store, _) = tempStore()
        val sid = "s8"
        store.set(sid, "任务", turnsBudget = 10)

        val rt = GoalRuntime(
            store = store,
            sessionId = sid,
            runTurn = { throw CancellationException("用户按了 Ctrl+C") },
        )

        var caught = false
        try {
            rt.run("开始")
        } catch (_: CancellationException) {
            caught = true
        }
        assertTrue("CancellationException 必须继续上抛（不能吞）", caught)
        assertEquals("状态应转 paused", "paused", store.get(sid)?.status)
    }

    // ═══════════════════════ 边界 ═══════════════════════

    @Test
    fun `没有目标 —— 立刻返回 no_goal 不跑任何轮`() = runBlocking {
        val (store, _) = tempStore()
        var calls = 0
        val rt = GoalRuntime(store = store, sessionId = "none", runTurn = { calls++ })

        val out = rt.run("开始")
        assertEquals("没有目标时应立刻返回", "no_goal", out.reason)
        assertEquals("不该跑任何轮", 0, calls)
    }

    @Test
    fun `目标已是终态 —— 不再推进`() = runBlocking {
        val (store, _) = tempStore()
        val sid = "s9"
        store.set(sid, "任务")
        store.tryStatus(sid, "complete", "之前就做完了")

        var calls = 0
        val rt = GoalRuntime(store = store, sessionId = sid, runTurn = { calls++ })

        val out = rt.run("开始")
        assertEquals("complete", out.reason)
        assertEquals("终态目标不该再跑", 0, calls)
    }

    @Test
    fun `token 预算耗尽 —— 也能触发收尾`() = runBlocking {
        val (store, _) = tempStore()
        val sid = "s10"
        store.set(sid, "任务", turnsBudget = 100, tokensBudget = 1000)

        val prompts = mutableListOf<String>()
        val rt = GoalRuntime(
            store = store,
            sessionId = sid,
            runTurn = { prompts += it },
            getTokens = { 2000L },   // 每次都超预算
        )
        val out = rt.run("开始")

        assertEquals("token 超限应触发 budget 收尾", "budget", out.reason)
        assertTrue("最后一轮应是收尾", prompts.last().contains("预算耗尽"))
        assertTrue("应提到 token 预算", prompts.last().contains("token 预算"))
    }

    @Test
    fun `轮次预算为 1 —— 第一轮后立刻进收尾`() = runBlocking {
        val (store, _) = tempStore()
        val sid = "s11"
        store.set(sid, "任务", turnsBudget = 1)

        val prompts = mutableListOf<String>()
        val rt = GoalRuntime(store = store, sessionId = sid, runTurn = { prompts += it })
        rt.run("开始")

        assertEquals("1 轮预算 → 1 正常 + 1 收尾", 2, prompts.size)
        assertTrue("第二轮应是收尾", prompts[1].contains("预算耗尽"))
    }

    // ═══════════════════════ 契约文本 ═══════════════════════

    @Test
    fun `契约 —— 未给判据时必须提示模型自己明确一个`() {
        val (store, _) = tempStore()
        val sid = "s12"
        store.set(sid, "目标", proof = "")
        val c = GoalRuntime.buildGoalContract(store.get(sid)!!)
        assertTrue(
            "没判据时要逼模型自己定一个，实际: $c",
            c.contains("你必须先把它明确成一个可检查的判据"),
        )
    }

    @Test
    fun `契约 —— 必须包含三条结束路径与两条审计`() {
        val (store, _) = tempStore()
        val sid = "s13"
        store.set(sid, "目标", proof = "判据")
        val c = GoalRuntime.buildGoalContract(store.get(sid)!!)

        assertTrue("要有「还有实质工作」路径", c.contains("还有实质工作"))
        assertTrue("要有 complete 路径", c.contains("GoalStatus(status:\"complete\")"))
        assertTrue("要有 blocked 路径", c.contains("blocked"))
        assertTrue("完成审计要禁止把计划当完成", c.contains("只产出了计划"))
        assertTrue("要禁止拿预算当完成理由", c.contains("预算快用完不是完成的理由"))
        assertTrue("阻塞审计要列出不算阻塞的情形", c.contains("不算阻塞"))
    }

    @Test
    fun `预算行 —— 三种格式都要能渲染`() {
        val (store, _) = tempStore()
        val sid = "s14"

        // 只有轮次
        store.set(sid, "目标", turnsBudget = 5)
        var line = GoalRuntime.budgetLine(store.get(sid)!!)
        assertTrue("应含轮次，实际: $line", line.contains("轮次"))

        // 轮次 + 时间
        store.set(sid, "目标", turnsBudget = 5, timeBudgetMs = 60_000)
        line = GoalRuntime.budgetLine(store.get(sid)!!)
        assertTrue("应含时间，实际: $line", line.contains("时间"))

        // 轮次 + token
        store.set(sid, "目标", turnsBudget = 5, tokensBudget = 10000)
        line = GoalRuntime.budgetLine(store.get(sid)!!)
        assertTrue("应含 token，实际: $line", line.contains("token"))
    }

    @Test
    fun `formatElapsed —— 三档格式`() {
        assertEquals("12s", GoalRuntime.formatElapsed(12_000))
        assertEquals("3m20s", GoalRuntime.formatElapsed(200_000))
        assertEquals("1h05m", GoalRuntime.formatElapsed(3_900_000))
        assertEquals("0s", GoalRuntime.formatElapsed(-100))
    }

    @Test
    fun `收敛提示 —— 预算用掉 75% 后必须出现`() {
        val (store, _) = tempStore()
        val sid = "s15"
        store.set(sid, "目标", turnsBudget = 4)
        repeat(3) { store.consumeTurn(sid) }   // 3/4 = 75%

        val c = GoalRuntime.buildGoalContract(store.get(sid)!!)
        assertTrue(
            "75% 后应进入收敛提示，实际: $c",
            c.contains("接近上限") && c.contains("收敛"),
        )
    }

    @Test
    fun `收敛提示 —— 预算充裕时不该出现`() {
        val (store, _) = tempStore()
        val sid = "s16"
        store.set(sid, "目标", turnsBudget = 100)
        store.consumeTurn(sid)   // 1/100

        val c = GoalRuntime.buildGoalContract(store.get(sid)!!)
        assertTrue("预算充裕时应说稳步推进，实际: $c", c.contains("预算充裕"))
        assertFalse("不该提收敛", c.contains("接近上限"))
    }
}
