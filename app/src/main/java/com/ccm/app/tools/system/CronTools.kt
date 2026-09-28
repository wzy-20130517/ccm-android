package com.ccm.app.tools.system

import com.ccm.app.core.tool.Tool
import com.ccm.app.core.tool.ToolContext
import com.ccm.app.core.tool.ToolResult
import com.ccm.app.core.tool.ToolSchema
import com.ccm.app.core.tool.ToolSchema.bool
import com.ccm.app.core.tool.ToolSchema.str
import com.ccm.app.tools.file.AtomicFile
import kotlinx.serialization.json.JsonObject
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * 定时任务 —— CronCreate / CronList / CronDelete。
 *
 * 参照 Node 版 `core/cron.mjs`（246 行）+ `core/tools-cron.mjs`（79 行）。
 *
 * ══════════════════════════════════════════════════════════════
 *  与 CLI 版的差异（重要）
 * ══════════════════════════════════════════════════════════════
 *
 * CLI 版靠**进程内的定时器**（进程活着才触发）。APK 侧进程随时可能被系统杀，
 * 所以：
 * - **durable=true** 的任务写盘，由 [CronStore] 持久化，App 启动时重新装载
 * - **durable=false** 的任务只在内存里，进程结束即消失（与 CLI 的 session 级一致）
 *
 * ⚠️ 真正的「到点唤醒 App」需要 `AlarmManager` + `WorkManager`
 * （Android 的省电策略会杀后台定时器）。本类只负责**存储与计算**，
 * 到点触发由 App 层用 WorkManager 实现。
 *
 * ══════════════════════════════════════════════════════════════
 *  cron 表达式（标准 5 字段）
 * ══════════════════════════════════════════════════════════════
 *
 * ```
 * 分 时 日 月 周
 * 0  9  *  *  *     每天 9:00
 * [星]/5 * * * *    每 5 分钟
 * 0  0  1  *  *     每月 1 号 0:00
 * 30 8  *  *  1     每周一 8:30
 * ```
 *
 * 支持 `*`、数字、步长（星号后跟斜杠加数字）、`a-b` 区间、`a,b,c` 列表。
 * 不支持 `@daily` 这类别名（避免歧义）。
 *
 * ⚠️ 注释里不要写出「星号紧跟斜杠」的字面组合（如步长语法本身）——
 * Kotlin 的块注释**可以嵌套**，那个序列会被当成注释结束符，
 * 导致后面整段文字跑出注释外、编译失败（实测踩过，113 条连锁报错）。
 * 上面用 `[星]/5` 代替就是为了避开它。
 */
class CronStore(private val rootDir: File) {

    companion object {
        /** 支持的最早年份（用于穷举搜索的边界） */
        private const val SEARCH_YEARS = 4
    }

    /** 一条定时任务 */
    data class Task(
        val id: String,
        val cron: String,
        val prompt: String,
        val recurring: Boolean = false,
        val durable: Boolean = false,
        val createdAt: Long = 0L,
        val lastFiredAt: Long = 0L,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("cron", cron)
            put("prompt", prompt)
            put("recurring", recurring)
            put("durable", durable)
            put("createdAt", createdAt)
            put("lastFiredAt", lastFiredAt)
        }

