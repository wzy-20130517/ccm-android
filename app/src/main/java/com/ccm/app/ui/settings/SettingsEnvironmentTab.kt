package com.ccm.app.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme
import com.ccm.app.ui.theme.CcmMono
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * 设置页 · 环境 tab —— 对齐 `EnvironmentPanel.tsx`（139 行）。
 *
 * 只在 CCM 原生外壳里显示（`/api/ccm/status` 返回 `ccm:true`）。
 * 数据来自 Kotlin 桥接服务器（127.0.0.1:3457），反映 Shizuku / rootfs / proot
 * 的真实状态 —— 用户偶尔来看一眼「手机操作为什么不 work」。
 *
 * ## 源码结构
 * 1. 头部：图标 + 标题「CCM 原生环境」+ 副标题 + 右侧「刷新」按钮
 * 2. Section「原生能力桥」：Shizuku / Linux 环境 / proot / Node / 内核 / Android SDK
 * 3. Section「运行时」：运行模式 / Node 版本 / 平台 / 家目录 / 工作区 / 已运行
 * 4. 底部说明卡（4 条）
 *
 * ## 实测（Tailwind × 0.92）
 * - Section 容器：`rounded-xl border p-4 space-y-2` → 圆角 11.04 / 内距 14.72
 * - Row：`py-1.5 text-sm` → 上下 5.52，字号 `text-sm`(14) 移动端实测 12.999 → 11.96
 * - 状态图标：`w-3.5 h-3.5` = 14×14 → 12.88
 * - 值文本：`font-mono text-xs`(12) 移动端实测 11.62 → 10.69
 */
