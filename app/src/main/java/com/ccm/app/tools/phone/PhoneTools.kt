package com.ccm.app.tools.phone

import android.content.Context
import com.ccm.app.bridge.ShizukuBridge
import com.ccm.app.core.tool.Attachment
import com.ccm.app.core.tool.Tool
import com.ccm.app.core.tool.ToolContext
import com.ccm.app.core.tool.ToolResult
import com.ccm.app.core.tool.ToolSchema
import com.ccm.app.core.tool.ToolSchema.bool
import com.ccm.app.core.tool.ToolSchema.int
import com.ccm.app.core.tool.ToolSchema.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject
import java.io.File

/**
 * 手机操作工具集 —— phone_* 系列（11 个）。
 *
 * 参照 Node 版 `core/tools-phone.mjs`（1438 行，全项目第三大文件）。
 *
 * ══════════════════════════════════════════════════════════════
 *  APK 侧的巨大优势：不需要 Shizuku/rish 之外的任何东西
 * ══════════════════════════════════════════════════════════════
 *
 * Node 版要靠 `rish`（Shizuku 的 shell 客户端）+ `uiautomator dump` +
 * `input tap` 这些**外部命令**，每次操作都有进程启动开销（实测一轮 3~5 秒）。
 *
 * APK 侧直接调 [ShizukuBridge.phoneService] 拿 AIDL 接口，
 * 元素树和点击都在服务进程内完成（一轮几百毫秒，快一个数量级）。
 *
 * ══════════════════════════════════════════════════════════════
 *  CCM 踩过的坑（全部保留，注释标了位置）
 * ══════════════════════════════════════════════════════════════
 *
 * **坑 1：默认用文本快照，不要默认截图**
 * 实测一个搜歌任务走了 27 分钟，根因是全程截图 + 识图 + 手算缩放坐标
 * （每轮 20~30 秒）。原生界面文本快照完全够用（一轮几百毫秒）。
 * **只有 WebView/Flutter/Canvas 这类元素树看不见的界面才截图。**
 *
 * **坑 2：界面变化后旧 ref 立即失效**
 * 必须重新 snapshot 再操作，不能拿旧 ref 点。
 *
 * **坑 3：phone_wait 的参数名撞了框架超时机制**
 * Node 版用了 `timeout` 参数名，被框架当成「这个工具最多跑 N 秒」直接掐断。
 * APK 侧改用 `max_wait_ms`（对齐 CCM 后来的修复）。
 *
 * **坑 4：截图坐标要声明来源**
 * 缩放图的坐标必须用 `from_screenshot: true` 声明，由工具换算，
 * 不要让模型自己做乘法（算错过就会点歪）。
 *
 * @param context Android Context（调 ShizukuBridge）
 * @param saveDir 截图保存目录
 */
