package com.ccm.app.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccm.app.core.plugin.DshHostManager
import com.ccm.app.core.plugin.PluginManager
import com.ccm.app.ui.theme.CCMTheme
import com.ccm.app.ui.theme.CCMText
import kotlinx.coroutines.launch

/**
 * 插件面板（DSH 插件宿主）—— 对齐 Web `customize/PluginPanel.tsx` 与 CLI `/plugin`。
 *
 * 数据来自 PluginManager（宿主 /control/... 的客户端）。
 * 自愈与 CLI/Web 一致：宿主没起时刷新会自动拉起；首次使用先
 * DshHostManager.deploy（装 Node + 依赖，约 334MB，可能十几分钟）。
 *
 * 【与「连接器」的区别】
 *   · 连接器 = MCP 服务器（协议级工具接入）
 *   · 插件 = Cordis 插件（常驻宿主进程，如账号池 / 免费额度聚合）
 * 不同层次的扩展机制。
 */
@Composable
fun PluginPanel(modifier: Modifier = Modifier) {
    val colors = CCMTheme.colors
    val scope = rememberCoroutineScope()
    // ⚠️ 必须在 composable 顶层拿 —— refresh() 是普通 fun，
    // 里面不能调 @Composable 的 requireContext()（编译不过）
    val appCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext

    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<PluginManager.HostStatus?>(null) }
    var providers by remember { mutableStateOf<List<PluginManager.Provider>>(emptyList()) }
    var bundles by remember { mutableStateOf<List<PluginManager.Bundle>>(emptyList()) }
    var hostAlive by remember { mutableStateOf<Boolean?>(null) }   // null = 未知
    var busy by remember { mutableStateOf<String?>(null) }        // 正在忙的条目名
    var error by remember { mutableStateOf("") }
    var showInstall by remember { mutableStateOf(false) }
    var installTarget by remember { mutableStateOf("") }
    var logLine by remember { mutableStateOf("") }   // install 操作的进度（部署进度在 deployState）

    // 部署进度/错误走全局状态（deployState）—— 长任务（Node + 334MB
    // npm ci 十几分钟）跑在 AppGraph.appScope，不随窗口销毁被取消；
    // UI 只订阅。详见 PluginDeployState 的注释。
    val deployState = com.ccm.app.core.plugin.PluginDeployState

    /**
     * 自愈：宿主没起就部署 + 启动。
     *
     * ⚠️ 协程跑在 **AppGraph.appScope**（应用级），不是 remember 的
     * UI scope —— 部署十几分钟，UI scope 会在窗口销毁（切页 / 虚拟副屏
     * 被系统回收）时取消协程，npm ci 断在半路前功尽弃（真机踩过）。
     * 本函数只负责投递，进度由 deployState 订阅驱动。
     */
    fun startHeal() {
        if (deployState.running) return   // 已在跑，不重复投递
        deployState.reset()
        com.ccm.app.AppGraph.appScope?.launch {
            try {
                val host = DshHostManager(appCtx)
                if (host.state() is DshHostManager.State.NotInstalled ||
                    host.state() is DshHostManager.State.DepsMissing
                ) {
                    deployState.appendLog("首次安装插件宿主（约 334MB，可能十几分钟）…")
                    val ok = host.deploy { s -> deployState.appendLog(s) }
                    if (!ok) {
                        deployState.fail("宿主部署失败（日志见上）")
                        return@launch
                    }
                }
                deployState.appendLog("启动插件宿主…")
                val started = host.start { s -> deployState.appendLog(s) }
                if (!started) {
                    // start() 的日志含 host.log 崩溃栈尾部
                    deployState.fail("宿主启动失败（日志见上）")
                    return@launch
                }
                deployState.succeed()
            } catch (t: Throwable) {
                deployState.fail("自愈异常：${t.message}")
            }
        }
    }

    // 查询（秒级）：状态 / providers / bundles —— UI scope 即可
    fun refresh(heal: Boolean = false) {
        scope.launch {
            loading = true
            error = ""
            try {
                val host = DshHostManager(appCtx)
                val alive = host.isAlive()
                hostAlive = alive
                if (alive) {
                    when (val r = PluginManager.status()) {
                        is PluginManager.Result.Ok -> status = r.value
                        is PluginManager.Result.Err -> error = "状态获取失败: ${r.message}"
                    }
                    // providers / bundles 失败不阻塞主状态
                    providers = (PluginManager.providers() as? PluginManager.Result.Ok)?.value ?: emptyList()
                    bundles = (PluginManager.bundles() as? PluginManager.Result.Ok)?.value ?: emptyList()
                } else if (heal) {
                    // 【2026-10-07 修】自愈判断必须放在**查询完成之后** ——
                    // 原来写成「refresh() 后紧跟 if (hostAlive == false)」，
                    // 但 refresh 是 scope.launch 异步的，那行跑的时候
                    // hostAlive 还是初始值 null，条件永远不成立 → 自愈
                    // 从未被投递（真机实测：面板永远停在空态）。
                    startHeal()
                }
            } catch (t: Throwable) {
                error = t.message ?: "读取插件状态失败"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { refresh(heal = true) }
    // 自愈完成信号 → 重新查询
    LaunchedEffect(deployState.doneTick) {
        if (deployState.doneTick > 0) refresh()
    }
    // 部署失败过且当前没在跑 → 用户点「重试」会把 startHeal 再投一次；
    // 这里只负责让 UI 反映 deployState（读取处直接绑，不需额外 effect）

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        // ── 头部 ─────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("插件", style = CCMText.body16Bold, color = colors.textMain)
                Spacer(Modifier.height(2.dp))
                Text(
                    "DSH 插件（Cordis 框架）—— 常驻宿主进程的扩展，与「连接器」不同层次。",
                    style = CCMText.body12,
                    color = colors.textSecondary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (showInstall) "收起" else "安装",
                    style = CCMText.body13.copy(fontWeight = FontWeight.Medium),
                    color = colors.textMain,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                        .clickable { showInstall = !showInstall }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
                Text(
                    "刷新",
                    style = CCMText.body13.copy(fontWeight = FontWeight.Medium),
                    color = if (loading) colors.textSecondary else colors.textMain,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                        .clickable(enabled = !loading) { refresh(heal = true) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }

        // ── 错误条（查询错误 + 部署错误合并显示）────────────────
        val shownError = if (error.isNotBlank()) error
        else if (deployState.error.isNotBlank()) deployState.error else ""
        if (shownError.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.error.copy(alpha = 0.10f))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(shownError, style = CCMText.body12, color = colors.error, modifier = Modifier.weight(1f))
                Text(
                    "✕",
                    color = colors.error.copy(alpha = 0.6f),
                    modifier = Modifier.clickable {
                        error = ""
                        deployState.error = ""
                        deployState.log = ""
                    },
                )
            }
        }

        // ── 进度（部署在 deployState，install 在本地 logLine）──────
        val shownLog = buildString {
            if (deployState.log.isNotBlank()) append(deployState.log)
            if (logLine.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(logLine)
            }
        }
        if (shownLog.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                shownLog + if (deployState.running) " ⏳" else "",
                style = CCMText.body11,
                color = colors.textSecondary,
                maxLines = 4,
            )
        }

        // ── 安装输入 ─────────────────────────────────────────
        if (showInstall) {
            Spacer(Modifier.height(12.dp))
            InstallBox(
                target = installTarget,
                onTargetChange = { installTarget = it },
                busy = busy != null,
                onInstall = {
                    val spec = installTarget.trim()
                    if (spec.isBlank()) return@InstallBox
                    busy = spec
                    scope.launch {
                        logLine = "安装 $spec（npm 装包 + 热加载，可能几分钟）…"
                        error = when (val r = PluginManager.install(spec)) {
                            is PluginManager.Result.Ok -> ""
                            is PluginManager.Result.Err -> "安装失败: ${r.message}"
                        }
                        if (error.isBlank()) {
                            installTarget = ""
                            showInstall = false
                            logLine = "已安装 $spec"
                        } else {
                            logLine = ""
                        }
                        busy = null
                        refresh()
                    }
                },
            )
        }

        Spacer(Modifier.height(16.dp))

        // ── 宿主未运行：空态 + 重试（会自动拉起）──────────────
        if (loading && hostAlive == null) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                Text("读取插件状态…", style = CCMText.body13, color = colors.textSecondary)
            }
            return@Column
        }
        if (hostAlive == false && !loading && !deployState.running) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("插件宿主未运行", style = CCMText.body15, color = colors.textMain)
                Spacer(Modifier.height(6.dp))
                Text(
                    "无法连接到 DSH 宿主（proot 内 127.0.0.1:8790）",
                    style = CCMText.body12,
                    color = colors.textSecondary,
                )
                val reason = error.ifBlank {
                    deployState.error.lineSequence().firstOrNull().orEmpty()
                }
                if (reason.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(reason, style = CCMText.body11, color = colors.textSecondary, maxLines = 2)
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "重试（会自动拉起）",
                    style = CCMText.body13.copy(fontWeight = FontWeight.Medium),
                    color = colors.bgMain,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.textMain)
                        .clickable {
                            // 重试 = 查询 +（若没起）投递自愈
                            refresh(heal = true)
                        }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                )
            }
            return@Column
        }

        val st = status ?: return@Column

        // ── 概览 3 卡 ────────────────────────────────────────
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("插件", st.loaded.size, Modifier.weight(1f))
            StatCard("服务", st.serviceCount, Modifier.weight(1f))
            StatCard("Provider", st.providerCount, Modifier.weight(1f))
        }

        Spacer(Modifier.height(20.dp))

        // ── 已加载插件 ───────────────────────────────────────
        SectionTitle("已加载插件")
        if (st.loaded.isEmpty()) {
            EmptyHint("还没有加载任何插件")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                st.states.forEach { p ->
                    PluginRow(
                        name = p.name,
                        subtitle = when {
                            p.active -> "活跃"
                            p.state == 0 -> "挂起（等待依赖）"
                            else -> "状态 ${p.state}"
                        },
                        active = p.active,
                        busy = busy == p.name,
                        onDisable = {
                            busy = p.name
                            scope.launch {
                                error = when (val r = PluginManager.setPlugin(p.name, false)) {
                                    is PluginManager.Result.Ok -> ""
                                    is PluginManager.Result.Err -> r.message
                                }
                                busy = null
                                refresh()
                            }
                        },
                        onRemove = {
                            busy = p.name
                            scope.launch {
                                error = when (val r = PluginManager.remove(p.name)) {
                                    is PluginManager.Result.Ok -> ""
                                    is PluginManager.Result.Err -> r.message
                                }
                                busy = null
                                refresh()
                            }
                        },
                    )
                }
            }
        }

        // ── 可安装 ───────────────────────────────────────────
        if (bundles.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            SectionTitle("可安装")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                bundles.forEach { b ->
                    val installed = b.installed || b.name in st.loaded
                    BundleRow(
                        bundle = b,
                        installed = installed,
                        busy = busy == b.name,
                        onInstall = {
                            busy = b.name
                            scope.launch {
                                logLine = "安装 ${b.name}…"
                                error = when (val r = PluginManager.install(b.name)) {
                                    is PluginManager.Result.Ok -> ""
                                    is PluginManager.Result.Err -> "安装失败: ${r.message}"
                                }
                                logLine = ""
                                busy = null
                                refresh()
                            }
                        },
                    )
                }
            }
        }

        // ── Provider 接入地址 ────────────────────────────────
        if (providers.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            SectionTitle("Provider 接入地址")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                providers.forEach { p -> ProviderRow(p) }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ═══ 子组件 ═══

@Composable
private fun StatCard(label: String, value: Int, modifier: Modifier = Modifier) {
    val colors = CCMTheme.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Text("$value", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = colors.textMain)
        Spacer(Modifier.height(4.dp))
        Text(label, style = CCMText.body12, color = colors.textSecondary)
    }
}

