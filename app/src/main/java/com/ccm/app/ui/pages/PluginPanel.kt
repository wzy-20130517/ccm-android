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
    var logLine by remember { mutableStateOf("") }                // 自愈/安装进度
    var showInstall by remember { mutableStateOf(false) }
    var installTarget by remember { mutableStateOf("") }

    // 首次进入 + 手动刷新：自愈（宿主没起 → 尝试起；首次会走部署）
    fun refresh(withHeal: Boolean = true) {
        scope.launch {
            loading = true
            error = ""
            try {
                val host = DshHostManager(appCtx)
                var alive = host.isAlive()
                if (!alive && withHeal) {
                    logLine = "连接宿主失败，尝试自愈…"
                    if (host.state() is DshHostManager.State.NotInstalled ||
                        host.state() is DshHostManager.State.DepsMissing
                    ) {
                        logLine = "首次安装插件宿主（约 334MB，可能十几分钟）…"
                        // 日志**窗口**（保留最后 6 行）—— 单行会被覆盖：
                        // apt 的真实报错是中间某行，最后一行永远是
                        // 「Node 安装失败」这种结论，光看它分不清根因
                        val logWindow = ArrayDeque<String>()
                        val ok = host.deploy { s ->
                            logWindow.addLast(s)
                            while (logWindow.size > 6) logWindow.removeFirst()
                            logLine = logWindow.joinToString("\n")
                        }
                        if (!ok) {
                            val tail = logWindow.joinToString(" | ").ifBlank { "（Node 或依赖没装上）" }
                            error = "宿主部署失败：$tail"
                            loading = false
                            return@launch
                        }
                    }
                    logLine = "启动插件宿主…"
                    host.start { s -> logLine = s }
                    alive = host.isAlive()
                }
                hostAlive = alive
                if (alive) {
                    when (val r = PluginManager.status()) {
                        is PluginManager.Result.Ok -> status = r.value
                        is PluginManager.Result.Err -> error = "状态获取失败: ${r.message}"
                    }
                    // providers / bundles 失败不阻塞主状态
                    providers = (PluginManager.providers() as? PluginManager.Result.Ok)?.value ?: emptyList()
                    bundles = (PluginManager.bundles() as? PluginManager.Result.Ok)?.value ?: emptyList()
                }
            } catch (t: Throwable) {
                error = t.message ?: "读取插件状态失败"
            } finally {
                logLine = ""
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

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
                        .clickable(enabled = !loading) { refresh() }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }

        // ── 错误条 ───────────────────────────────────────────
        if (error.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.error.copy(alpha = 0.10f))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(error, style = CCMText.body12, color = colors.error, modifier = Modifier.weight(1f))
                Text("✕", color = colors.error.copy(alpha = 0.6f), modifier = Modifier.clickable { error = "" })
            }
        }

        // ── 自愈/安装进度 ─────────────────────────────────────
        if (logLine.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(logLine, style = CCMText.body11, color = colors.textSecondary, maxLines = 3)
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
                        refresh(withHeal = false)
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
        if (hostAlive == false && !loading) {
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
                if (error.isBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(error.ifBlank { "" }, style = CCMText.body11, color = colors.textSecondary)
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "重试（会自动拉起）",
                    style = CCMText.body13.copy(fontWeight = FontWeight.Medium),
                    color = colors.bgMain,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.textMain)
                        .clickable { refresh() }
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
                                refresh(withHeal = false)
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
                                refresh(withHeal = false)
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
                                refresh(withHeal = false)
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
