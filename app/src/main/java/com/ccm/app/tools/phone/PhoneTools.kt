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
import kotlin.math.roundToInt
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

        /**
         * 最近一次【截图】注入时的缩放比例（真实尺寸 ÷ 图上尺寸）。
         *
         * APK 的图片缩放发生在 AgentLoop 注入层（不是工具内），所以比例只能由
         * 注入层回写到这里，供 phone_tap_xy 的 from_screenshot:true 换算 ——
         * 对齐 CLI 的 lastShotScale（tools-phone.mjs T:1040-1050）。
         * null = 还没有截图记录（先调 phone_screenshot）。
         */
        @Volatile
        var recentShotScale: Pair<Float, Float>? = null
    }

    /**
     * 拿服务，拿不到就返回带原因的错误。
     *
     * 【2026-10-06 加】拿服务时同步「本次会话的模式」给 Service ——
     * 它据此决定操作主屏（前台）还是副屏（后台）。
     *
     * @param skipGate true = 跳过模式闸门（phone_shell 专用）。对齐 CLI
     *   `tools-phone.mjs T:1466-1470` 的语义：shell 是**通用通道**（诊断、查包名、
     *   读日志），与「要不要操作手机界面」是两件事 —— 用户选 idle 只是说
     *   「别动我屏幕」，不代表「别执行诊断命令」；强制它反而会挡住合理的
     *   诊断需求（比如查副屏为什么没起来）。Shizuku 可用性检查照常保留。
     */
    private suspend fun service(skipGate: Boolean = false): Result<com.ccm.app.bridge.IPhoneUseService> =
        withContext(Dispatchers.IO) {
            // ① 模式闸门（2026-10-06）—— 放在最前：还没定模式就先问用户。
            //    放这里的理由：**一处覆盖全部 14 个调用点**，
            //    以后新增手机工具也不会漏（跟 agent 侧 service() 同思路）。
            //    phone_shell 走 skipGate=true 绕过（见上方注释，对齐 CLI）。
            if (!skipGate) {
                modeGate()?.let {
                    return@withContext Result.failure(IllegalStateException(it))
                }
            }
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
            // 把模式同步给 Service（失败不影响——Service 默认副屏）
            try {
                svc.setTargetDisplay(PhoneMode.targetDisplayArg(PhoneMode.sessionMode))
            } catch (_: Throwable) {}
            Result.success(svc)
        }

    /**
     * 模式闸门（2026-10-06 加，对齐 CLI 的 ensurePhoneMode）。
     *
     * 规则：
     *   · 本次会话已有生效值 → 放行
     *   · 偏好是 foreground/background → 直接用它，不弹
     *   · 偏好是 ask 或从没设过 → **需要弹选择**
     *   · idle → 明确拒绝（不是错误，是用户的选择）
     *
     * @return null = 放行；非 null = 给模型的拒绝说明
     */
    private fun modeGate(): String? {
        val session = PhoneMode.sessionMode
        if (session == PhoneMode.IDLE) {
            return "本次会话选择「不操作手机」（idle）—— 手机工具不会执行。\n" +
                "这是用户在会话开始时的选择。要操作手机，请用户用 /device mode 主屏|后台 切换，或新开一轮会话。"
        }
        if (session != null) return null   // 已定 → 放行

        val pref = PhoneMode.preference(context)
        when (pref) {
            PhoneMode.FOREGROUND, PhoneMode.BACKGROUND -> {
                PhoneMode.setSession(pref)   // 偏好即生效值，不弹
                return null
            }
            else -> {
                // 'ask' 或从没设过 → 需要 UI 弹选择
                val picked = com.ccm.app.AppGraph.phoneModePrompter?.invoke()
                if (picked == null) {
                    // 非交互环境（子 agent / 无 UI）→ idle，不动手机
                    PhoneMode.setSession(PhoneMode.IDLE)
                    return "当前环境无法弹出模式选择（子 Agent 或界面未就绪）—— " +
                        "本次会话按 idle 处理，手机工具不会执行。\n" +
                        "请在主对话里操作手机，或用 /device mode 主屏|后台 预设模式。"
                }
                PhoneMode.setSession(picked)
                // 选了前台/后台/每次都问 → 记住（idle 不记，那是一次性的）
                if (picked == PhoneMode.FOREGROUND || picked == PhoneMode.BACKGROUND) {
                    PhoneMode.setPreference(context, picked)
                }
                return if (picked == PhoneMode.IDLE) {
                    "用户选择「这次不操作手机」—— 手机工具不执行。"
                } else null
            }
        }
    }

    private fun screenshotDir(): File = File(saveDir, "phone-shots").apply { if (!exists()) mkdirs() }

    /** 解析 ref：支持 "e12" / "12" / "node:12" 三种写法（对齐 AIDL 注释） */
    private fun normalizeRef(raw: String): String = raw
        .removePrefix("e")
        .removePrefix("node:")
        .trim()

    // ══════════════════════════════════════════════════════════════
    //  Clawd 悬浮窗联动（2026-10-09）
    // ══════════════════════════════════════════════════════════════
    //
    // 手机操作时让 Clawd 吉祥物「在场」：点击时飘到落点探头，滑动时被拖着
    // 走，截图时举放大镜。用户看不到副屏上的操作，所以副屏模式下不传坐标
    //（原地播动作表示「我在干活」）。

    /**
     * 发一个带坐标的动作（仅前台/主屏模式有效）。
     *
     * @param x/y 主屏物理像素坐标
     */
    private fun emitClawdAt(type: com.ccm.app.core.clawd.ClawdAction.Type, x: Int, y: Int) {
        // 副屏操作（background）时坐标不在主屏坐标系里，飘过去没有意义
        if (PhoneMode.sessionMode != PhoneMode.FOREGROUND) {
            com.ccm.app.core.clawd.ClawdBus.emitAction(
                com.ccm.app.core.clawd.ClawdAction.here(type),
            )
            return
        }
        com.ccm.app.core.clawd.ClawdBus.emitAction(
            com.ccm.app.core.clawd.ClawdAction.at(type, x, y),
        )
    }

    /** 发一个原地动作（不知道坐标 / 副屏模式）。 */
    private fun emitClawdHere(type: com.ccm.app.core.clawd.ClawdAction.Type) {
        com.ccm.app.core.clawd.ClawdBus.emitAction(
            com.ccm.app.core.clawd.ClawdAction.here(type),
        )
    }

    /**
     * 发一个滑动动作（带方向分量，悬浮窗据此让螃蟹往反方向倾）。
     *
     * @param x/y 滑动起点（主屏物理像素）
     * @param dx/dy 滑动位移（终点减起点）
     */
    private fun emitClawdSwipe(x: Int, y: Int, dx: Int, dy: Int) {
        val action = if (PhoneMode.sessionMode == PhoneMode.FOREGROUND) {
            com.ccm.app.core.clawd.ClawdAction.swipe(dx, dy, x, y)
        } else {
            com.ccm.app.core.clawd.ClawdAction.swipe(dx, dy)
        }
        com.ccm.app.core.clawd.ClawdBus.emitAction(action)
    }

    // ══════════════════════════════════════════════════════════════
    //  1. phone_snapshot
    // ══════════════════════════════════════════════════════════════

    inner class PhoneSnapshotTool : Tool() {
        override val name = "phone_snapshot"
        override val description =
            "获取当前手机界面的元素树文本快照（比截图快一个数量级，优先用它）。" +
                "输出是平铺格式：首行状态，次行列头，之后一行一元素，形如 " +
                "#e12 Button \"发送\" 940,2100,1180,2200 c。" +
                "用行首 id（e12）做 phone_click / phone_type 的目标。" +
                "flags 含义：c=可点 e=可输入 s=可滚 k±=选中 off=禁用 focus=聚焦。" +
                "界面变化后旧 id 会失效，需重新 snapshot。"
        override val isReadOnly = true
        // ⚠️ 虽然只读，但**不可并发** —— 多个快照同时抓会互相干扰（服务侧是单份状态）
        override val isConcurrencySafe = false
        override val maxResultSizeChars = 20_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "interactive_only" to ToolSchema.boolean("只列可点击/带 id 的元素（默认 true，省 token）"),
            "max_nodes" to ToolSchema.integer("最多返回多少元素（默认 120）", minimum = 1, maximum = 1000),
            "no_system_ui" to ToolSchema.boolean("滤掉状态栏/导航栏/输入法（默认 true）"),
        )

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val svc = service().getOrElse {
                return ToolResult.Error(it.message ?: "服务不可用", ToolResult.INTERNAL)
            }
            // Clawd 联动：读元素树 → 左右张望（「我在看界面」）
            emitClawdHere(com.ccm.app.core.clawd.ClawdAction.Type.SNAPSHOT)
            return try {
                val tree = svc.dumpTree(
                    input.bool("interactive_only") ?: true,
                    input.int("max_nodes") ?: DEFAULT_MAX_NODES,
                    input.bool("no_system_ui") ?: true,
                )
                if (tree.isBlank()) {
                    // 空树不一定是息屏（无窗口 / WebView/Flutter 时元素树也是空的），
                    // 一刀切报「息屏」会让模型让用户「点亮屏幕」而不是改用截图。
                    ToolResult.failed(
                        "未解析到可见元素。可能息屏（点亮屏幕重试），" +
                            "也可能是 WebView/Flutter 界面 —— 改用 phone_screenshot 看画面。",
                    )
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
                    // 【2026-10-06 修】原来报「长按暂未支持」并让用户改用
                    // phone_tap_xy —— 但那条路当时也不支持长按（静默变单击），
                    // 两头都走不通。现在 AIDL 有 longPress + tapRefAt，
                    // 按 ref 取坐标后真长按。
                    val xy = svc.tapRefAt(ref)
                    if (xy == null || xy.size < 2) {
                        ToolResult.failed("节点已失效（#$ref）。界面刷新过，请重新 phone_snapshot 再试。")
                    } else {
                        // Clawd 联动：长按也走 TAP 动作（飘过去探头看）
                        emitClawdAt(com.ccm.app.core.clawd.ClawdAction.Type.TAP, xy[0], xy[1])
                        val ok = svc.longPress(xy[0], xy[1], 600)
                        if (ok) {
                            ToolResult.ok(
                                "已长按 #$ref (${xy[0]}, ${xy[1]})\n" +
                                    "界面可能已变化，需要继续操作请重新 phone_snapshot",
                            )
                        } else ToolResult.failed("长按失败 #$ref")
                    }
                } else {
                    // 先取中心坐标再 tap（AIDL 不回传类名，给不了 CLI T:760 的
                    // 「元素名 @ 坐标」全格式 —— 坐标 + 下一步句是能达到的最大信息量）。
                    val xy = svc.tapRefAt(ref)
                    if (xy == null || xy.size < 2) {
                        ToolResult.failed("节点已失效（#$ref）。界面刷新过，请重新 phone_snapshot 再点。")
                    } else {
                        // Clawd 联动：飘到点击处探头看（用户要的「点击时来到点击处摆动作」）
                        emitClawdAt(com.ccm.app.core.clawd.ClawdAction.Type.TAP, xy[0], xy[1])
                        val ok = svc.tap(xy[0], xy[1])
                        if (ok) {
                            ToolResult.ok(
                                "已点击 #$ref (${xy[0]}, ${xy[1]})\n" +
                                    "界面可能已变化，需要继续操作请重新 phone_snapshot",
                            )
                        } else ToolResult.failed("点击失败 #$ref（坐标指令未生效）")
                    }
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
            var note = ""

            // 按截图坐标点击：查最近一次截图的缩放比例自动换算（对齐 CLI T:794-806）。
            // 不加这个的话模型每轮都要手算「缩放图坐标 × 比例」，实测十几轮里
            // 每轮一次乘法，又慢又容易算错。比例由 AgentLoop 注入缩放图时回写
            // （PhoneTools.recentShotScale）—— APK 的缩放发生在注入层而不是工具内。
            if (input.bool("from_screenshot") == true) {
                val scale = recentShotScale
                    ?: return ToolResult.failed(
                        "还没有截图记录，无法换算。先调 phone_screenshot，" +
                            "或直接传真实像素坐标（不设 from_screenshot）",
                    )
                val ox = x
                val oy = y
                x = (x * scale.first).roundToInt()
                y = (y * scale.second).roundToInt()
                note = "（截图坐标 $ox,$oy → 真实 $x,$y）"
                // 边界夹取：换算后可能溢出屏幕 1~2px（四舍五入/缩放误差）
                try {
                    val metrics = svc.displayMetrics()
                    if (metrics != null && metrics.size >= 2 && metrics[0] > 0) {
                        x = x.coerceIn(0, metrics[0])
                        y = y.coerceIn(0, metrics[1])
                    }
                } catch (_: Throwable) {
                }
            }

            // 【2026-10-06 修】原来完全不读 long_press 参数 —— 声明了却不生效，
            // 模型以为长按成功、实际只是单击（静默失败，比报错更难查）。
            val longPress = input.bool("long_press") == true
            val what = if (longPress) "长按" else "点击"
            // Clawd 联动：飘到坐标处探头看
            emitClawdAt(com.ccm.app.core.clawd.ClawdAction.Type.TAP, x, y)
            return try {
                val ok = if (longPress) svc.longPress(x, y, 600) else svc.tap(x, y)
                if (ok) {
                    // 对齐 CLI T:815-817：坐标 + 换算回显 + 下一步引导
                    ToolResult.ok(
                        "已${what}坐标 $x,$y$note\n" +
                            "界面可能已变化，需要继续操作请重新 phone_snapshot",
                    )
                } else {
                    ToolResult.failed("${what}失败 ($x, $y)")
                }
            } catch (e: Throwable) {
                ToolResult.Error("${what}失败：${e.message}", ToolResult.INTERNAL)
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

            // Clawd 联动：输入文字 → 打字动作
            emitClawdHere(com.ccm.app.core.clawd.ClawdAction.Type.TYPE_TEXT)
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
            "ref" to ToolSchema.string("可选：以这个元素的中心为滑动起点（在元素范围内滑）"),
            // 【2026-10-06 加】坐标模式 —— 原来 description 承诺「可给两点坐标」
            // 但 schema 里根本没这几个参数（AIDL 早有 swipe 坐标版）。
            "x1" to ToolSchema.integer("起点 x（给了坐标就忽略 direction/ref）"),
            "y1" to ToolSchema.integer("起点 y"),
            "x2" to ToolSchema.integer("终点 x"),
            "y2" to ToolSchema.integer("终点 y"),
        )

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val svc = service().getOrElse {
                return ToolResult.Error(it.message ?: "服务不可用", ToolResult.INTERNAL)
            }
            val duration = input.int("duration") ?: 300

            // ① 坐标模式（优先级最高）—— 四个坐标都给才生效
            val x1 = input.int("x1"); val y1 = input.int("y1")
            val x2 = input.int("x2"); val y2 = input.int("y2")
            if (x1 != null && y1 != null && x2 != null && y2 != null) {
                return try {
                    // Clawd 联动：从起点飘到终点方向（拖拽动作）
                    emitClawdSwipe(x1, y1, x2 - x1, y2 - y1)
                    val ok = svc.swipe(x1, y1, x2, y2, duration)
                    if (ok) {
                        ToolResult.ok(
                            "已从 ($x1, $y1) 滑到 ($x2, $y2)\n" +
                                "界面可能已变化，需要继续操作请重新 phone_snapshot",
                        )
                    } else ToolResult.failed("滑动失败")
                } catch (e: Throwable) {
                    ToolResult.Error("滑动失败：${e.message}", ToolResult.INTERNAL)
                }
            }

            // ② ref 模式：以元素中心为起点，按方向滑一小段（元素高度的 1/4）
            //    【2026-10-06 修】原来 ref 声明了但从不读取 —— 模型以为
            //    「只滚某个列表」，实际整屏滑动（嵌套滚动时会滚错容器）。
            val ref = input.str("ref")
            val dir = input.str("direction") ?: "up"
            if (!ref.isNullOrBlank()) {
                val xy = svc.tapRefAt(normalizeRef(ref))
                if (xy == null || xy.size < 2) {
                    return ToolResult.failed(
                        "节点已失效（#$ref）—— 重新 phone_snapshot 再试（ref 模式需要节点坐标）",
                    )
                }
                val cx = xy[0]; val cy = xy[1]
                val d = 200   // 元素内滑动距离（不按元素高度算 —— 小元素会滑不动）
                val (fx, fy, tx, ty) = when (dir.lowercase()) {
                    "up" -> listOf(cx, cy + d, cx, cy - d)
                    "down" -> listOf(cx, cy - d, cx, cy + d)
                    "left" -> listOf(cx + d, cy, cx - d, cy)
                    "right" -> listOf(cx - d, cy, cx + d, cy)
                    else -> return ToolResult.failed("direction 必须是 up/down/left/right")
                }
                return try {
                    // Clawd 联动：滑到起点处拖拽
                    emitClawdSwipe(fx, fy, tx - fx, ty - fy)
                    val ok = svc.swipe(fx, fy, tx, ty, duration)
                    if (ok) {
                        ToolResult.ok(
                            "已在 #$ref 上向 $dir 滑动\n" +
                                "界面可能已变化，需要继续操作请重新 phone_snapshot",
                        )
                    } else ToolResult.failed("滑动失败")
                } catch (e: Throwable) {
                    ToolResult.Error("滑动失败：${e.message}", ToolResult.INTERNAL)
                }
            }

            // ③ 整屏模式（默认）
            return try {
                val ok = svc.swipeDir(dir, duration)
                if (ok) {
                    ToolResult.ok(
                        "已向 $dir 滑动\n" +
                            "界面可能已变化，需要继续操作请重新 phone_snapshot",
                    )
                } else ToolResult.failed("滑动失败")
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
                ?: return ToolResult.invalidInput(
                    "未知按键 \"$key\"。可用: " +
                        "back/home/recent/enter/delete/tab/escape/volume_up/volume_down/power 或 KEYCODE_XXX",
                )

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
            // Clawd 联动：截图 → 举放大镜观察（最接近「看」的素材动作；
            // 素材库没有相机动作，见 ClawdAction 的说明）
            emitClawdHere(com.ccm.app.core.clawd.ClawdAction.Type.SCREENSHOT)
            return try {
                // 帧缓存可能还没刷新（刚触发建屏/首帧未到）——空帧重试 3 次。
                // 失败文案要带「已重试」字样（对齐 CLI T:1029），让模型知道已尽力过。
                var bytes: ByteArray? = null
                for (attempt in 1..3) {
                    bytes = svc.latestFrame()
                    if (bytes != null && bytes.isNotEmpty()) break
                    if (attempt < 3) withContext(Dispatchers.IO) { Thread.sleep(300) }
                }
                if (bytes == null || bytes.isEmpty()) {
                    return ToolResult.failed(SCREEN_OFF_HINT)
                }
                val frame = bytes
                val target = File(screenshotDir(), "shot-${System.currentTimeMillis()}.jpg")
                withContext(Dispatchers.IO) { target.writeBytes(frame) }

                val hint = input.str("prompt")?.takeIf { it.isNotBlank() }
                    ?.let { "（关注：$it）" } ?: ""
                ToolResult.okWithImages(
                    "手机屏幕截图$hint —— ${target.absolutePath}",
                    listOf(Attachment.ImageFile(target.absolutePath, "image/jpeg")),
                )
            } catch (e: Throwable) {
                ToolResult.Error("截屏失败（已重试）：${e.message}", ToolResult.INTERNAL)
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
            var rounds = 0

            while (System.currentTimeMillis() < deadline) {
                ctx.checkCancelled()
                rounds++
                val tree = try {
                    svc.dumpTree(true, DEFAULT_MAX_NODES, true)
                } catch (_: Throwable) {
                    ""
                }

                if (wantText != null) {
                    if (tree.contains(wantText)) {
                        return ToolResult.ok("\"$wantText\" 已出现（第 $rounds 次采样）")
                    }
                } else if (wantGone != null) {
                    if (!tree.contains(wantGone)) {
                        return ToolResult.ok("\"$wantGone\" 已消失（第 $rounds 次采样）")
                    }
                } else {
                    // 无参数：等界面稳定
                    if (tree == lastTree && tree.isNotEmpty()) {
                        stableCount++
                        if (stableCount >= 1) {
                            // 首行形如 "# display=8 1080x2400 pkg=com.x count=12"
                            val count = Regex("""count=(\d+)""").find(tree)?.groupValues?.get(1) ?: "?"
                            val pkg = Regex("""pkg=([\w.]+)""").find(tree)?.groupValues?.get(1) ?: "未知"
                            return ToolResult.ok(
                                "界面已稳定（$pkg，$count 个元素，第 $rounds 次采样）\n" +
                                    "可以 phone_snapshot 取最新元素树",
                            )
                        }
                    } else {
                        stableCount = 0
                    }
                    lastTree = tree
                }
                withContext(Dispatchers.IO) { Thread.sleep(250) }
            }

            // 对齐 CLI T:1156-1157：说清等的是什么条件 + 点破「可能永远不会达成」，
            // 免得模型超时后在「重试」和「放弃」之间反复试。
            return ToolResult.failed(
                run {
                    val what = wantText?.let { "等 \"$it\" 出现" }
                        ?: wantGone?.let { "等 \"$it\" 消失" }
                        ?: "等界面稳定"
                    "超时未满足条件（$what，采样 $rounds 次）。" +
                        "界面可能仍在变化，或条件本身不会达成"
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
            "启动/切换应用（在虚拟副屏启动，不占物理屏；若应用已在主屏运行会自动搬运过去，不重启），" +
                "或列已安装应用。" +
                "list 加 labels:true 可显示中文名（只显示缓存里已有的，不现场扫描）。" +
                "要看某个应用的中文名用 action:label + package（读单个很快，读完进缓存）。"
        override val isConcurrencySafe = false
        override val maxResultSizeChars = 10_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "package" to ToolSchema.string("包名，如 com.android.settings"),
            "action" to ToolSchema.string(
                "默认 launch；label=读单个应用的中文名",
                enum = listOf("launch", "list", "current", "stop", "label"),
            ),
            "filter" to ToolSchema.string("list 时按关键词过滤（包名或已缓存的中文名）"),
            "labels" to ToolSchema.boolean("list 时显示中文名（默认 false，只显示缓存里已有的，不现场扫描）"),
        )

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val svc = service().getOrElse {
                return ToolResult.Error(it.message ?: "服务不可用", ToolResult.INTERNAL)
            }
            // 对齐 CLI T:1295：没给 action 时，给了 package → launch，没给 → current
            val action = input.str("action")
                ?: if (input.str("package").isNullOrBlank()) "current" else "launch"
            val pkg = input.str("package") ?: ""
            val filter = input.str("filter") ?: ""
            val wantLabels = input.bool("labels") == true

            if (action in setOf("launch", "stop", "label") && pkg.isBlank()) {
                return ToolResult.invalidInput("$action 需要 package 参数")
            }
            // 包名安全校验（对齐 CLI 的 /^[\w.]+$/）
            if (pkg.isNotEmpty() && !Regex("^[\\w.]+$").matches(pkg)) {
                return ToolResult.invalidInput("包名格式不合法：$pkg")
            }

            return try {
                // Clawd 联动：启动应用 → 施法动作（「变出一个 App」的语义最贴切）
                if (action == "launch") {
                    emitClawdHere(com.ccm.app.core.clawd.ClawdAction.Type.LAUNCH_APP)
                }
                // labels 只影响 list 的展示（读缓存），AIDL 签名固定三个 String ——
                // 所以把 labels 标志编进 action 传给服务侧（约定值 list_labels，同文件内）。
                val serviceAction = if (action == "list" && wantLabels) "list_labels" else action
                val raw = svc.app(serviceAction, pkg, filter)
                val obj = try { JSONObject(raw) } catch (_: Throwable) { null }
                if (obj == null) return ToolResult.failed(raw.take(1_000))
                if (!obj.optBoolean("ok", true)) {
                    val err = obj.optString("error").ifEmpty { raw.take(500) }
                    // label 读不到：CLI T:1367 是正常回执不是报错 ——
                    // 模型据此知道该换包名/放弃，而不是当成工具故障重试。
                    if (action == "label") return ToolResult.ok("$pkg  $err")
                    return ToolResult.failed(err)
                }
                when (action) {
                    "list" -> formatAppList(obj, wantLabels, filter)
                    "label" -> {
                        val label = obj.optString("label")
                        val cached = obj.optBoolean("cached", false)
                        ToolResult.ok("$pkg  $label" + if (cached) "（缓存）" else "")
                    }
                    "stop" -> ToolResult.ok(obj.optString("message", "已停止 $pkg"))
                    "launch" -> ToolResult.ok(
                        obj.optString("message", "已启动 $pkg") + "\n用 phone_snapshot 查看当前界面",
                    )
                    "current" -> {
                        val p = obj.optString("package")
                        if (p.isNotEmpty()) ToolResult.ok(p) else ToolResult.failed("未获取到前台应用")
                    }
                    else -> ToolResult.ok(raw)
                }
            } catch (e: Throwable) {
                ToolResult.Error("应用操作失败：${e.message}", ToolResult.INTERNAL)
            }
        }

        /**
         * list 的行式输出 —— 对齐 CLI T:1335-1355 的格式与提示文案。
         *
         * 紧凑 JSON（原来 K:692 读的 `list` 字段还跟服务侧的 `apps` 对不上）
         * 对模型不友好：截断了不说、第三方/系统不分、中文名挂哪不知道。
         */
        private fun formatAppList(obj: JSONObject, wantLabels: Boolean, kw: String): ToolResult {
            val apps = obj.optJSONArray("apps") ?: return ToolResult.failed("list 返回缺少 apps 字段")
            val total = apps.length()
            val labels = if (wantLabels) obj.optJSONObject("labels") else null
            val tag = if (kw.isEmpty()) "第三方" else "全部（含系统）"
            val show = (0 until minOf(80, total)).map { apps.getString(it) }
            val lines = if (wantLabels) {
                show.map { p ->
                    val l = labels?.optString(p) ?: ""
                    if (l.isNotEmpty()) "$p  $l" else p
                }
            } else show

            val sb = StringBuilder()
            sb.append("已安装应用（$tag）$total 个")
            if (kw.isNotEmpty()) sb.append("（含 \"$kw\"）")
            sb.append(":\n")
            sb.append(lines.joinToString("\n"))
            if (wantLabels) {
                var known = 0
                for (i in 0 until total) {
                    val l = labels?.optString(apps.getString(i)) ?: ""
                    if (l.isNotEmpty()) known++
                }
                if (known == 0) {
                    sb.append("\n（中文名缓存为空。要看某个应用名：phone_app label <包名>）")
                } else if (known < total) {
                    sb.append("\n（中文名只显示了缓存里已有的 $known 个；" +
                        "其余用 phone_app label <包名> 按需读）")
                }
            }
            if (kw.isEmpty() && total >= 80) {
                sb.append("\n（只显示前 80 个，加 filter 关键词可搜系统应用）")
            }
            return ToolResult.ok(sb.toString())
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
            // 【不走模式闸门（skipGate=true）】对齐 CLI T:1466-1470：
            // 模式闸门是给「操作手机界面」用的 —— idle 模式下不该点击/输入；
            // 但 shell 是**通用通道**（诊断、查包名、读日志），与「要不要操作手机界面」
            // 是两件事 —— 用户选了 idle 只是说「别动我屏幕」，不代表「别执行诊断命令」。
            // 强制它反而会挡住合理的诊断需求（比如查副屏为什么没起来）。
            // Shizuku 可用性检查照常走（service() 内部 ②③ 两步）。
            val svc = service(skipGate = true).getOrElse {
                return ToolResult.Error(it.message ?: "服务不可用", ToolResult.INTERNAL)
            }
            val cmd = input.str("command")!!
            val timeout = (input.int("timeout") ?: 30_000).coerceIn(1_000, 120_000)

            return try {
                val out = svc.runShell(cmd, timeout)
                val (code, stdout) = parseShellResult(out)
                if (code == 0) {
                    // 空输出也要给明确回执 + 命令回显（CLI T:1478），
                    // 否则模型分不清「命令没输出」和「工具坏了」。
                    // 服务侧 runShell 用 redirectErrorStream 合并了 stderr，
                    // 没有独立 [stderr] 可分流 —— 至少把命令回显出来。
                    if (stdout.isEmpty()) ToolResult.ok("（命令已执行，无输出）\n\$ $cmd")
                    else ToolResult.ok(stdout)
                } else {
                    ToolResult.failed("exit=$code\n$stdout\n命令：$cmd")
                }
            } catch (e: Throwable) {
                ToolResult.Error("执行失败：${e.message}\n命令：$cmd", ToolResult.INTERNAL)
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
                    // 对齐 CLI T:1530 完成时态
                    ToolResult.ok("副屏已停止")
                } catch (e: Throwable) {
                    ToolResult.Error("停止失败：${e.message}", ToolResult.INTERNAL)
                }
            }

            val svc = service().getOrElse {
                return ToolResult.Error(it.message ?: "服务不可用", ToolResult.INTERNAL)
            }
            return try {
                if (action == "status") {
                    ToolResult.ok(renderVdStatus(svc.status()))
                } else {
                    // start/restart：服务侧在首次调用时自动建屏，
                    // 这里通过一次 dumpTree 触发建屏，再读状态
                    runCatching { svc.dumpTree(false, 1, false) }
                    val st = try { JSONObject(svc.status()) } catch (_: Throwable) { null }
                    if (st == null) {
                        ToolResult.ok("已触发副屏$action")
                    } else {
                        val disp = st.optInt("display_id", -1)
                        val age = st.optLong("frame_age_ms", -1)
                        val usable = st.optBoolean("running", false) &&
                            st.optInt("frame_bytes", 0) > 0 && age >= 0 && age <= 5000
                        // 对齐 CLI T:1544
                        ToolResult.ok(
                            "副屏已启动\n  display id: $disp\n  可用: " +
                                if (usable) "是" else "否（帧缓存还没刷新，稍等再试）",
                        )
                    }
                }
            } catch (e: Throwable) {
                ToolResult.Error("副屏操作失败：${e.message}", ToolResult.INTERNAL)
            }
        }

        /**
         * status() 的 JSON → 加工文本（对齐 CLI T:1518-1525）。
         *
         * 原来直接把原始 JSON 扔给模型：没有「未运行 → 用 phone_vd start 启动」的引导，
         * 也看不出帧缓存新不新鲜 —— description 里承诺的「帧缓存新鲜度」形同虚设。
         * 字段拿不到（解析失败/缺字段）时原样返回或省略对应行，不编造。
         */
        private fun renderVdStatus(raw: String): String {
            val st = try { JSONObject(raw) } catch (_: Throwable) { null }
                ?: return raw
            if (!st.optBoolean("running", false)) {
                return "副屏未运行。用 phone_vd start 启动。"
            }
            val disp = st.optInt("display_id", -1)
            val age = st.optLong("frame_age_ms", -1)
            val hasFrame = st.optInt("frame_bytes", 0) > 0 && age >= 0
            // 守护侧持续把最新帧编成 JPEG，正常 age 在秒级内；
            // >5s 说明合成停了（息屏/副屏没起来）—— 画面可能是旧的。
            val fresh = hasFrame && age <= 5000
            val lines = mutableListOf(
                "副屏运行中",
                "  display id: $disp",
            )
            if (hasFrame) {
                lines += "  帧缓存    : ${if (fresh) "新鲜" else "已过期"}（${age / 1000} 秒前更新）"
            }
            if (!fresh) lines += "  ⚠️ 帧缓存过期，画面可能是旧的。建议 phone_vd restart"
            return lines.joinToString("\n")
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
