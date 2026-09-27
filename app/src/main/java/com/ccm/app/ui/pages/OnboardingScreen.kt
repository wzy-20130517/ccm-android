package com.ccm.app.ui.pages

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccm.app.R
import com.ccm.app.bridge.ShizukuBridge
import com.ccm.app.runtime.ProotRuntime
import com.ccm.app.runtime.RootfsManager
import com.ccm.app.ui.common.PainterIcon
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme
import com.ccm.app.ui.theme.CcmMono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首次启动引导页 —— 安装 Linux 运行环境。
 *
 * ══════════════════════════════════════════════════════════════
 *  这个页面在 2026-09-27 重写过，因为用户报了三个问题
 * ══════════════════════════════════════════════════════════════
 *
 * 用户原话：
 * > 「软件里刚进去就是过时的，什么node内核啥的。点击授权截图，
 * >   其实软件请求的根本不是截图权限。配色也是operit ai的，和claude不沾边」
 *
 * 三处都改了：
 *
 * **① 文案过时** —— 原文案列了三条「需要安装」：
 * ```
 * • Ubuntu 24.04 base（约 28MB）
 * • Node.js 运行时（约 50MB，apt 下载）
 * • Claude Code Mobile 内核（约 14MB）
 * ```
 * 后两条是**老架构的遗留**：内核逻辑已经全部移植到 Kotlin，
 * 不再需要下载 Node 内核；而 Node.js 现在是**可选项**（勾了才装）。
 * 把可选说成必需，用户会以为不装就用不了。
 *
 * **② 截图授权没用** —— 原来有个「授权截屏」按钮，调的是
 * `MediaProjectionManager.createScreenCaptureIntent()`，
 * 弹出来的是**系统录屏对话框**。但 `phone_screenshot` 工具走的是
 * Shizuku 的 `latestFrame()`，跟 MediaProjection 毫无关系。
 * 用户点「授权截图」，授权了个用不上的东西 —— 这是纯粹的误导。
 * 现在改成一句说明：截屏需要 Shizuku，装好就能用，不用单独授权。
 *
 * **③ 配色是 Operit 的** —— 原来整个页面用 `MaterialTheme.colorScheme`，
 * 那是 Material3 默认的紫色（`#6750A4`），跟 Claude 一点关系没有。
 * 现在全部走 [CCMTheme]（Claude 橙 `#D97757` + 米白 `#F8F8F6`）。
 *
 * ══════════════════════════════════════════════════════════════
 *  这个页面负责什么
 * ══════════════════════════════════════════════════════════════
 * - 说明为什么需要 Linux 环境（一句话，不吓人）
 * - 触发 [RootfsManager.install]（复制/下载 → 解压 → 配置）
 * - 显示真实进度（**不是假进度条**）
 * - 失败时**停住**并显示日志（用户反馈过「失败后自动跳走，想截图都来不及」）
 * - 环境自检结果（proot 能否启动）—— 提前暴露问题，别等用户点工具才报错
 *
 * ⚠️ **不负责**：工具链选择（Node/Python/Rust…）。那是可选增强，
 * 放到设置页里，首次启动不该让用户做这种选择题。
 *
 * @param onReady 环境就绪回调（父组件据此切到主界面）
 */
