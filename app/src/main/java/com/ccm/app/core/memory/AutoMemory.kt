package com.ccm.app.core.memory

import com.ccm.app.core.api.ApiClient
import com.ccm.app.core.session.ContentBlock
import com.ccm.app.core.session.Message
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject
import java.io.File

/**
 * 自动记忆提取（automem）—— 每轮 run 结束后后台提炼结论写进 CLAUDE.md。
 *
 * 对齐 CLI 的 `core/auto-memory.mjs`（132 行）。那边对照的是官方
 * `services/extractMemories/extractMemories.ts`，三个核心机制原样搬过来：
 *
 * | 机制 | 做法 | 为什么 |
 * |---|---|---|
 * | **cursor 增量** | 按消息条数当游标，只处理上次提取之后的新消息 | 全量重扫会反复提取同一条结论 |
 * | **互斥** | 主 Agent 本轮自己写过 CLAUDE.md（Memory 工具）→ 跳过 | 免得刚写完又生成一条重复的 |
 * | **并发保护** | 同一时刻只跑一个提取；期间来的新消息留给下一轮 | 两个提取同时写文件会互相覆盖 |
 *
 * ## 其他保护（照搬 CLI）
 * - 消息净增量 < [MIN_DELTA_MESSAGES] 不跑 —— 打招呼/查状态不值得烧这一发 API
 * - 用**主 provider 的非流式小请求**，纯判断+提炼
 * - **失败静默**，绝不打扰主流程（自动记忆是锦上添花，不能因为它把对话搞崩）
 *
 * ## 与 CLI 的行为差异（诚实列出）
 * 1. **无 incognito 检查**：APK 的 `/incognito` 目前只是提示文案（无痕会话未落地），
 *    所以这里没有 `opts.incognito` 对应物。等无痕会话实现后要补。
 * 2. **maxTokens / temperature 用实例默认值**：CLI 传 `maxTokens:300, temperature:0.2`，
 *    APK 的 [ApiClient.chat] 用构造时配置（`maxOutputTokens` / `temperature`），
 *    这里没有按请求覆盖的能力。影响很小（提取器输出本来就短），不值得为此
 *    在 ApiClient 上开一个只为一个调用点服务的口子。
 * 3. **日志**：CLI 往 logger 打「已记入: xxx」，APK 走 [onLog] 回调（可空）。
 *
 * ## 为什么调用方必须 fire-and-forget（不要 await）
 * 提取要发一次完整 API 请求（几秒）。跑在 Agent 的 run 协程里会：
 * - 拖住 Done 事件，UI 转圈多几秒
 * - 用户 Ctrl+C 时被一起取消，提取半途而废
 *
 * 所以设计成「调用方起独立协程调用」，内部用 [running] 标志挡并发。
 *
 * @param stateFile 游标/开关状态落盘位置（对齐 CLI 的 `.claude-code-mobile/automem.json`）
 * @param memoryFile 目标记忆文件（APK 侧是 `files/CLAUDE.md`）
 * @param onLog 日志回调（null = 静默）
 */