        companion object {
            fun fromJson(o: JSONObject) = Task(
                id = o.optString("id"),
                cron = o.optString("cron"),
                prompt = o.optString("prompt"),
                recurring = o.optBoolean("recurring", false),
                durable = o.optBoolean("durable", false),
                createdAt = o.optLong("createdAt", 0L),
                lastFiredAt = o.optLong("lastFiredAt", 0L),
            )
        }
    }

    private val durableFile: File get() = File(rootDir, "cron-durable.json")

    /** session 级任务（内存，进程结束即消失） */
    private val sessionTasks = mutableListOf<Task>()

    /** 已加载的 durable 任务 */
    private val durableTasks = mutableListOf<Task>()

    init {
        if (!rootDir.exists()) rootDir.mkdirs()
        loadDurable()
    }

    @Synchronized
    private fun loadDurable() {
        durableTasks.clear()
        try {
            val f = durableFile
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { durableTasks += Task.fromJson(it) }
            }
        } catch (_: Throwable) {
            // 文件坏了就当没有（不阻塞启动）
        }
    }

    @Synchronized
    private fun saveDurable() {
        try {
            val arr = JSONArray()
            durableTasks.forEach { arr.put(it.toJson()) }
            AtomicFile.writeText(durableFile, arr.toString(2), createParent = true)
        } catch (_: Throwable) {
            // 存不了不阻塞（下次再试）
        }
    }

    @Synchronized
    fun list(): List<Task> = (durableTasks + sessionTasks).sortedBy { it.createdAt }

    @Synchronized
    fun find(id: String): Task? = (durableTasks + sessionTasks).find { it.id == id }

    /** 创建任务。cron 非法时抛异常（由工具层转成 invalidInput） */
    @Synchronized
    fun create(cron: String, prompt: String, recurring: Boolean, durable: Boolean): Task {
        val parsed = CronExpr.parse(cron)
            ?: throw IllegalArgumentException(
                "cron 表达式非法：\"$cron\"。需要标准 5 字段（分 时 日 月 周），" +
                    "如 \"0 9 * * *\"（每天 9 点）、\"*/5 * * * *\"（每 5 分钟）。" +
                    "支持 * 数字 */N a-b a,b,c",
            )
        if (prompt.isBlank()) throw IllegalArgumentException("prompt 不能为空")

        // ⚠️ 先校验「能找到下次触发时间」再落盘 ——
        // 否则会先写进文件、再删掉，中间如果进程被杀就留下一个永远不触发的僵尸任务。
        // 顺带：这个检查能挡住 `0 0 31 2 *`（2 月 31 号）这类语法合法但永不触发的表达式。
        if (parsed.next(System.currentTimeMillis()) == null) {
            throw IllegalArgumentException(
                "这个 cron 表达式在未来 $SEARCH_YEARS 年内不会触发，请检查：" +
                    "（常见原因：2 月 30/31 号、或日期与月份的限定值互斥）",
            )
        }

        val id = "cron-${System.currentTimeMillis() % 1_000_000}-${list().size + 1}"
        val task = Task(
            id = id,
            cron = cron.trim(),
            prompt = prompt.trim(),
            recurring = recurring,
            durable = durable,
            createdAt = System.currentTimeMillis(),
        )

        if (durable) {
            durableTasks += task
            saveDurable()
        } else {
            sessionTasks += task
        }
        return task
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val removed = durableTasks.removeAll { it.id == id }
        val removed2 = sessionTasks.removeAll { it.id == id }
        if (removed) saveDurable()
        return removed || removed2
    }

    /** 标记已触发（循环任务据此重算下次） */
    @Synchronized
    /**
     * 调度器心跳（audit-core #8：loadDurable/saveDurable 齐全但
     * nextRun/markFired **零调用方** —— 持久任务存盘重启后永不触发）。
     *
     * 由 AppGraph 的 appScope 每 30s 调一次：
     * 1. 重读 durable 文件（能感知 CronCreate/删除工具的新写入）
     * 2. nextRun 到点 → onFire 投递；**返回 true 才记账**
     *    （返回 false = 目标正忙、下个 tick 重试，不丢任务）
     * 3. recurring 记 lastFiredAt；一次性任务投递成功即从清单移除
     */
    fun schedulerTick(now: Long = System.currentTimeMillis(), onFire: (Task) -> Boolean) {
        loadDurable()
        val due = durableTasks.filter { t -> nextRun(t)?.let { it <= now } == true }
        for (t in due) {
            val ok = try { onFire(t) } catch (_: Throwable) { false }
            if (!ok) continue
            if (t.recurring) {
                markFired(t.id)
            } else {
                durableTasks.removeAll { it.id == t.id }
                saveDurable()
            }
        }
    }

    fun markFired(id: String) {
        val idx = durableTasks.indexOfFirst { it.id == id }
        if (idx >= 0) {
            durableTasks[idx] = durableTasks[idx].copy(lastFiredAt = System.currentTimeMillis())
            saveDurable()
            return
        }
        val idx2 = sessionTasks.indexOfFirst { it.id == id }
        if (idx2 >= 0) {
            sessionTasks[idx2] = sessionTasks[idx2].copy(lastFiredAt = System.currentTimeMillis())
        }
    }

    /** 下次触发时间（毫秒；找不到返回 null） */
    fun nextRun(task: Task): Long? {
        val expr = CronExpr.parse(task.cron) ?: return null
        val from = if (task.recurring) {
            maxOf(task.lastFiredAt, task.createdAt, System.currentTimeMillis() - 60_000)
        } else {
            task.createdAt
        }
        return expr.next(from)
    }

    /** cron → 人话 */
    fun human(cron: String): String {
        val parts = cron.trim().split(Regex("\\s+"))
        if (parts.size != 5) return cron
        val (min, hour, dom, month, dow) = parts
        return when {
            min == "*" && hour == "*" -> "每分钟"
            min.startsWith("*/") && hour == "*" -> "每 ${min.removePrefix("*/")} 分钟"
            hour.startsWith("*/") && dom == "*" && month == "*" && dow == "*" ->
                "每 ${hour.removePrefix("*/")} 小时"
            dom == "*" && month == "*" && dow == "*" ->
                "每天 $hour:${min.padStart(2, '0')}"
            dom == "*" && month == "*" && dow != "*" ->
                "每周${dowName(dow)} $hour:${min.padStart(2, '0')}"
            month == "*" && dow == "*" ->
                "每月 $dom 号 $hour:${min.padStart(2, '0')}"
            else -> cron
        }
    }

    private fun dowName(d: String): String = when (d) {
        "0", "7" -> "日"
        "1" -> "一"
        "2" -> "二"
        "3" -> "三"
        "4" -> "四"
        "5" -> "五"
        "6" -> "六"
        else -> d
    }
}

