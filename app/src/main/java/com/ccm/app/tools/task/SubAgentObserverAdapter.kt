package com.ccm.app.tools.task

import com.ccm.app.core.agent.SubAgentManager
import com.ccm.app.core.agent.SubAgentSnapshot

/**
 * 子 Agent 观察器适配 —— 把 core 层的 [SubAgentManager] 接成 tools 层的
 * [AgentTools.SubAgentObserver]。
 *
 * ══════════════════════════════════════════════════════════════
 *  为什么适配器放这边（而不是 core 层直接实现接口）
 * ══════════════════════════════════════════════════════════════
 *
 * **依赖方向是 `core ← tools`**（tools 依赖 core，反之不行）：
 * - `core/` 是零 Android 依赖的纯逻辑层，能在 JVM 单测里跑
 * - 若 `SubAgentManager` 直接实现 `tools/` 的接口 → 依赖倒挂，core 层单测跑不了
 *
 * 所以：接口定义在 tools（[AgentTools.SubAgentObserver]），
 * core 提供能力（[SubAgentManager]），**适配器在 tools 层做**。
 *
 * ══════════════════════════════════════════════════════════════
 *  ⚠️ 字段映射的一个细节：空串 → null
 * ══════════════════════════════════════════════════════════════
 *
 * `SubAgentSnapshot.agentName` 是 `String`（非空），
 * 而 [AgentTools.Snapshot.agentName] 是 `String?`。
 *
 * 这里把**空串转成 null** —— 语义更准：
 * - `null` = 没起名字（展示时不显示「」）
 * - `""` = 起了个空名字（展示时会显示一个空的「」）
 *
 * `agentType` 同理。
 */
fun SubAgentManager.asToolObserver(): AgentTools.SubAgentObserver =
    object : AgentTools.SubAgentObserver {

        override suspend fun list(): List<AgentTools.Snapshot> =
            this@asToolObserver.list().map { it.toToolSnapshot() }

        override suspend fun get(taskId: String): AgentTools.Snapshot? =
            this@asToolObserver.get(taskId)?.toToolSnapshot()

        override suspend fun stop(taskId: String, reason: String): Boolean =
            this@asToolObserver.stop(taskId, reason)

        override suspend fun output(
            taskId: String,
            block: Boolean,
            timeoutSec: Int,
        ): AgentTools.Snapshot? =
            this@asToolObserver.output(taskId, block, timeoutSec)?.toToolSnapshot()
    }

/** 快照字段映射（core → tools） */
fun SubAgentSnapshot.toToolSnapshot(): AgentTools.Snapshot = AgentTools.Snapshot(
    taskId = taskId,
    // 空串 → null（见文件头说明）
    agentName = agentName.takeIf { it.isNotBlank() },
    agentType = agentType.takeIf { it.isNotBlank() },
    description = description,
    status = status,
    turns = turns,
    durationMs = durationMs,
    outputPreview = outputPreview,
    resultPreview = resultPreview,
    error = error,
)