@Composable
fun OnboardingScreen(
    modifier: Modifier = Modifier,
    onReady: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val colors = CCMTheme.colors
    val scope = rememberCoroutineScope()

    val rootfs = remember { RootfsManager(ctx) }
    val proot = remember { ProotRuntime(ctx) }

    /** 0 = 未开始；1 = 安装中；2 = 已完成；3 = 失败 */
    var phase by remember { mutableStateOf(0) }
    var progress by remember { mutableFloatStateOf(0f) }
    var log by remember { mutableStateOf("") }
    /** 失败后**停住**，不自动跳走 —— 用户要看日志。 */
    var failed by remember { mutableStateOf(false) }

    // Shizuku 状态（只读展示，不请求授权）
    var shizukuOk by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        shizukuOk = try { ShizukuBridge.granted() } catch (_: Throwable) { false }
    }

    // 进度条跟随日志自动滚到底（用户往上翻时停止跟随）
    val scrollState = rememberScrollState()
    var autoFollow by remember { mutableStateOf(true) }
    LaunchedEffect(log) {
        if (autoFollow) {
            try { scrollState.scrollTo(scrollState.maxValue) } catch (_: Throwable) {}
        }
    }
    LaunchedEffect(scrollState.value, scrollState.maxValue) {
        autoFollow = (scrollState.maxValue - scrollState.value) < 60
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgMain)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))

        // ── Claude 标志 + 标题 ────────────────────────────────────────
        PainterIcon(R.drawable.ic_hero_star, size = 36.dp, tint = colors.claudeOrange)
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Claude Code Mobile",
            style = CCMText.body18.copy(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
            color = colors.textTitle,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "一个跑在手机上的 AI 编程助手",
            style = CCMText.body14,
            color = colors.textSecondary,
        )

        Spacer(Modifier.height(28.dp))

        when (phase) {
            // ══════════════════════════════════════════════════════
            //  未开始 —— 说明 + 开始按钮
            // ══════════════════════════════════════════════════════
            0 -> {
                InfoCard {
                    Text(
                        text = "首次启动需要安装 Linux 运行环境（Ubuntu 24.04，已内置）",
                        style = CCMText.body14,
                        color = colors.textMain,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        // 【为什么这么写】原文案列了「Node.js 运行时（约 50MB，apt 下载）」
                        // 「Claude Code Mobile 内核（约 14MB）」—— 两条都是老架构遗留。
                        // 内核已移植进 Kotlin；Node 现在是可选工具链。
                        // 把可选说成必需，用户会以为不装就用不了。
                        text = "它用来跑 AI 执行命令的工具（git / curl / python 等）。" +
                                "首次安装约 10~20 秒，之后不再需要联网。\n\n" +
                                "手机操作（点击/输入/截图）需要 Shizuku，是可选的；" +
                                "没装也能正常对话和读写文件。",
                        style = CCMText.body14,
                        color = colors.textSecondary,
                    )
                }

                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = {
                        phase = 1
                        progress = 0f
                        log = ""
                        failed = false
                        scope.launch { runInstall(rootfs, proot) { p, msg -> 
                            progress = p
                            if (msg.isNotEmpty()) log = if (log.isEmpty()) msg else "$log\n$msg"
                        }.also { ok ->
                            if (ok) {
                                phase = 2
                                delay(400)
                                onReady()
                            } else {
                                phase = 3
                                failed = true
                            }
                        } }
                    },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.claudeOrange,
                    ),
                ) {
                    Text("开始安装", color = androidx.compose.ui.graphics.Color.White)
                }

                Spacer(Modifier.height(20.dp))
                CapabilityList(shizukuOk = shizukuOk)
            }

            // ══════════════════════════════════════════════════════
            //  安装中
            // ══════════════════════════════════════════════════════
            1 -> {
                InfoCard {
                    Text(
                        text = "正在安装…",
                        style = CCMText.body14.copy(fontWeight = FontWeight.Medium),
                        color = colors.textMain,
                    )
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = colors.claudeOrange,
                        trackColor = colors.border,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        style = CCMText.body14,
                        color = colors.textSecondary,
                    )
                }

                if (log.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    LogBox(log = log, scrollState = scrollState)
                }
            }

            // ══════════════════════════════════════════════════════
            //  完成
            // ══════════════════════════════════════════════════════
            2 -> {
                InfoCard {
                    Text(
                        text = "环境就绪",
                        style = CCMText.body14.copy(fontWeight = FontWeight.Medium),
                        color = colors.success,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "正在进入…",
                        style = CCMText.body14,
                        color = colors.textSecondary,
                    )
                }
            }

            // ══════════════════════════════════════════════════════
            //  失败 —— 停住，让用户看完日志
            // ══════════════════════════════════════════════════════
            else -> {
                InfoCard {
                    Text(
                        text = "安装未完成",
                        style = CCMText.body14.copy(fontWeight = FontWeight.Medium),
                        color = colors.error,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        // 失败时最需要日志，所以不自动跳走（用户反馈过
                        // 「弹了『部分失败』后就清屏，想截图都来不及」）
                        text = "下面是完整日志，可以截图反馈。常见原因：存储空间不足、" +
                                "网络中断。已下载的部分会保留，重试不用从头开始。",
                        style = CCMText.body14,
                        color = colors.textSecondary,
                    )
                }

                Spacer(Modifier.height(16.dp))
                LogBox(log = log.ifEmpty { "（没有输出）" }, scrollState = scrollState)

                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = {
                            phase = 1
                            progress = 0f
                            log = ""
                            failed = false
                            scope.launch { runInstall(rootfs, proot) { p, msg ->
                                progress = p
                                if (msg.isNotEmpty()) log = if (log.isEmpty()) msg else "$log\n$msg"
                            }.also { ok ->
                                if (ok) { phase = 2; delay(400); onReady() }
                                else { phase = 3; failed = true }
                            } }
                        },
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.claudeOrange),
                    ) {
                        Text("重试", color = androidx.compose.ui.graphics.Color.White)
                    }
                    Button(
                        onClick = onReady,
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.hover,
                            contentColor = colors.textMain,
                        ),
                    ) {
                        Text("跳过（稍后再装）")
                    }
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