@Composable
private fun SectionTitle(text: String) {
    val colors = CCMTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = CCMText.body14.copy(fontWeight = FontWeight.Medium), color = colors.textMain)
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun EmptyHint(text: String) {
    val colors = CCMTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .padding(vertical = 26.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = CCMText.body13, color = colors.textSecondary)
    }
}

@Composable
private fun PluginRow(
    name: String,
    subtitle: String,
    active: Boolean,
    busy: Boolean,
    onDisable: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = CCMTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .background(colors.bgSidebar)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.bgMain),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (active) "●" else "◐", color = if (active) Color(0xFF4CAF50) else Color(0xFFFFC107), fontSize = 14.sp)
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = CCMText.body13.copy(fontWeight = FontWeight.Medium), color = colors.textMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = CCMText.body11, color = colors.textSecondary)
        }
        Text(
            if (busy) "…" else "禁用",
            style = CCMText.body12,
            color = colors.textSecondary,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(enabled = !busy, onClick = onDisable)
                .padding(horizontal = 8.dp, vertical = 5.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            if (busy) "…" else "卸载",
            style = CCMText.body12,
            color = colors.textSecondary,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(enabled = !busy, onClick = onRemove)
                .padding(horizontal = 8.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun BundleRow(bundle: PluginManager.Bundle, installed: Boolean, busy: Boolean, onInstall: () -> Unit) {
    val colors = CCMTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .background(colors.bgSidebar)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(bundle.name, style = CCMText.body13.copy(fontWeight = FontWeight.Medium), color = colors.textMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (bundle.description.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(bundle.description, style = CCMText.body11, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (installed) {
            Text(
                "已安装",
                style = CCMText.body11,
                color = colors.textSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.bgMain)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        } else {
            Text(
                if (busy) "安装中…" else "安装",
                style = CCMText.body12.copy(fontWeight = FontWeight.Medium),
                color = colors.textMain,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(6.dp))
                    .clickable(enabled = !busy, onClick = onInstall)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun ProviderRow(p: PluginManager.Provider) {
    val colors = CCMTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .background(colors.bgSidebar)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.name, style = CCMText.body13.copy(fontWeight = FontWeight.Medium), color = colors.textMain)
            Spacer(Modifier.width(6.dp))
            Text(
                p.id,
                style = CCMText.body11,
                color = colors.textSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.bgMain)
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (p.ready) "就绪" else "未就绪",
                style = CCMText.body12,
                color = if (p.ready) Color(0xFF4CAF50) else Color(0xFFFFC107),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            p.baseUrl,
            style = CCMText.body11,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(colors.bgMain)
                .padding(horizontal = 7.dp, vertical = 5.dp),
        )
        if (p.models.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "${p.models.size} 个模型：${p.models.take(3).joinToString("、")}" +
                    if (p.models.size > 3) " …" else "",
                style = CCMText.body11,
                color = colors.textSecondary,
            )
        }
    }
}

@Composable
private fun InstallBox(
    target: String,
    onTargetChange: (String) -> Unit,
    busy: Boolean,
    onInstall: () -> Unit,
) {
    val colors = CCMTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .background(colors.bgSidebar)
            .padding(14.dp),
    ) {
        Text("安装插件包", style = CCMText.body13.copy(fontWeight = FontWeight.Medium), color = colors.textMain)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                    .background(colors.bgMain)
                    .padding(horizontal = 10.dp, vertical = 9.dp),
            ) {
                if (target.isEmpty()) {
                    Text("包名，如 dsh-account-pool", style = CCMText.body12, color = colors.textSecondary)
                }
                BasicTextField(
                    value = target,
                    onValueChange = onTargetChange,
                    singleLine = true,
                    textStyle = CCMText.body12.copy(color = colors.textMain),
                    cursorBrush = SolidColor(colors.textMain),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                if (busy) "安装中…" else "安装",
                style = CCMText.body13.copy(fontWeight = FontWeight.Medium),
                color = if (busy || target.isBlank()) colors.textSecondary else colors.bgMain,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (busy || target.isBlank()) colors.bgMain else colors.textMain)
                    .clickable(enabled = !busy && target.isNotBlank(), onClick = onInstall)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "npm 装包 + 热加载，通常需要十几秒。",
            style = CCMText.body11,
            color = colors.textSecondary,
        )
    }
}