/**
 * cron 表达式解析与下次触发时间计算。
 *
 * 【实现方式】穷举搜索（逐分钟向后找），而不是数学推导。
 * 理由：cron 的「日/周」是**或**关系（`0 0 1 * 1` = 每月 1 号**或**每周一），
 * 数学推导要处理这个特例，容易写错。穷举最多搜 4 年（约 210 万次迭代），
 * 在现代手机上几十毫秒，且**绝不会算错**。
 */
class CronExpr(
    private val minutes: Set<Int>,
    private val hours: Set<Int>,
    private val daysOfMonth: Set<Int>,
    private val months: Set<Int>,
    private val daysOfWeek: Set<Int>,
    /** dom 与 dow 是否都是「限定值」（决定二者是「或」还是「与」关系） */
    private val domRestricted: Boolean,
    private val dowRestricted: Boolean,
) {

    companion object {
        /** 解析表达式；非法返回 null */
        fun parse(expr: String): CronExpr? {
            val parts = expr.trim().split(Regex("\\s+"))
            if (parts.size != 5) return null
            return try {
                val min = parseField(parts[0], 0, 59) ?: return null
                val hour = parseField(parts[1], 0, 23) ?: return null
                val dom = parseField(parts[2], 1, 31) ?: return null
                val month = parseField(parts[3], 1, 12) ?: return null
                val dow = parseField(parts[4], 0, 7)?.map { if (it == 7) 0 else it }?.toSet() ?: return null
                CronExpr(
                    minutes = min, hours = hour, daysOfMonth = dom,
                    months = month, daysOfWeek = dow,
                    domRestricted = parts[2].trim() != "*",
                    dowRestricted = parts[4].trim() != "*",
                )
            } catch (_: Throwable) {
                null
            }
        }

        /** 解析单个字段（支持 * / N / *\/N / a-b / a,b,c） */
        private fun parseField(field: String, min: Int, max: Int): Set<Int>? {
            val f = field.trim()
            if (f.isEmpty()) return null
            if (f == "*") return (min..max).toSet()

            val out = mutableSetOf<Int>()
            f.split(",").forEach { part ->
                val p = part.trim()
                when {
                    p.contains("/") -> {
                        val (rangePart, stepPart) = p.split("/", limit = 2)
                        val step = stepPart.toIntOrNull() ?: return null
                        if (step <= 0) return null
                        val (lo, hi) = when {
                            rangePart == "*" -> min to max
                            rangePart.contains("-") -> {
                                val (a, b) = rangePart.split("-", limit = 2)
                                (a.toIntOrNull() ?: return null) to (b.toIntOrNull() ?: return null)
                            }
                            else -> {
                                val v = rangePart.toIntOrNull() ?: return null
                                v to max
                            }
                        }
                        if (lo < min || hi > max || lo > hi) return null
                        var i = lo
                        while (i <= hi) {
                            out += i
                            i += step
                        }
                    }
                    p.contains("-") -> {
                        val (a, b) = p.split("-", limit = 2)
                        val lo = a.toIntOrNull() ?: return null
                        val hi = b.toIntOrNull() ?: return null
                        if (lo < min || hi > max || lo > hi) return null
                        out += (lo..hi)
                    }
                    else -> {
                        val v = p.toIntOrNull() ?: return null
                        if (v < min || v > max) return null
                        out += v
                    }
                }
            }
            return out.ifEmpty { null }
        }
    }

    /** 从 [fromMillis] 之后（不含当分钟）找下一次触发时间 */
    fun next(fromMillis: Long): Long? {
        val cal = Calendar.getInstance().apply {
            timeInMillis = fromMillis
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MINUTE, 1)   // 从下一分钟开始找
        }

        val limit = fromMillis + 4L * 365 * 24 * 3600 * 1000
        var guard = 0
        while (cal.timeInMillis <= limit && guard < 3_000_000) {
            guard++
            if (matches(cal)) return cal.timeInMillis
            cal.add(Calendar.MINUTE, 1)
        }
        return null
    }

    private fun matches(cal: Calendar): Boolean {
        if (cal.get(Calendar.MINUTE) !in minutes) return false
        if (cal.get(Calendar.HOUR_OF_DAY) !in hours) return false
        if ((cal.get(Calendar.MONTH) + 1) !in months) return false

        val dom = cal.get(Calendar.DAY_OF_MONTH)
        val dow = cal.get(Calendar.DAY_OF_WEEK) - 1   // Calendar: SUNDAY=1 → 转成 0=周日

        val domOk = dom in daysOfMonth
        val dowOk = dow in daysOfWeek

        // ⚠️ cron 的经典语义：dom 与 dow **都限定**时是「或」关系
        // （`0 0 1 * 1` = 每月 1 号**或**每周一）
        return when {
            domRestricted && dowRestricted -> domOk || dowOk
            domRestricted -> domOk
            dowRestricted -> dowOk
            else -> true
        }
    }
}