@Composable
fun SettingsEnvironmentTab(modifier: Modifier = Modifier) {
    val colors = CCMTheme.colors
    // ★ 刷新按钮的触发器（2026-09-27）：改变它触发重组，重新读取环境状态。
    //   原来「刷新」的 onClick 是空的 —— 点了毫无反应。
    var refreshTick by remember { mutableStateOf(0) }
    // RUN_COMMAND 权限请求（2026-10-06）—— dangerous 级，弹系统框即可授。
    // 原来 CCM 声明了权限但**从不请求**，用户选外接 Termux 后必然报
    // "Not allowed to start service Intent ... without permission"。
    val termuxPermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { refreshTick++ }

    // ── 真数据（2026-09-27：原来 11 行全是写死的假值，
    //    连 Node 版本都写着过时的 v22.14.0，工具链真值是 v24.21.0）──
    // remember(refreshTick)：点「刷新」重查，不点不重复 IO。
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // ══════════════════════════════════════════════════════════════
    //  【2026-10-06 改异步】原来整块在 remember 里**同步**做 ——
    //  其中 ShizukuBridge.granted() 可能走 binder IPC（Shizuku 服务响应慢时
    //  卡数百毫秒），点「环境」tab 会明显卡顿。
    //  现在：先给默认值渲染，IO 线程探测完回填（首次显示快，数据随后到）。
    // ══════════════════════════════════════════════════════════════
    var env by remember(refreshTick) {
        androidx.compose.runtime.mutableStateOf(
            EnvFacts(
                shizuku = false, linux = false, proot = false,
                termuxInstalled = false, termuxPerm = false,
                sdk = android.os.Build.VERSION.SDK_INT.toString(),
                abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
                home = ctx.filesDir.absolutePath,
                uptimeMs = 0L,
            ),
        )
    }
    androidx.compose.runtime.LaunchedEffect(refreshTick) {
        env = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val rootfs = com.ccm.app.runtime.RootfsManager(ctx)
            val installed = try { rootfs.isInstalled() } catch (_: Throwable) { false }
            EnvFacts(
                shizuku = try { com.ccm.app.bridge.ShizukuBridge.granted() } catch (_: Throwable) { false },
                linux = installed,
                proot = installed,
                termuxInstalled = try {
                    ctx.packageManager.getPackageInfo("com.termux", 0); true
                } catch (_: Throwable) { false },
                termuxPerm = com.ccm.app.tools.bash.TermuxChannel.hasRunCommandPermission(ctx),
                sdk = android.os.Build.VERSION.SDK_INT.toString(),
                abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
                home = ctx.filesDir.absolutePath,
                uptimeMs = try {
                    android.os.SystemClock.elapsedRealtime() -
                        android.os.Process.getStartElapsedRealtime()
                } catch (_: Throwable) { 0L },
            )
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(22.08.dp)) {

        // ── 头部 ─────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "CCM 原生环境",
                    style = CCMText.body18.copy(
                        fontSize = 16.56.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = colors.textMain,
                )
                Spacer(Modifier.height(3.68.dp))        // mt-1
                Text(
                    text = "App 内嵌的 Linux 运行时与原生能力状态",
                    style = CCMText.body14.copy(fontSize = 11.96.sp),
                    color = colors.textSecondary,
                )
            }
            Spacer(Modifier.width(7.36.dp))
            // 刷新按钮：px-3 py-1.5 rounded-lg text-sm bg-claude-btn
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.36.dp))
                    .background(colors.btnHover)
                    // ★ 2026-09-27 修：原来 `.clickable { }` 是空的 ——
                    //   点「刷新」毫无反应。这里触发状态重新检测。
                    .clickable { refreshTick++ }
                    .padding(horizontal = 11.04.dp, vertical = 5.52.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.52.dp),
            ) {
                RefreshIcon(color = colors.textMain, size = 12.88.dp)
                Text(
                    text = "刷新",
                    style = CCMText.body14.copy(fontSize = 11.96.sp),
                    color = colors.textMain,
                )
            }
        }

        // ── 原生能力桥 ───────────────────────────────────────────
        EnvSection(title = "原生能力桥") {
            EnvRow(
                label = "Shizuku",
                ok = env.shizuku,
                value = if (env.shizuku) "已授权" else "未授权",
            )
            EnvRow(
                label = "Linux 环境",
                ok = env.linux,
                value = if (env.linux) "已安装" else "未安装",
            )
            EnvRow(
                label = "proot 运行时",
                ok = env.proot,
                value = if (env.proot) "就绪" else "不可用",
            )
            // 「CCM 内核」行已删（audit-settings #4：无法探测状态却写死「已安装」——
            // 不可探测的行不显示，别展示假状态）
            EnvRow(label = "Android SDK", ok = true, value = env.sdk)

            // ── 外接 Termux（2026-10-06 加）────────────────────────────
            // 三态：未安装 / 已装未授权 / 就绪。
            // 权限那行可点（弹系统授权框）—— 这是用户报
            // "Not allowed to start service ... without permission" 的解法。
            EnvRow(
                label = "外接 Termux",
                ok = env.termuxInstalled && env.termuxPerm,
                value = when {
                    !env.termuxInstalled -> "未安装"
                    !env.termuxPerm -> "已安装 · 待授权"
                    else -> "就绪"
                },
            )
            if (env.termuxInstalled && !env.termuxPerm) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.claudeOrange.copy(alpha = 0.12f))
                        .clickable {
                            termuxPermLauncher.launch(
                                com.ccm.app.tools.bash.TermuxChannel.PERMISSION_RUN_COMMAND
                            )
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "点此授权「在 Termux 中运行命令」",
                            style = CCMText.body12.copy(fontSize = 11.96.sp),
                            color = colors.claudeOrange,
                        )
                        // 【2026-10-06 补说明】用户不知道这权限是干嘛的 ——
                        // 光看「在 Termux 中运行命令」不知道为什么要授权。
                        Text(
                            text = "Bash 工具要把命令送进 Termux 执行，系统要求先允许",
                            style = CCMText.body11.copy(fontSize = 10.48.sp),
                            color = colors.textSecondary,
                        )
                    }
                }
            }
        }

        // ── 运行时 ───────────────────────────────────────────────
        EnvSection(title = "运行时") {
            // ★ audit-settings #5：原写死 "native"（rootfs 装了也显示 native）
            EnvRow(
                label = "运行模式",
                ok = true,
                value = if (env.linux) "proot (Ubuntu rootfs)" else "native",
            )
            EnvRow(label = "平台", ok = true, value = "android / ${env.abi}")
            EnvRow(label = "家目录", ok = true, value = env.home)
            EnvRow(label = "已运行", ok = true, value = formatUptime(env.uptimeMs))
        }

        // ── 说明卡 ───────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(7.36.dp))
                .background(colors.btnHover.copy(alpha = 0.4f))
                .padding(14.72.dp),
            verticalArrangement = Arrangement.spacedBy(3.68.dp),
        ) {
            Text(
                text = "关于这些状态",
                style = CCMText.body12.copy(
                    fontSize = 11.04.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = colors.textMain,
            )
            EnvNoteLine("Shizuku", "手机操作（点击/输入/读界面）的主力通道，跑在虚拟副屏上不占物理屏。未授权时在 App 主界面点授权。")
            EnvNoteLine("Linux 环境", "AI 的工具链（git/python/ffmpeg 等）跑在这里面。")
            EnvNoteLine("proot 运行时", "让 Linux 环境无需 root 就能跑。")
            Text(
                text = "· 装更多工具（Python/Rust/PHP/SSH 等）在 App 的「工具链」界面里勾选。",
                style = CCMText.body12.copy(fontSize = 11.04.sp, lineHeight = 16.56.sp),
                color = colors.textSecondary,
            )
        }
    }
}