class AutoMemory(
    private val stateFile: File,
    private val memoryFile: File,
    private val onLog: ((String) -> Unit)? = null,
) {

    /** 持久化状态。 */
    private data class State(
        val cursor: Int = 0,
        val enabled: Boolean = false,  // 【2026-10-06 问题19】默认关闭
        val runs: Int = 0,
    )

    /**
     * 并发闸门（对齐 CLI 的 `st.running`）。
     *
     * **必须是内存态而不是从文件读** —— 两个并发的提取在同一个进程里，
     * 「读文件 → 判断 → 写文件」有竞态窗口；进程内单例的布尔量才可靠。
     */
    @Volatile
    private var running: Boolean = false

    /**
     * 主 Agent 本轮自己写过记忆文件（Memory 工具触发）。
     *
     * 置位后本轮提取跳过并推进游标。与 CLI 的 `mainWroteMemoryThisRun` 同语义。
     */
    @Volatile
    private var mainWroteMemory: Boolean = false

    /** 主 Agent 写了 CLAUDE.md → 本轮跳过提取（Memory 工具调用处调）。 */
    fun markMainWroteMemory() {
        mainWroteMemory = true
    }

    /** 读状态：`(enabled, cursor, runs)`。供 `/automem` 查看。 */
    fun status(): Triple<Boolean, Int, Int> {
        val s = loadState()
        return Triple(s.enabled, s.cursor, s.runs)
    }

    /** 开关（`/automem on|off`）。 */
    fun setEnabled(v: Boolean) {
        saveState(loadState().copy(enabled = v))
    }

    /** 重置游标（`/clear` 清空对话后调，否则 cursor > history.size 会让增量卡死）。 */
    fun resetCursor() {
        saveState(loadState().copy(cursor = 0))
    }

    /**
     * 每轮 run 正常结束后调用一次（**由调用方起独立协程，不要 await 在 run 里**）。
     *
     * @param history 当前完整历史（调用方传 `agentLoop.getHistory()`）
     * @param api 复用主 API 客户端（同一个 provider / key 池 / 连接池）
     * @return 给用户看的一句话（null = 本轮没跑 / 没提取到，属正常）
     */
    suspend fun maybeExtract(history: List<Message>, api: ApiClient): String? {
        val st = loadState()
        if (!st.enabled) return null
        if (running) return null                        // 并发放门外

        if (mainWroteMemory) {                          // 主 Agent 自己写过了 → 不重复
            mainWroteMemory = false
            // 仍然推进 cursor，否则这段历史下轮又爆增量
            saveState(st.copy(cursor = history.size))
            return null
        }

        val cursor = minOf(st.cursor, history.size)
        if (history.size - cursor < MIN_DELTA_MESSAGES) return null
        val delta = history.subList(cursor, history.size)

        val material = messagesToText(delta.takeLast(LOOKBACK_CAP))
        if (material.isBlank()) return null

        running = true
        saveState(st.copy(cursor = history.size, runs = st.runs + 1))

        return try {
            val memHead = readMemHead()
            val userMsg = buildJsonObject {
                put("role", JsonPrimitive("user"))
                put(
                    "content",
                    JsonPrimitive(
                        "当前 CLAUDE.md 头部（判断不要重复记）：\n$memHead" +
                            "\n\n---\n\n对话增量：\n$material",
                    ),
                )
            }
            // 裸请求：不带工具表（提取器是独立判断任务，与主对话的工具无关）。
            // system 用 EXTRACT_PROMPT，与主 Agent 的系统提示词无关。
            val resp = api.chat(EXTRACT_PROMPT, listOf(userMsg), emptyList())
            val parsed = parseExtraction(resp.text)
            if (parsed != null) {
                appendMemory(parsed)
                onLog?.invoke("[automem] 已记入: ${parsed.take(60)}")
                "已自动记入记忆：${parsed.take(60)}"
            } else {
                null
            }
        } catch (e: Throwable) {
            // 失败静默 —— 自动记忆绝不能把主流程搞崩
            onLog?.invoke("[automem] 提取失败（静默）: ${e.message}")
            null
        } finally {
            running = false
        }
    }

    // ═════════════════════════ 内部 ═════════════════════════

    private fun loadState(): State = try {
        if (!stateFile.exists()) {
            State()
        } else {
            val o = JSONObject(stateFile.readText())
            State(
                cursor = o.optInt("cursor", 0),
                enabled = o.optBoolean("enabled", false),  // 【2026-10-06 问题19】默认关闭
                runs = o.optInt("runs", 0),
            )
        }
    } catch (_: Throwable) {
        State()
    }

    private fun saveState(s: State) {
        try {
            stateFile.parentFile?.mkdirs()
            stateFile.writeText(
                JSONObject()
                    .put("cursor", s.cursor)
                    .put("enabled", s.enabled)
                    .put("runs", s.runs)
                    .toString(2),
            )
        } catch (_: Throwable) {
            // 落盘失败不影响本轮
        }
    }

    /** 历史 → 纯文本素材（只取文本块，单条截 400 字符，整体取尾部 [TEXT_CAP]）。 */
    private fun messagesToText(messages: List<Message>): String {
        val sb = StringBuilder()
        for (m in messages) {
            val text = m.content.filterIsInstance<ContentBlock.Text>().joinToString(" ") { it.text }
            val who = if (m.role == Message.ROLE_USER) "用户" else "助手"
            sb.append(who).append(": ").append(text.take(400)).append('\n')
        }
        val s = sb.toString()
        return if (s.length > TEXT_CAP) s.substring(s.length - TEXT_CAP) else s
    }

    /** 读记忆文件头部（给提取器判断「这条是不是已经记过了」）。 */
    private fun readMemHead(): String = try {
        if (!memoryFile.exists()) "(空)" else memoryFile.readText().take(1500)
    } catch (_: Throwable) {
        "(读取失败)"
    }

    /**
     * 从模型回复里抠出 `{"remember":true,"text":"..."}`。
     *
     * 用**宽松解析**（第一个 `{` 到最后一个 `}`）：模型经常在 JSON 前后加一句
     * 「好的，我判断如下：」——直接 JSONObject 解析会失败。
     * 对齐 CLI 的 `text.match(/\{[\s\S]*\}/)`。
     */
    private fun parseExtraction(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val o = JSONObject(raw.substring(start, end + 1))
            if (!o.optBoolean("remember", false)) return null
            val t = o.optString("text", "").trim()
            if (t.isEmpty()) null else t
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 追加到记忆文件。
     *
     * 多行转成多条 bullet（对齐 CLI 的 `text.replace(/\n+/g, '\n- ')`）——
     * 否则第二行起会顶到最左，看起来不像一条记忆。
     */
    private fun appendMemory(text: String) {
        val cur = if (memoryFile.exists()) memoryFile.readText() else "# CLAUDE.md\n\n"
        val sep = if (cur.endsWith("\n")) "" else "\n"
        val body = "- " + text.replace(Regex("\n+"), "\n- ")
        memoryFile.parentFile?.mkdirs()
        memoryFile.writeText(cur + sep + body + "\n")
    }

    companion object {
        /** 净增消息少于此数不提取（一问一答就 4 条边界）。 */
        const val MIN_DELTA_MESSAGES = 4

        /** 最多回看的消息条数。 */
        const val LOOKBACK_CAP = 12

        /** 喂给提取模型的素材上限（字符）。 */
        const val TEXT_CAP = 3000

        /**
         * 提取提示词（逐字对齐 CLI `core/auto-memory.mjs` 的 EXTRACT_PROMPT）。
         *
         * **别自作主张改这段**：它同时定义了「值得记」和「不值得记」两侧边界。
         * 只写「记重要的」会让模型把报错细节、临时心态也记进去，记忆文件迅速膨胀。
         */
        const val EXTRACT_PROMPT: String =
            """你是记忆提取器。从下面这段对话增量中判断：有没有值得写进项目 CLAUDE.md 的长期记忆？

值得记：项目级约定、用户长期偏好、构建/运行命令、踩过的坑及教训、关键路径/配置位置。
不值得记：闲聊、一次性操作、报错细节、临时心态、具体的代码内容、已经写过的内容。

只输出 JSON（不要任何其他文本）：
{"remember": false}
或
{"remember": true, "text": "要追加的记忆（一两句话，中文）"}"""
    }
}
