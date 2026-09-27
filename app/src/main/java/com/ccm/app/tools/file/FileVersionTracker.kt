package com.ccm.app.tools.file

import java.io.File

/**
 * 并发写保护 —— 记录「本进程读过的文件版本」，写前比对。
 *
 * ══════════════════════════════════════════════════════════════
 *  要堵的漏（这是整个工具系统里最难查的 bug 类型）
 * ══════════════════════════════════════════════════════════════
 *
 * ```
 * worker-A: Read file.txt  → 看到 "v1"
 * worker-B: Edit file.txt  → 写入 "v2"        （B 的工作已完成）
 * worker-A: Write file.txt ← 用记忆里的 "v1" 覆盖  ← 灾难
 * ```
 *
 * 结果：**B 的改动静默消失**，退出码 0，无任何告警。
 * 回收站里其实有备份，但没人知道要去找 —— 表现为「明明改了却不生效」。
 *
 * ══════════════════════════════════════════════════════════════
 *  做法（与 CCM `core/file-tools.mjs` 一致）
 * ══════════════════════════════════════════════════════════════
 *
 * Read 时记录 `mtime + size`；Write/Edit 覆盖前比对，不一致就**拒绝写入**
 * 并要求重新 Read。只对「本进程读过的文件」生效 —— 没读过就直接写
 * 视为用户明确要覆盖，不拦（否则新建文件、外部工具生成的文件全被误伤）。
 *
 * ⚠️ 已知局限（只是缩小窗口，没有解决；失败方向全部是 fail-open）：
 *   1. mtime 精度取决于文件系统。FAT / 网络挂载可能只有秒级精度，
 *      「同一秒内 + 长度不变」的修改漏检。size 作为辅助信号能捞回一部分。
 *   2. 校验与写入之间不是原子的（check-then-write），理论上有极窄竞态窗口。
 *   3. key 是「规范化后的绝对路径」，符号链接 / 不同挂载点指向同一文件识别不到。
 *   4. 记录是进程内的，跨进程（如 proot 里的命令改了同一文件）识别不到。
 *   以上任一情况都退回「无校验」的旧行为，**不会误拦**。
 *
 * 【线程安全】用 synchronized 保护，因为工具可能被并行调度。
 */
object FileVersionTracker {

    /** 记录上限。超了淘汰最早的 —— 淘汰只会退回「不校验」，不会误拦。 */
    private const val MAX_TRACKED = 500

    /** 版本快照：mtime 毫秒 + 文件长度 */
    private data class Version(val mtimeMs: Long, val size: Long)

    /** LRU 顺序：LinkedHashMap 保持插入顺序，重新 put 会移到队尾 */
    private val versions = object : LinkedHashMap<String, Version>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Version>): Boolean {
            return size > MAX_TRACKED
        }
    }

    /** 统计（诊断用） */
    @Volatile
    var hitCount: Int = 0
        private set

    @Volatile
    var rejectCount: Int = 0
        private set

    private fun statVersion(file: File): Version? = try {
        if (file.exists()) Version(file.lastModified(), file.length()) else null
    } catch (_: Throwable) {
        null  // stat 不到就放弃校验（fail-open）
    }

    /**
     * 记录/刷新版本。
     *
     * **必须在两处调用**：① Read 之后 ② 本进程每次成功写盘之后。
     * 漏掉 ② 会导致「读一次、写两次」的第二次写拿旧 mtime 比新文件 → 误拦自己。
     */
    @Synchronized
    fun track(file: File) {
        val v = statVersion(file) ?: return
        val key = keyOf(file)
        versions.remove(key)   // 先删再 put，保证移到队尾（LRU）
        versions[key] = v
    }

    /**
     * 覆盖写前的守卫。
     *
     * @return null = 放行；非 null = 拒绝原因（可直接展示给模型/用户）
     */
    @Synchronized
    fun staleReason(file: File): String? {
        if (!file.exists()) return null          // 新建文件，无冲突可言
        val known = versions[keyOf(file)] ?: return null  // 没读过 → 明确要覆盖，不拦
        val now = statVersion(file) ?: return null
        if (now.mtimeMs == known.mtimeMs && now.size == known.size) return null

        rejectCount++
        return buildString {
            append("文件已被其他 Agent 修改，拒绝覆盖（否则对方的改动会静默消失）：${file.absolutePath}\n")
            append("  记录 mtime ${known.mtimeMs}（${known.size} bytes）\n")
            append("  当前 mtime ${now.mtimeMs}（${now.size} bytes）\n")
            append("请重新 Read 这个文件，把对方的改动合并进来后再写。")
        }
    }

    /** 清空记录（测试/诊断用） */
    @Synchronized
    fun reset() {
        versions.clear()
        hitCount = 0
        rejectCount = 0
    }

    /** 当前跟踪的文件数（诊断用） */
    @Synchronized
    fun trackedCount(): Int = versions.size

    private fun keyOf(file: File): String = try {
        file.canonicalPath
    } catch (_: Throwable) {
        file.absolutePath
    }
}