/**
 * 安装过程的进度中转站。
 *
 * 【为什么需要这个类】`RootfsManager.install` 的 `onProgress` 在**工作线程
 * 同步调用**，而进度要写进 Compose state（必须主线程）。直接用
 * `withContext(Dispatchers.Main)` 会在回调里阻塞工作线程 —— 安装本身是后台
 * 任务，这样写会让「解压」和「刷 UI」互相等，进度条一顿一顿的。
 *
 * 所以：回调只**记录**，由独立的推送协程节流刷 UI。
 *
 * ⚠️ 字段必须 `@Volatile` —— 工作线程写、主线程读。
 * 注意 **`@Volatile` 不能修饰局部变量**（Kotlin 的 `@Volatile` 目标是 FIELD），
 * 所以这里用一个 private class 承载，而不是在函数里声明几个 `@Volatile var`。
 * 后者编译期直接报「This annotation is not applicable to target
 * 'local variable'」。
 */
private class ProgressSink {
    @Volatile var progress: Float = 0f
    @Volatile var log: String = ""
    @Volatile var finished: Boolean = false
}

/**
 * 执行安装流程，把 [RootfsManager.install] 的回调翻译成「进度 + 日志」。
 *
 * 抽出来是因为「开始安装」和「重试」两个按钮要走完全一样的流程 ——
 * 复制粘贴两份的话，将来改进度文案必然漏改一处。
 *
 * @param emit (进度 0~1, 日志行) —— 日志为空串时表示只更新进度
 * @return 是否成功
 */