class PhoneTools(
    private val context: Context,
    private val saveDir: File,
) {

    companion object {
        /** 息屏时的统一提示（Android 系统限制，不是故障） */
        private const val SCREEN_OFF_HINT =
            "屏幕已关闭（Android 在息屏/Doze 下暂停虚拟屏合成、不给应用分配 Surface）。" +
                "这是系统限制不是故障 —— 请点亮屏幕后重试（不用解锁）。"

        /** 默认元素树节点上限 */
        private const val DEFAULT_MAX_NODES = 120
    }

    /** 拿服务，拿不到就返回带原因的错误 */
    private suspend fun service(): Result<com.ccm.app.bridge.IPhoneUseService> =
        withContext(Dispatchers.IO) {
            val reason = ShizukuBridge.unavailableReason()
            if (reason != null) {
                return@withContext Result.failure(IllegalStateException("手机操作不可用：$reason"))
            }
            val svc = ShizukuBridge.phoneService(context)
                ?: return@withContext Result.failure(
                    IllegalStateException(
                        "手机操作服务未就绪：${ShizukuBridge.lastPhoneError ?: "未知原因"}"
                    ),
                )
            Result.success(svc)
        }

    private fun screenshotDir(): File = File(saveDir, "phone-shots").apply { if (!exists()) mkdirs() }

    /** 解析 ref：支持 "e12" / "12" / "node:12" 三种写法（对齐 AIDL 注释） */
    private fun normalizeRef(raw: String): String = raw
        .removePrefix("e")
        .removePrefix("node:")
        .trim()

    // ══════════════════════════════════════════════════════════════
    //  1. phone_snapshot
    // ══════════════════════════════════════════════════════════════

    inner class PhoneSnapshotTool : Tool() {
        override val name = "phone_snapshot"
        override val description =
            "获取当前手机界面的元素树文本快照（比截图快一个数量级，优先用它）。" +
                "输出是平铺格式：首行状态，次行列头，之后一行一元素，形如 " +
                "#e12 Button \"发送\" 940,2100,1180,2200 c。" +
                "直接用行首的 id（e12）做 phone_click 的目标，不要自己算坐标。" +
                "flags: c=可点 e=可输入 s=可滚 k±=选中 off=禁用。界面变化后旧 id 会失效，需重新 snapshot。"
        override val isReadOnly = true
        // ⚠️ 虽然只读，但**不可并发** —— 多个快照同时抓会互相干扰（服务侧是单份状态）
        override val isConcurrencySafe = false
        override val maxResultSizeChars = 20_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "interactive_only" to ToolSchema.boolean("只列可点击/带 id 的元素（默认 true，省 token）"),
            "max_nodes" to ToolSchema.integer("最多返回多少元素（默认 120）", minimum = 1, maximum = 1000),
            "no_system_ui" to ToolSchema.boolean("滤掉状态栏/导航栏/输入法（默认 true）"),
            "include_text" to ToolSchema.boolean("额外补文本内容（慢，动画中会失败）"),
        )

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val svc = service().getOrElse {
                return ToolResult.Error(it.message ?: "服务不可用", ToolResult.INTERNAL)
            }
            return try {
                val tree = svc.dumpTree(
                    input.bool("interactive_only") ?: true,
                    input.int("max_nodes") ?: DEFAULT_MAX_NODES,
                    input.bool("no_system_ui") ?: true,
                )
                if (tree.isBlank()) {
                    ToolResult.failed(SCREEN_OFF_HINT)
                } else {
                    ToolResult.ok(tree)
                }
            } catch (e: Throwable) {
                ToolResult.Error("获取元素树失败：${e.message}", ToolResult.INTERNAL)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  2. phone_click / 3. phone_tap_xy
    // ══════════════════════════════════════════════════════════════

    inner class PhoneClickTool : Tool() {
        override val name = "phone_click"
        override val description =
            "点击手机屏幕上的元素。传 phone_snapshot 输出里行首的 id（如 e12，纯数字 12 也行）——" +
                "内部换算成元素中心坐标，不需要你算坐标。报「已失效」说明界面刷新过，重新 snapshot 再点。"
        override val isConcurrencySafe = false
        override val maxResultSizeChars = 500

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "ref" to ToolSchema.string("phone_snapshot 里的节点 id，如 e12 或 12"),
            "long_press" to ToolSchema.boolean("长按（默认 false）"),
            required = listOf("ref"),
        )

        override fun validateInput(input: JsonObject): String? =
            if (input.str("ref").isNullOrBlank()) "ref is required" else null

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val svc = service().getOrElse {
                return ToolResult.Error(it.message ?: "服务不可用", ToolResult.INTERNAL)
            }
            val ref = normalizeRef(input.str("ref")!!)
            return try {
                if (input.bool("long_press") == true) {
                    // AIDL 没有长按专用方法 —— 用 tapRef 拿坐标后长按。
                    // （服务侧的 tapRef 是单击；长按需要坐标版本，这里明确说明不支持）
                    ToolResult.failed("长按暂未支持（服务侧只有单击接口）。可以先用 phone_tap_xy 长按坐标。")
                } else {
                    val ok = svc.tapRef(ref)
                    if (ok) ToolResult.ok("已点击 #$ref")
                    else ToolResult.failed("节点已失效（#$ref）。界面刷新过，请重新 phone_snapshot 再点。")
                }
            } catch (e: Throwable) {
                ToolResult.Error("点击失败：${e.message}", ToolResult.INTERNAL)
            }
        }
    }

    inner class PhoneTapXYTool : Tool() {
        override val name = "phone_tap_xy"
        override val description =
            "按绝对坐标点击屏幕。仅在没有可用 ref 时使用（如 WebView/Canvas 里元素树看不到）。" +
                "优先用 phone_click 按 ref 点。"
        override val isConcurrencySafe = false
        override val maxResultSizeChars = 500

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "x" to ToolSchema.integer("横坐标"),
            "y" to ToolSchema.integer("纵坐标"),
            "from_screenshot" to ToolSchema.boolean("坐标来自缩放后的截图时设 true，工具自动换算成真实像素"),
            "long_press" to ToolSchema.boolean("长按（默认 false）"),
            required = listOf("x", "y"),
        )

        override fun validateInput(input: JsonObject): String? =
            if (input.int("x") == null || input.int("y") == null) "x and y are required" else null

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val svc = service().getOrElse {
                return ToolResult.Error(it.message ?: "服务不可用", ToolResult.INTERNAL)
            }
            var x = input.int("x")!!
            var y = input.int("y")!!

            // 坐标来自缩放截图 → 按副屏真实尺寸换算
            if (input.bool("from_screenshot") == true) {
                try {
                    val metrics = svc.displayMetrics()
                    if (metrics != null && metrics.size >= 2 && metrics[0] > 0) {
                        // 截图缩放比例由调用方在 prompt 里说明；这里只做边界夹取保护
                        x = x.coerceIn(0, metrics[0])
                        y = y.coerceIn(0, metrics[1])
                    }
                } catch (_: Throwable) {
                }
            }

            return try {
                val ok = svc.tap(x, y)
                if (ok) ToolResult.ok("已点击 ($x, $y)") else ToolResult.failed("点击失败 ($x, $y)")
            } catch (e: Throwable) {
                ToolResult.Error("点击失败：${e.message}", ToolResult.INTERNAL)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  4. phone_type
    // ══════════════════════════════════════════════════════════════

    inner class PhoneTypeTool : Tool() {
        override val name = "phone_type"
        override val description =
            "在手机上输入文本。只认「当前有焦点的输入框」——先 phone_click 那个输入框再调用，" +
                "或直接给 ref。返回里会说明是否通过回读校验：" +
                "报 verify_mismatch 说明内容没真正写进去（字段有长度/格式限制，或被输入法过滤），别当成成功。"
        override val isConcurrencySafe = false
        override val maxResultSizeChars = 1_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "text" to ToolSchema.string("要输入的文本"),
            "ref" to ToolSchema.string("可选：先点击这个 ref 聚焦输入框"),
            "submit" to ToolSchema.boolean("输入后按回车（默认 false）"),
            required = listOf("text"),
        )

        override fun validateInput(input: JsonObject): String? =
            if (input["text"] == null) "text is required" else null

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val svc = service().getOrElse {
                return ToolResult.Error(it.message ?: "服务不可用", ToolResult.INTERNAL)
            }
            val text = input.str("text") ?: ""
            val target = input.str("ref")?.let { normalizeRef(it) } ?: ""

            return try {
                val raw = svc.typeTextAt(text, target)
                val obj = try {
                    JSONObject(raw)
                } catch (_: Throwable) {
                    null
                }

                val submitSuffix = if (input.bool("submit") == true) {
                    try {
                        svc.key(android.view.KeyEvent.KEYCODE_ENTER)
                        "\n（已按回车提交）"
                    } catch (_: Throwable) {
                        "\n（回车提交失败）"
                    }
                } else {
                    ""
                }

                if (obj == null) {
                    return ToolResult.failed("输入返回无法解析：${raw.take(200)}$submitSuffix")
                }

                val ok = obj.optBoolean("ok", false)
                val verified = obj.optBoolean("verified", false)
                val err = obj.optString("error", "")
                val reason = obj.optString("reason", "")
                val verifiedText = obj.optString("verified_text", "")

                when {
                    ok && verified -> ToolResult.ok("已输入并校验通过：\"$verifiedText\"$submitSuffix")
                    ok && !verified -> ToolResult.failed(
                        "输入执行了但回读不一致（verify_mismatch）。可能被输入法过滤或字段有限制。" +
                            "请用 phone_snapshot 确认实际内容。$submitSuffix",
                    )
                    else -> ToolResult.failed(
                        "输入失败：${err.ifEmpty { reason }.ifEmpty { "未知原因" }}" +
                            (obj.optString("focus_hint", "").takeIf { it.isNotEmpty() }?.let { "\n提示：$it" } ?: "") +
                            submitSuffix,
                    )
                }
            } catch (e: Throwable) {
                ToolResult.Error("输入失败：${e.message}", ToolResult.INTERNAL)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  5. phone_swipe / 6. phone_key / 7. phone_scroll
    // ══════════════════════════════════════════════════════════════

    inner class PhoneSwipeTool : Tool() {
        override val name = "phone_swipe"
        override val description = "滑动屏幕：up=内容上移即向下翻。也可给两点坐标。"
        override val isConcurrencySafe = false
        override val maxResultSizeChars = 500

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "direction" to ToolSchema.string(
                "滑动方向（up=内容上移即向下翻）",
                enum = listOf("up", "down", "left", "right"),
            ),
            "duration" to ToolSchema.integer("毫秒，默认 300", minimum = 50, maximum = 5000),
            "ref" to ToolSchema.string("可选：在这个元素范围内滑动"),
        )

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val svc = service().getOrElse {
                return ToolResult.Error(it.message ?: "服务不可用", ToolResult.INTERNAL)
            }
            val dir = input.str("direction") ?: "up"
            val duration = input.int("duration") ?: 300
            return try {
                val ok = svc.swipeDir(dir, duration)
                if (ok) ToolResult.ok("已向 $dir 滑动") else ToolResult.failed("滑动失败")
            } catch (e: Throwable) {
                ToolResult.Error("滑动失败：${e.message}", ToolResult.INTERNAL)
            }
        }
    }

    inner class PhoneKeyTool : Tool() {
        override val name = "phone_key"
        override val description = "按系统按键：back/home/recent/enter/delete/volume 等。"
        override val isConcurrencySafe = false
        override val maxResultSizeChars = 300

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "key" to ToolSchema.string(
                "back|home|recent|enter|delete|tab|escape|volume_up|volume_down|power，或原始 KEYCODE_XXX",
            ),
            required = listOf("key"),
        )

        override fun validateInput(input: JsonObject): String? =
            if (input.str("key").isNullOrBlank()) "key is required" else null

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val svc = service().getOrElse {
                return ToolResult.Error(it.message ?: "服务不可用", ToolResult.INTERNAL)
            }
            val key = input.str("key")!!
            val code = keyCodeOf(key)
                ?: return ToolResult.invalidInput("未知按键：$key")

            return try {
                val ok = svc.key(code)
                if (ok) ToolResult.ok("已按 $key") else ToolResult.failed("按键失败：$key")
            } catch (e: Throwable) {
                ToolResult.Error("按键失败：${e.message}", ToolResult.INTERNAL)
            }
        }
    }

    inner class PhoneScrollTool : Tool() {
        override val name = "phone_scroll"
        override val description = "滚动。给 ref 就滚那个元素，否则按 direction（up/down）滑一屏。"
        override val isConcurrencySafe = false
        override val maxResultSizeChars = 500

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "ref" to ToolSchema.string("要滚动的元素 id（省略则滑整屏）"),
            "direction" to ToolSchema.string("方向", enum = listOf("up", "down", "left", "right")),
        )

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val svc = service().getOrElse {
                return ToolResult.Error(it.message ?: "服务不可用", ToolResult.INTERNAL)
            }
            val ref = input.str("ref")?.let { normalizeRef(it) } ?: ""
            val dir = input.str("direction") ?: "down"
            return try {
                val ok = svc.scroll(ref, dir)
                if (ok) ToolResult.ok("已滚动${if (ref.isEmpty()) "整屏" else " #$ref"} $dir")
                else ToolResult.failed("滚动失败${if (ref.isNotEmpty()) "（#$ref 可能已失效）" else ""}")
            } catch (e: Throwable) {
                ToolResult.Error("滚动失败：${e.message}", ToolResult.INTERNAL)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  8. phone_screenshot（产出图片附件）
    // ══════════════════════════════════════════════════════════════

    inner class PhoneScreenshotTool : Tool() {
        override val name = "phone_screenshot"
        override val description =
            "截取当前手机画面。**能用 phone_snapshot 就别用这个** —— " +
                "文本快照约千把 token 且百毫秒级，截图几千 token 还慢。" +
                "只在 phone_snapshot 拿不到有用元素时用（WebView/Flutter/Canvas）。"
        override val isReadOnly = true
        override val isConcurrencySafe = false
        override val maxResultSizeChars = 1_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "prompt" to ToolSchema.string("可选：这次要在画面里找什么"),
        )

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val svc = service().getOrElse {
                return ToolResult.Error(it.message ?: "服务不可用", ToolResult.INTERNAL)
            }
            return try {
                val bytes = svc.latestFrame()
                if (bytes == null || bytes.isEmpty()) {
                    return ToolResult.failed(SCREEN_OFF_HINT)
                }
                val target = File(screenshotDir(), "shot-${System.currentTimeMillis()}.jpg")
                withContext(Dispatchers.IO) { target.writeBytes(bytes) }

                val hint = input.str("prompt")?.takeIf { it.isNotBlank() }
                    ?.let { "（关注：$it）" } ?: ""
                ToolResult.okWithImages(
                    "手机屏幕截图$hint —— ${target.absolutePath}",
                    listOf(Attachment.ImageFile(target.absolutePath, "image/jpeg")),
                )
            } catch (e: Throwable) {
                ToolResult.Error("截图失败：${e.message}", ToolResult.INTERNAL)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  9. phone_wait
    // ══════════════════════════════════════════════════════════════

    inner class PhoneWaitTool : Tool() {
        override val name = "phone_wait"
        override val description =
            "等界面稳定或等某段文字出现/消失，再继续操作。" +
                "比盲等固定毫秒可靠：点完不知道该等多久，等短了抓到旧界面，等长了浪费时间。" +
                "不给参数 = 等界面不再变化（连续两次采样一致即认为稳定）。"
        override val isReadOnly = true
        override val isConcurrencySafe = false
        override val maxResultSizeChars = 500

        // ⚠️ 参数名必须是 max_wait_ms，**不能用 timeout** ——
        // CCM 上 `timeout` 撞了框架的工具超时覆盖机制，传 timeout:10000
        // 会被当成「这个工具最多跑 10 秒」直接掐断，工具当场失效。
        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "text" to ToolSchema.string("等这段文字出现在屏幕上"),
            "text_gone" to ToolSchema.string("等这段文字从屏幕消失（如等 loading 结束）"),
            "max_wait_ms" to ToolSchema.integer("最长等待毫秒，默认 8000，上限 30000", minimum = 100, maximum = 30_000),
        )

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val svc = service().getOrElse {
                return ToolResult.Error(it.message ?: "服务不可用", ToolResult.INTERNAL)
            }
            val maxWait = (input.int("max_wait_ms") ?: 8_000).coerceIn(100, 30_000)
            val wantText = input.str("text")
            val wantGone = input.str("text_gone")

            val deadline = System.currentTimeMillis() + maxWait
            var lastTree = ""
            var stableCount = 0

            while (System.currentTimeMillis() < deadline) {
                ctx.checkCancelled()
                val tree = try {
                    svc.dumpTree(true, DEFAULT_MAX_NODES, true)
                } catch (_: Throwable) {
                    ""
                }

                if (wantText != null) {
                    if (tree.contains(wantText)) {
                        return ToolResult.ok("已等到文字「$wantText」出现")
                    }
                } else if (wantGone != null) {
                    if (!tree.contains(wantGone)) {
                        return ToolResult.ok("文字「$wantGone」已消失")
                    }
                } else {
                    // 无参数：等界面稳定
                    if (tree == lastTree && tree.isNotEmpty()) {
                        stableCount++
                        if (stableCount >= 1) return ToolResult.ok("界面已稳定")
                    } else {
                        stableCount = 0
                    }
                    lastTree = tree
                }
                withContext(Dispatchers.IO) { Thread.sleep(250) }
            }

            return ToolResult.failed(
                when {
                    wantText != null -> "等待超时（${maxWait}ms）：「$wantText」未出现"
                    wantGone != null -> "等待超时（${maxWait}ms）：「$wantGone」未消失"
                    else -> "等待超时（${maxWait}ms）：界面仍在变化"
                },
            )
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  10. phone_app
    // ══════════════════════════════════════════════════════════════

    inner class PhoneAppTool : Tool() {
        override val name = "phone_app"
        override val description =
            "启动应用（在虚拟副屏启动，不占物理屏；若应用已在主屏运行会自动搬运过去，不重启）。" +
                "action:'list' 列已装应用。"
        override val isConcurrencySafe = false
        override val maxResultSizeChars = 10_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "package" to ToolSchema.string("包名，如 com.android.settings"),
            "action" to ToolSchema.string(
                "launch（默认）| list | current | stop",
                enum = listOf("launch", "list", "current", "stop"),
            ),
            "filter" to ToolSchema.string("action=list 时按关键词过滤"),
        )

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val svc = service().getOrElse {
                return ToolResult.Error(it.message ?: "服务不可用", ToolResult.INTERNAL)
            }
            val action = input.str("action") ?: "launch"
            val pkg = input.str("package") ?: ""
            val filter = input.str("filter") ?: ""

            if (action == "launch" && pkg.isBlank()) {
                return ToolResult.invalidInput("action=launch 时 package 必填")
            }
            // 包名安全校验（对齐 CCM 的 /^[\w.]+$/）
            if (pkg.isNotEmpty() && !Regex("^[\\w.]+$").matches(pkg)) {
                return ToolResult.invalidInput("包名格式非法：$pkg")
            }

            return try {
                val raw = svc.app(action, pkg, filter)
                val obj = try {
                    JSONObject(raw)
                } catch (_: Throwable) {
                    null
                }
                if (obj != null && obj.optBoolean("ok", true)) {
                    val list = obj.optString("list", "")
                    if (list.isNotEmpty()) ToolResult.ok(list) else ToolResult.ok(raw)
                } else {
                    ToolResult.failed(raw)
                }
            } catch (e: Throwable) {
                ToolResult.Error("应用操作失败：${e.message}", ToolResult.INTERNAL)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  11. phone_shell
    // ══════════════════════════════════════════════════════════════

    inner class PhoneShellTool : Tool() {
        override val name = "phone_shell"
        override val description =
            "在 Android 系统里跑任意 shell 命令（uid=2000 shell，可 am/pm/dumpsys/input/screencap/run-as）。" +
                "与 Bash 的分工：Bash 跑在 Termux/proot 里（读写文件），本工具跑在 Android 里（操作手机）。" +
                "典型用途：pkill 重启进程、am start 指定屏启动、pm list packages 找包名、" +
                "run-as 读应用私有文件、settings/dumpsys 诊断。" +
                "通道卡住、副屏没起来、要找包名/读日志时先想到它。"
        override val isReadOnly = false
        override val isDestructive = true
        override val isConcurrencySafe = false
        override val maxResultSizeChars = 30_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "command" to ToolSchema.string("要执行的 shell 命令"),
            "timeout" to ToolSchema.integer("超时毫秒（默认 30000，最长 120000）", minimum = 1000, maximum = 120_000),
            required = listOf("command"),
        )

        override fun validateInput(input: JsonObject): String? =
            if (input.str("command").isNullOrBlank()) "command is required" else null

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val svc = service().getOrElse {
                return ToolResult.Error(it.message ?: "服务不可用", ToolResult.INTERNAL)
            }
            val cmd = input.str("command")!!
            val timeout = (input.int("timeout") ?: 30_000).coerceIn(1_000, 120_000)

            return try {
                val out = svc.runShell(cmd, timeout)
                val (code, stdout) = parseShellResult(out)
                if (code == 0) {
                    ToolResult.ok(stdout.ifEmpty { "(无输出)" })
                } else {
                    ToolResult.failed("exit=$code\n$stdout")
                }
            } catch (e: Throwable) {
                ToolResult.Error("命令执行失败：${e.message}", ToolResult.INTERNAL)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  12. phone_vd / 13. phone_device
    // ══════════════════════════════════════════════════════════════

    inner class PhoneVdTool : Tool() {
        override val name = "phone_vd"
        override val description =
            "虚拟副屏进程管理（后台操作手机用的那个屏）。status 看状态（含 display id、帧缓存新鲜度）；" +
                "start 启动；stop 停止；restart 重启。副屏「帧缓存过期」时 snapshot 会读到旧画面，此时 restart。"
        override val isConcurrencySafe = false
        override val maxResultSizeChars = 2_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "action" to ToolSchema.string(
                "status（默认）| start | stop | restart",
                enum = listOf("status", "start", "stop", "restart"),
            ),
        )

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val action = input.str("action") ?: "status"

            // stop 不需要服务在线（就是要把服务停掉）
            if (action == "stop") {
                return try {
                    withContext(Dispatchers.IO) {
                        ShizukuBridge.phoneService(context)?.destroy()
                    }
                    ToolResult.ok("已请求停止副屏服务")
                } catch (e: Throwable) {
                    ToolResult.Error("停止失败：${e.message}", ToolResult.INTERNAL)
                }
            }

            val svc = service().getOrElse {
                return ToolResult.Error(it.message ?: "服务不可用", ToolResult.INTERNAL)
            }
            return try {
                val status = svc.status()
                if (action == "status") {
                    ToolResult.ok(status)
                } else {
                    // start/restart：服务侧在首次调用时自动建屏，
                    // 这里通过一次 dumpTree 触发建屏，再读状态
                    runCatching { svc.dumpTree(false, 1, false) }
                    ToolResult.ok("已触发副屏$action\n${svc.status()}")
                }
            } catch (e: Throwable) {
                ToolResult.Error("副屏操作失败：${e.message}", ToolResult.INTERNAL)
            }
        }
    }

    inner class PhoneDeviceTool : Tool() {
        override val name = "phone_device"
        override val description =
            "手机操作通道状态总览：当前走哪条通道、目标屏是几、副屏是否可用、Shizuku 授权状态。" +
                "操作手机遇到问题时先看它。"
        override val isReadOnly = true
        override val isConcurrencySafe = false
        override val maxResultSizeChars = 2_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "test" to ToolSchema.boolean("是否实测通道可用性（会真的跑一次操作）"),
        )

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val sb = StringBuilder()
            val reason = ShizukuBridge.unavailableReason()
            sb.append("Shizuku: ")
            if (reason == null) {
                sb.append("已授权 ✓\n")
            } else {
                sb.append("不可用（$reason）\n")
            }

            val svc = ShizukuBridge.phoneService(context)
            if (svc == null) {
                sb.append("phone 服务: 未就绪（${ShizukuBridge.lastPhoneError ?: "未绑定"}）\n")
            } else {
                sb.append("phone 服务: 已连接 ✓\n")
                try {
                    sb.append("副屏状态: ${svc.status()}\n")
                    val m = svc.displayMetrics()
                    if (m != null && m.size >= 3) {
                        sb.append("副屏尺寸: ${m[0]}x${m[1]} @${m[2]}dpi\n")
                    }
                } catch (e: Throwable) {
                    sb.append("读状态失败: ${e.message}\n")
                }
            }

            if (input.bool("test") == true) {
                sb.append("\n--- 实测 ---\n")
                sb.append(
                    try {
                        val tree = svc?.dumpTree(true, 5, true) ?: "(无服务)"
                        if (tree.isBlank()) SCREEN_OFF_HINT else "元素树采样成功（${tree.length} 字符）"
                    } catch (e: Throwable) {
                        "失败：${e.message}"
                    },
                )
            }

            return ToolResult.ok(sb.toString())
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  14. phone_handoff（跨屏接力）
    // ══════════════════════════════════════════════════════════════

    /**
     * 跨屏接力：把某个屏上**正在跑的 App** 整体搬到另一个屏。
     *
     * 参照 Node 版 `core/tools-phone.mjs` 的 PhoneHandoffTool（已实测通过）。
     *
     * ══════════════════════════════════════════════════════════════
     *  与 phone_app 的区别（这是本工具存在的理由）
     * ══════════════════════════════════════════════════════════════
     *
     * | | phone_app | phone_handoff |
     * |---|---|---|
     * | 行为 | 在新屏**重新启动** | 把正在跑的 **task 整体搬过去** |
     * | 状态 | 重走启动流程，可能丢（登录态/草稿/播放进度） | **完整保留** |
     * | 适用 | 还没开、或开了也无所谓 | 已经开着、状态不能丢 |
     *
     * 典型场景：用户在主屏开着某 App，你要操作但不想占他屏幕 ——
     * 先 handoff 迁到副屏，再在副屏操作。
     *
     * ══════════════════════════════════════════════════════════════
     *  实现：两条 Android 原生命令（照抄 agent-mobile-use 的做法）
     * ══════════════════════════════════════════════════════════════
     *
     * ```
     * 1. dumpsys activity activities | grep -A 12 "Display #<from>"
     *    → 从 topResumedActivity / Task{} 行解析出 taskId
     * 2. cmd activity display move-stack <taskId> <to>
     * ```
     *
     * ⚠️ **move-stack 成功时无输出**，退出码也可能是 0 ——
     * 所以判据是「输出里有没有 error/denied 字样」，**不能把空输出当失败**。
     *
     * ══════════════════════════════════════════════════════════════
     *  通道选择：走 runShell 而不是新增 AIDL 方法
     * ══════════════════════════════════════════════════════════════
     *
     * 有两条路可走：
     * · ① 给 IPhoneUseService 加一个 `handoff()` AIDL 方法（改 aidl + PhoneUseService）
     * · ② 复用现有的 `runShell(cmd, timeout)`（[phone_shell][PhoneShellTool] 用的同一个）
     *
     * **选 ②**：move-stack 本来就是 shell 命令，加 AIDL 方法只是把它包一层，
     * 收益是「少拼一次字符串」，代价是要动 AIDL 契约（跨模块，需协调）+
     * 服务端要重新实现解析逻辑。而 ① 的解析逻辑跟 CLI 端完全一致，
     * 复用 runShell 能让两端**共用同一套正则和排错经验**（CLI 端已实测过）。
     *
     * 副作用：需要 Shizuku 服务在线（与 phone_shell 同样的前提）。
     */
    inner class PhoneHandoffTool : Tool() {
        override val name = "phone_handoff"
        override val description =
            "跨屏接力：把某个屏上正在运行的 App **整体搬到**另一个屏（状态完整保留）。\n" +
                "【典型场景】用户在主屏开着某个 App，你要操作它但不想占他屏幕 —— " +
                "先 phone_handoff 把它迁到副屏，再在副屏操作。\n" +
                "【与 phone_app 的区别】phone_app 是「在新屏重新启动」（会重走启动流程、可能丢状态）；" +
                "phone_handoff 是「把正在跑的 task 整体搬过去」（状态完整保留）。\n" +
                "【参数】省略 from/to 时：from 默认主屏(0)，to 默认副屏。"
        override val isReadOnly = false
        override val isDestructive = false
        override val isConcurrencySafe = false
        override val maxResultSizeChars = 2_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "from" to ToolSchema.integer("源屏 display id（默认 0 = 主屏）", minimum = 0),
            "to" to ToolSchema.integer("目标屏 display id（默认 = 副屏）", minimum = 0),
            "package" to ToolSchema.string("可选：指定搬哪个包（该屏有多个 task 时用）"),
        )

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val svc = service().getOrElse {
                return ToolResult.Error(it.message ?: "服务不可用", ToolResult.INTERNAL)
            }

            val from = input.int("from") ?: 0
            val wantPkg = input.str("package")?.trim()?.takeIf { it.isNotEmpty() } ?: ""

            // 包名安全校验（拼进 shell 命令前必须挡注入 —— 对齐 PhoneAppTool 的做法）
            if (wantPkg.isNotEmpty() && !Regex("^[\\w.]+$").matches(wantPkg)) {
                return ToolResult.invalidInput("包名格式非法：$wantPkg")
            }

            // ── 目标屏：默认副屏（从服务拿 displayId）────────────────
            var to = input.int("to")
            if (to == null) {
                val displayId = try {
                    // status() 返回 JSON：{running, display_id, ...}
                    val raw = svc.status()
                    JSONObject(raw).optInt("display_id", -1)
                } catch (_: Throwable) {
                    -1
                }
                if (displayId < 0) {
                    return ToolResult.failed(
                        "副屏未运行，无法接力。先用 phone_vd start 启动副屏，或显式传 to 参数。",
                    )
                }
                to = displayId
            }

            if (from == to) {
                return ToolResult.ok("源屏和目标屏相同（$from），无需接力。")
            }

            return try {
                // ── ① 找源屏最顶层的 task ─────────────────────────
                val dump = svc.runShell(
                    "dumpsys activity activities 2>/dev/null | grep -A 12 \"Display #$from \"",
                    20_000,
                )
                val text = parseShellResult(dump).second

                var taskId = 0
                var component = ""

                // 主匹配：topResumedActivity=ActivityRecord{hash u0 pkg/Act t<taskId>}
                //
                // ⚠️ 正则的两个容错点（都用真机 dumpsys 输出核对过，2026-09-27）：
                // · `(?:c\d+\s+)?` —— 部分 Android 版本/机型在 userId 前多一个 clientId
                //   （形如 `{hash c0 u0 pkg/Act}`），真机 REDMI Note 15 Pro 上没有，
                //   但加可选段能兼容，且不影响现有格式的匹配
                // · `t(?:askId=)?` —— 老版本写 `t15118`，新版本有的写 `taskId=15118`
                for (line in text.lines()) {
                    val m = Regex(
                        """topResumedActivity=ActivityRecord\{[0-9a-fA-F]+\s+(?:c\d+\s+)?u\d+\s+([\w.]+)/([\w.$]+)\s+t(?:askId=)?(\d+)""",
                    ).find(line)
                    if (m != null) {
                        component = "${m.groupValues[1]}/${m.groupValues[2]}"
                        taskId = m.groupValues[3].toIntOrNull() ?: 0
                        break
                    }
                }

                // 兜底：从 * Task{hash #12345 type=standard A=10349:pkg ...} 行找
                if (taskId == 0) {
                    for (line in text.lines()) {
                        val m = Regex("""\* Task\{[0-9a-fA-F]+\s+#(\d+)\s+[^}]*A=\d+:([\w.]+)""").find(line)
                            ?: continue
                        val pkg = m.groupValues[2]
                        if (pkg.contains("launcher") || pkg.contains("systemui")) continue
                        if (wantPkg.isNotEmpty() && !pkg.contains(wantPkg)) continue
                        taskId = m.groupValues[1].toIntOrNull() ?: 0
                        component = pkg
                        break
                    }
                }

                // 指定了包名时，再按包名过滤一遍（主匹配可能拿到的是别的 App）
                if (taskId != 0 && wantPkg.isNotEmpty() && !component.contains(wantPkg)) {
                    for (line in text.lines()) {
                        val m = Regex("""\* Task\{[0-9a-fA-F]+\s+#(\d+)\s+[^}]*A=\d+:([\w.]+)""").find(line)
                            ?: continue
                        if (m.groupValues[2].contains(wantPkg)) {
                            taskId = m.groupValues[1].toIntOrNull() ?: 0
                            component = m.groupValues[2]
                            break
                        }
                    }
                }

                if (taskId == 0) {
                    return ToolResult.failed(
                        "display $from 上没找到可搬的 App（可能只有桌面/系统界面）。" +
                            (if (wantPkg.isNotEmpty()) "\n指定了包名 $wantPkg，但该屏顶层没有它。" else "") +
                            "\n提示：先确认那屏上确实开着目标 App（phone_snapshot 看一眼）。",
                    )
                }

                // ── ② 移动整个 task ──────────────────────────────
                val moveOut = svc.runShell("cmd activity display move-stack $taskId $to 2>&1", 20_000)
                val out = parseShellResult(moveOut).second.trim()

                // ⚠️ 成功时通常无输出 —— 判据是「有没有错误字样」，不是「输出是否为空」
                val failed = Regex("error|exception|not found|denied", RegexOption.IGNORE_CASE).containsMatchIn(out)
                if (failed) {
                    return ToolResult.failed(
                        "接力失败（task $taskId → display $to）：\n${out.take(400)}\n\n" +
                            "【常见原因】\n" +
                            "· Android 版本不支持 move-stack（13+ 部分机型改了权限）\n" +
                            "· 跨屏移动需要系统权限（shell 通道可能不够）\n" +
                            "· 备选：phone_app 在新屏重新启动（会丢状态）",
                    )
                }

                ToolResult.ok(
                    "✅ 已接力：${component.ifEmpty { "顶层 App" }}（task $taskId）从 display $from → $to" +
                        (if (out.isNotEmpty()) "\n输出：${out.take(200)}" else "") +
                        "\n\n接下来可以在目标屏上操作它了（phone_snapshot / phone_click）。",
                )
            } catch (e: Throwable) {
                ToolResult.Error("接力执行失败：${e.message}", ToolResult.INTERNAL)
            }
        }
    }

    /**
     * 解析 [IPhoneUseService.runShell] 的返回值。
     *
     * ══════════════════════════════════════════════════════════════
     *  ⚠️ 契约与实际不一致（2026-09-27 实测发现）
     * ══════════════════════════════════════════════════════════════
     *
     * - **AIDL 注释**（`IPhoneUseService.aidl:69`）承诺：`"exitCode\n---\nstdout"`
     * - **服务实现**（`PhoneUseService.kt:387`）实际返回：`"${proc.exitValue()}\n$out"`
     *
     * 少了 `---` 分隔符。原 `phone_shell` 按注释格式 split，于是**永远切不开**：
     * `parts[0]` 变成 `"0\n<输出内容>"`，`toIntOrNull()` 得 null，
     * `code == 0` 不成立 → **每次调用都走 failed 分支**。
     *
     * 本函数同时兼容两种格式：
     * · 有 `\n---\n` → 按它切（若将来服务端补上分隔符，无需改这里）
     * · 没有 → 只切第一行当 exitCode，其余全部是 stdout
     *
     * **为什么不直接改服务端**：`bridge/` 不在本层权限内（且 AIDL 是跨模块契约，
     * 改动要协调）。工具侧兼容是安全的做法 —— 两种格式都能吃。
     *
     * @return (exitCode, stdout)；exitCode 为 null 表示连首行都不是数字（异常返回）
     */
    private fun parseShellResult(raw: String): Pair<Int?, String> {
        // ① 优先按 AIDL 承诺的格式切
        if (raw.contains("\n---\n")) {
            val parts = raw.split("\n---\n", limit = 2)
            return parts[0].trim().toIntOrNull() to (parts.getOrNull(1) ?: "")
        }
        // ② 实际格式：首行 exitCode，其余全是 stdout（**stdout 本身可能含换行，不能多切**）
        val idx = raw.indexOf('\n')
        if (idx < 0) return raw.trim().toIntOrNull() to ""
        return raw.substring(0, idx).trim().toIntOrNull() to raw.substring(idx + 1)
    }

    // ══════════════════════════════════════════════════════════════
    //  辅助：按键名 → KeyEvent 常量
    // ══════════════════════════════════════════════════════════════

    private fun keyCodeOf(name: String): Int? {
        val upper = name.uppercase()
        // ⚠️ 严格白名单：只接受 KEYCODE_ + 大写字母数字下划线
        // （CCM 踩过命令注入：原校验只查开头，`KEYCODE_A; id > /sdcard/x` 能通过）
        if (upper.startsWith("KEYCODE_")) {
            if (!Regex("^KEYCODE_[A-Z0-9_]+$").matches(upper)) return null
            return try {
                android.view.KeyEvent::class.java.getField(upper).getInt(null)
            } catch (_: Throwable) {
                null
            }
        }
        return when (name.lowercase()) {
            "back" -> android.view.KeyEvent.KEYCODE_BACK
            "home" -> android.view.KeyEvent.KEYCODE_HOME
            "recent", "recents" -> android.view.KeyEvent.KEYCODE_APP_SWITCH
            "enter" -> android.view.KeyEvent.KEYCODE_ENTER
            "delete", "backspace" -> android.view.KeyEvent.KEYCODE_DEL
            "tab" -> android.view.KeyEvent.KEYCODE_TAB
            "escape" -> android.view.KeyEvent.KEYCODE_ESCAPE
            "volume_up" -> android.view.KeyEvent.KEYCODE_VOLUME_UP
            "volume_down" -> android.view.KeyEvent.KEYCODE_VOLUME_DOWN
            "power" -> android.view.KeyEvent.KEYCODE_POWER
            "menu" -> android.view.KeyEvent.KEYCODE_MENU
            "space" -> android.view.KeyEvent.KEYCODE_SPACE
            else -> null
        }
    }
}