/**
 * Cron 工具组（3 个）。
 */
class CronTools(private val store: CronStore) {

    inner class CronCreateTool : Tool() {
        override val name = "CronCreate"
        override val description =
            "创建一个定时任务：到点自动执行一段 prompt。\n" +
                "cron 用标准 5 字段（分 时 日 月 周），如 \"0 9 * * *\" 每天 9 点、\"*/5 * * * *\" 每 5 分钟。\n" +
                "recurring=true 循环执行（触发后重算下次），false 则一次性（触发后自动删除）。\n" +
                "durable=true 写盘持久（重启后还在）；**只有用户明确说「每天/长期/一直」才用 durable**，" +
                "否则默认 session 级（退出即消失）。"
        override val isConcurrencySafe = true
        override val maxResultSizeChars = 1_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "cron" to ToolSchema.string("cron 表达式，5 字段（分 时 日 月 周）"),
            "prompt" to ToolSchema.string("到点要执行的任务描述"),
            "recurring" to ToolSchema.boolean("是否循环，默认 false（一次性）"),
            "durable" to ToolSchema.boolean("是否持久化，默认 false（session 级）"),
            required = listOf("cron", "prompt"),
        )

        override fun validateInput(input: JsonObject): String? {
            if (input.str("cron").isNullOrBlank()) return "cron is required"
            if (input.str("prompt").isNullOrBlank()) return "prompt is required"
            return null
        }

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            return try {
                val t = store.create(
                    cron = input.str("cron")!!,
                    prompt = input.str("prompt")!!,
                    recurring = input.bool("recurring") == true,
                    durable = input.bool("durable") == true,
                )
                val next = store.nextRun(t)
                val nextStr = next?.let {
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(it))
                } ?: "（无法计算）"

                ToolResult.ok(
                    "已创建定时任务 ${t.id}（${store.human(t.cron)}）。\n" +
                        "  持久化：${if (t.durable) "是（重启后还在）" else "否（session 级，退出即消失）"}\n" +
                        "  类型：${if (t.recurring) "循环（每次触发后重算下次）" else "一次性（触发后自动删除）"}\n" +
                        "  下次触发：$nextStr\n" +
                        "用 CronDelete 取消。",
                )
            } catch (e: Throwable) {
                ToolResult.invalidInput(e.message ?: "创建失败")
            }
        }
    }

    inner class CronListTool : Tool() {
        override val name = "CronList"
        override val description = "列出所有定时任务（含下次触发时间）。"
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override val maxResultSizeChars = 3_000

        override val inputSchema: JsonObject = ToolSchema.noArgsSchema()

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val tasks = store.list()
            if (tasks.isEmpty()) return ToolResult.ok("（暂无定时任务）")

            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            return ToolResult.ok(
                tasks.joinToString("\n") { t ->
                    val next = store.nextRun(t)?.let { fmt.format(java.util.Date(it)) } ?: "无"
                    val kind = if (t.recurring) "循环" else "一次"
                    val dur = if (t.durable) "持久" else "session"
                    "- ${t.id} [$kind|$dur] ${store.human(t.cron)} → 下次 $next\n  ${t.prompt.take(80)}"
                },
            )
        }
    }

    inner class CronDeleteTool : Tool() {
        override val name = "CronDelete"
        override val description = "删除一个定时任务。"
        override val isDestructive = true
        override val isConcurrencySafe = true
        override val maxResultSizeChars = 500

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "id" to ToolSchema.string("任务 ID（CronList 里看）"),
            required = listOf("id"),
        )

        override fun validateInput(input: JsonObject): String? =
            if (input.str("id").isNullOrBlank()) "id is required" else null

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val id = input.str("id")!!
            return if (store.delete(id)) {
                ToolResult.ok("已删除 $id")
            } else {
                ToolResult.notFound("找不到任务 $id")
            }
        }
    }
}