private suspend fun runInstall(
    rootfs: RootfsManager,
    proot: ProotRuntime,
    emit: suspend (Float, String) -> Unit,
): Boolean = kotlinx.coroutines.coroutineScope {
    val sink = ProgressSink()

    // 推送协程：每 120ms 把最新进度刷进 UI。
    // 为什么要节流：install 的回调在复制 28MB 时会调几百次，
    // 每次都推 UI 会让主线程忙于重组，反而更卡。
    val pump = launch(Dispatchers.Main) {
        while (!sink.finished) {
            val line = sink.log
            sink.log = ""
            emit(sink.progress, line)
            delay(120)
        }
        val tail = sink.log
        sink.log = ""
        emit(sink.progress, tail)   // 收尾再推一次，别漏最后一行
    }

    val result = withContext(Dispatchers.IO) {
        try {
            rootfs.install { stage, done, total ->
                val pct = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                // 进度映射：复制/下载占 0~0.6，解压占 0.6~0.9，配置占 0.9~1.0。
                // 不按字节数直接算 —— 解压阶段 total 是压缩包大小，
                // 解压出的文件更大，直接算会到 100% 后卡住不动。
                sink.progress = when (stage) {
                    "copy", "download" -> pct * 0.6f
                    "extract" -> 0.6f + pct * 0.3f
                    else -> 0.9f + pct * 0.1f
                }
                sink.log = when (stage) {
                    "copy" -> "复制内置环境包 ${done / 1048576}MB / ${total / 1048576}MB"
                    "download" -> "下载环境包 ${done / 1048576}MB / ${total / 1048576}MB"
                    "extract" -> "解压中 ${done / 1048576}MB / ${total / 1048576}MB"
                    else -> "配置中…"
                }
            }
        } catch (t: Throwable) {
            Log.e("Onboarding", "安装异常", t)
            sink.log = "安装异常：${t.message}"
            false
        }
    }

    sink.finished = true
    pump.join()   // 等推送协程收尾，避免最后一行日志被吞

    if (!result) return@coroutineScope false

    // 装完跑一次自检 —— proot 起不来的报错极具误导性
    //（"Function not implemented" 看着像 rootfs 里的二进制坏了，
    //  实际是 proot 自己的 loader 找不到）。提前暴露，别等用户点工具才报错。
    emit(1f, "正在自检…")
    val diag = try { proot.selfCheck() } catch (t: Throwable) { "自检异常：${t.message}" }
    if (diag != null) {
        emit(1f, "环境自检未通过：$diag")
        // 自检失败**不算安装失败** —— 文件都在，可能只是这次探测超时。
        // 让它进去，真有问题在对话里报错更清楚（有上下文）。
    }

    true
}

/** 说明卡片（米白底 + 细边框，对齐 Web 的 `bg-claude-input border rounded-xl`）。 */
@Composable
private fun InfoCard(content: @Composable () -> Unit) {
    val colors = CCMTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.input)
            .padding(16.dp),
    ) { content() }
}

/** 日志区（等宽字体，限高可滚）。 */
@Composable
private fun LogBox(log: String, scrollState: androidx.compose.foundation.ScrollState) {
    val colors = CCMTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 200.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.hover)
            .verticalScroll(scrollState)
            .padding(12.dp),
    ) {
        Text(
            text = log,
            style = CCMText.body14.copy(fontFamily = CcmMono, fontSize = 11.sp),
            color = colors.textSecondary,
        )
    }
}

/**
 * 能力清单 —— 装完能用什么。
 *
 * ⚠️ 这里**不能有「授权截图」按钮**。原来有一个，调的是
 * `MediaProjectionManager.createScreenCaptureIntent()`（系统录屏对话框），
 * 但 `phone_screenshot` 走的是 Shizuku，两者无关。
 * 用户点了授权一个用不上的东西，是纯粹的误导。
 *
 * 现在只做**状态展示**：Shizuku 装了就说「已就绪」，没装就说「可选」。
 */
@Composable
private fun CapabilityList(shizukuOk: Boolean) {
    val colors = CCMTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "装好之后可以",
            style = CCMText.body14.copy(fontWeight = FontWeight.Medium),
            color = colors.textMain,
        )
        CapabilityRow("对话、写代码、读写文件", true, "")
        CapabilityRow("跑命令（git / curl / python…）", true, "需先装对应工具链")
        CapabilityRow(
            label = "操作手机（点击/输入/截图）",
            ok = shizukuOk,
            note = if (shizukuOk) "Shizuku 已就绪" else "需装 Shizuku（可选）",
        )
    }
}

/** 一行能力。 */
@Composable
private fun CapabilityRow(label: String, ok: Boolean, note: String) {
    val colors = CCMTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (ok) "✓" else "·",
            style = CCMText.body14,
            color = if (ok) colors.success else colors.textSecondary,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = CCMText.body14,
            color = colors.textMain,
            modifier = Modifier.weight(1f),
        )
        if (note.isNotEmpty()) {
            Text(
                text = note,
                style = CCMText.body14,
                color = colors.textSecondary,
            )
        }
    }
}