/**
 * 环境面板小节 —— 对应源码
 * `<div className="rounded-xl border border-claude-border p-4 space-y-2">`。
 * 移动端实测（Tailwind × 0.92）：圆角 11.04 · 内距 14.72 · 间距 7.36。
 */
@Composable
private fun EnvSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = CCMTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.04.dp))
            .border(1.dp, colors.border, RoundedCornerShape(11.04.dp))
            .padding(14.72.dp),
        verticalArrangement = Arrangement.spacedBy(7.36.dp),
    ) {
        Text(
            text = title,
            style = CCMText.body14.copy(
                fontSize = 11.96.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = colors.textMain,
            modifier = Modifier.padding(bottom = 7.36.dp),     // mb-2
        )
        content()
    }
}

/**
 * 状态行 —— 对应源码
 * `<div className="flex items-center justify-between py-1.5 text-sm">
 *    <span text-claude-textSecondary>label</span>
 *    <span className="flex items-center gap-1.5">
 *      <CheckCircle2 className="w-3.5 h-3.5 text-green-500"/>   ← 或 XCircle text-red-500
 *      <span text-claude-text font-mono text-xs>value</span>
 *    </span>
 *  </div>`
 *
 * ⚠️ 源码这里用 `justify-between`，移动端 CSS 会把**设置页里的**
 * `.flex.items-center.justify-between` 改成纵向堆叠。但这行是
 * `flex items-center justify-between py-1.5 text-sm` —— 选择器要求 class
 * 里同时有 `items-center` 和 `justify-between`，它满足，所以**也是纵向的**。
 */
@Composable
private fun EnvRow(label: String, ok: Boolean, value: String) {
    val colors = CCMTheme.colors
    Column(modifier = Modifier.padding(vertical = 5.52.dp)) {   // py-1.5
        Text(
            text = label,
            style = CCMText.body14.copy(fontSize = 11.96.sp),
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(2.76.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.52.dp),   // gap-1.5
        ) {
            StatusDot(ok = ok, size = 12.88.dp)
            Text(
                text = value,
                style = CCMText.body12.copy(
                    fontSize = 10.69.sp,
                    fontFamily = CcmMono,
                ),
                color = colors.textMain,
            )
        }
    }
}

/**
 * 状态圆点 —— 源码是 lucide 的 `CheckCircle2` / `XCircle`。
 *
 * 实测尺寸 `w-3.5 h-3.5` = 14×14 → 屏幕 **12.88**。
 * 颜色：成功 `text-green-500` = #22C55E，失败 `text-red-500` = #EF4444。
 * 这里用「圆 + 勾/叉」简笔画出，比引入整套图标库轻。
 */
@Composable
private fun StatusDot(ok: Boolean, size: androidx.compose.ui.unit.Dp) {
    val color = if (ok) Color(0xFF22C55E) else Color(0xFFEF4444)
    Canvas(modifier = Modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val stroke = 1.29.dp.toPx()
        // 外圆
        drawCircle(
            color = color,
            radius = r - stroke / 2,
            style = Stroke(width = stroke),
        )
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        if (ok) {
            // 勾
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(cx - r * 0.42f, cy)
                lineTo(cx - r * 0.08f, cy + r * 0.34f)
                lineTo(cx + r * 0.44f, cy - r * 0.36f)
            }
            drawPath(p, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
        } else {
            // 叉
            val d = r * 0.34f
            drawLine(color, Offset(cx - d, cy - d), Offset(cx + d, cy + d), stroke, StrokeCap.Round)
            drawLine(color, Offset(cx + d, cy - d), Offset(cx - d, cy + d), stroke, StrokeCap.Round)
        }
    }
}

/** 刷新图标 —— 源码 lucide `RefreshCw`，用一段 3/4 圆弧 + 箭头近似 */
@Composable
private fun RefreshIcon(color: Color, size: androidx.compose.ui.unit.Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val stroke = 1.38.dp.toPx()
        val pad = stroke / 2
        drawArc(
            color = color,
            startAngle = 60f,
            sweepAngle = 280f,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
            topLeft = Offset(pad, pad),
            size = androidx.compose.ui.geometry.Size(
                this.size.width - stroke,
                this.size.height - stroke,
            ),
        )
    }
}

/** 说明卡里的一行（`· **术语**：解释`） */
@Composable
private fun EnvNoteLine(term: String, desc: String) {
    Text(
        text = "· $term：$desc",
        style = CCMText.body12.copy(fontSize = 11.04.sp, lineHeight = 16.56.sp),
        color = CCMTheme.colors.textSecondary,
    )
}

/**
 * 设置页 · Models tab —— 对齐 `SettingsPage.tsx:261` 的
 * `{tab === 'models' && <ProviderSettings />}`。
 *
 * 真正的实现在 `ProviderSettings.tsx`（1469 行），见 [SettingsModelsTab] 的调用方
 * `ProviderSettingsScreen`。这里只是一个转接壳，保持源码的 tab 结构。
 */
@Composable
fun SettingsModelsTab(modifier: Modifier = Modifier) {
    ProviderSettingsScreen(modifier = modifier)
}

/** 环境页真数据快照（remember(refreshTick) 缓存，点刷新重查）。 */
private data class EnvFacts(
    val shizuku: Boolean,
    val linux: Boolean,
    val proot: Boolean,
    /** Termux 是否已安装（2026-10-06：环境模式选外接 Termux 时用）。 */
    val termuxInstalled: Boolean = false,
    /** RUN_COMMAND 权限是否已授予（dangerous 级，弹框可授）。 */
    val termuxPerm: Boolean = false,
    val sdk: String,
    val abi: String,
    val home: String,
    val uptimeMs: Long,
)

/** 进程运行时长 → "2 小时 13 分" / "45 分" / "30 秒"。 */
private fun formatUptime(ms: Long): String {
    if (ms <= 0) return "—"
    val sec = ms / 1000
    val min = sec / 60
    val hour = min / 60
    return when {
        hour > 0 -> "$hour 小时 ${min % 60} 分"
        min > 0 -> "$min 分"
        else -> "$sec 秒"
    }
}
