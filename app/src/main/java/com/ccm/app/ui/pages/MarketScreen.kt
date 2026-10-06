package com.ccm.app.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 市场 —— 从远端下载 skill / MCP。
 *
 * ═══════════════════════════════════════════════════════════════
 * 【2026-10-06 问题40 新建】
 *
 * ## 架构（运行时从网络下载，不是打包进 APK）
 *
 * ```
 * GitHub Release (market-v1)
 *   ├── registry.json          ← 清单（进入页面时拉）
 *   ├── mail-qq.tar.gz         ← MCP 包
 *   └── skill-xxx.tar.gz       ← skill 包
 *         ↓ 点「下载」→ 拉 tar.gz → 解压
 * files/mcp/<名字>/            ← MCP server（+ 写 mcp.json）
 * files/skills/<名字>/         ← skill
 * ```
 *
 * 这样加新资源不用重发 APK —— 往 Release 传包 + 更新 registry.json 即可。
 *
 * ## 装完还要做什么
 *
 * | 类型 | 装完 |
 * |---|---|
 * | **skill** | 直接用（`/skills` 能看到）|
 * | **mcp** | 填 env（如邮箱授权码）→ 重启 App 后工具出现 |
 * ═══════════════════════════════════════════════════════════════
 */
@Composable
fun MarketScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    val colors = CCMTheme.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(MarketTab.SKILL) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<MarketItem>>(emptyList()) }
    var log by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var configFor by remember { mutableStateOf<MarketItem?>(null) }

    // 已装的（从文件系统判断）
    // 已安装集合：存「**安装目录名**」（skill）与「mcp 服务器名」。
    //
    // 【2026-10-06 修】原来直接用 item.id 比对，但 skill 装到磁盘时用的是
    // **去掉前缀的名字**（anthropic-skill-academy-guide → academy-guide），
    // 于是 `item.id in installed` 永远 false —— 装完还显示「下载」。
    // 现在两边都走同一个推导函数（skillDirName），不会再错位。
    val installed = remember(refresh) {
        val s = mutableSetOf<String>()
        try {
            val skillsDir = java.io.File(ctx.filesDir, "skills")
            skillsDir.listFiles()?.forEach { f ->
                s += if (f.isDirectory) f.name else f.name.removeSuffix(".md")
            }
            val mcpFile = java.io.File(ctx.filesDir, "mcp.json")
            if (mcpFile.exists()) {
                val o = org.json.JSONObject(mcpFile.readText())
                o.optJSONObject("mcpServers")?.keys()?.forEach { s += it }
            }
        } catch (_: Throwable) {}
        s
    }

    // 拉清单
    LaunchedEffect(refresh) {
        loading = true
        error = ""
        val r = withContext(Dispatchers.IO) {
            try {
                com.ccm.app.core.market.MarketClient.fetchRegistry()
            } catch (t: Throwable) {
                null
            }
        }
        if (r == null) {
            error = "拉取市场清单失败（检查网络后点「刷新」）"
        } else {
            items = r
        }
        loading = false
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgMain),
    ) {
        // ── 顶部 ─────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.218.dp, vertical = 7.86.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("←", style = CCMText.body14, color = colors.textMain)
                Text(
                    "市场",
                    style = CCMText.body13.copy(fontWeight = FontWeight.Medium),
                    color = colors.textMain,
                )
            }
            MarketTab.entries.forEach { t ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (t == tab) colors.hover else Color.Transparent)
                        .clickable { tab = t }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        t.label,
                        style = CCMText.body13,
                        color = if (t == tab) colors.textMain else colors.textSecondary,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                "刷新",
                style = CCMText.body12.copy(fontSize = 11.sp),
                color = colors.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { refresh++ }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))

        // ── 内容 ─────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                loading -> Text(
                    "正在拉取市场清单…",
                    style = CCMText.body12,
                    color = colors.textSecondary,
                )
                error.isNotBlank() -> Text(
                    error,
                    style = CCMText.body12,
                    color = Color(0xFFD9534F),
                )
                else -> {
                    val list = items.filter { it.type == tab.type }
                    if (list.isEmpty()) {
                        Text(
                            "（${tab.label}分类下暂时没有可下载的）",
                            style = CCMText.body12,
                            color = colors.textSecondary,
                        )
                    } else {
                        list.forEach { item ->
                            MarketItemCard(
                                item = item,
                                installed = installedNameOf(item) in installed,
                                busy = busy == item.id,
                                onInstall = {
                                    if (item.type == "mcp" && item.env.isNotEmpty()) {
                                        configFor = item
                                    } else {
                                        busy = item.id
                                        scope.launch {
                                            log = "正在下载 ${item.name}…"
                                            val ok = com.ccm.app.core.market.MarketClient.install(ctx, item) { s -> log = s }
                                            // 【2026-10-06 修】失败时**保留详情** ——
                                            // 原来直接覆盖成「安装失败」四个字，用户看不到
                                            // 是下载失败/解压失败/Node 装不上（onLog 逐条
                                            // 回传的 ❌ 原因全被这一行盖掉）。
                                            log = if (ok) "✅ ${item.name} 安装完成"
                                            else "❌ ${item.name} 安装失败（原因见上方日志）\n$log"
                                            busy = null
                                            refresh++
                                        }
                                    }
                                },
                                onUninstall = {
                                    busy = item.id
                                    scope.launch {
                                        val ok = com.ccm.app.core.market.MarketClient.uninstall(ctx, item) { s -> log = s }
                                        log = if (ok) "已卸载 ${item.name}" else "卸载失败"
                                        busy = null
                                        refresh++
                                    }
                                },
                            )
                        }
                    }
                }
            }

            if (log.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    log,
                    style = CCMText.body12.copy(fontSize = 11.sp, lineHeight = 16.sp),
                    color = colors.textSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.input.copy(alpha = 0.6f))
                        .padding(12.dp),
                )
            }

        }
    }

    // MCP 配置对话框
    configFor?.let { item ->
        McpEnvDialog(
            item = item,
            onDismiss = { configFor = null },
            onConfirm = { env ->
                configFor = null
                busy = item.id
                scope.launch {
                    log = "正在下载 ${item.name}…"
                    val ok = com.ccm.app.core.market.MarketClient.install(ctx, item, env) { s -> log = s }
                    log = if (ok) "✅ ${item.name} 安装完成（重启 App 后可用）"
                    else "❌ 安装失败（原因见上方日志）\n$log"
                    busy = null
                    refresh++
                }
            },
        )
    }
}

/**
 * 条目在磁盘上的「安装名」—— 与 MarketClient 的安装/卸载路径必须一致。
 *
 * skill：去掉源前缀（anthropic-skill- / skill-）→ 目录名
 * 其他：直接用 id
 *
 * ⚠️ 改这里要同步改 MarketClient 的 install/uninstall（两边必须一致，
 * 否则出现「装完了还显示未安装」）。
 */
private fun installedNameOf(item: MarketItem): String = when (item.type) {
    "skill" -> item.id.removePrefix("anthropic-skill-").removePrefix("skill-").ifBlank { item.id }
    else -> item.id
}

/** 市场 tab。 */
private enum class MarketTab(val label: String, val type: String) {
    SKILL("技能", "skill"),
    MCP("MCP", "mcp"),
    PLUGIN("插件", "plugin"),
}

/** 市场条目（从 registry.json 解析）。 */
data class MarketItem(
    val id: String,
    val type: String,
    val name: String,
    val description: String,
    val author: String = "",
    val size: String = "",
    /** tar.gz 包地址（skill 用）。 */
    val url: String = "",
    /** 散文件地址列表（mcp 用 —— 逐个下载）。 */
    val files: List<String> = emptyList(),
    /** 是否需要 npm install（装依赖）。 */
    val npmInstall: Boolean = false,
    /** 入口文件名（mcp 用，如 server.mjs）。 */
    val entry: String = "",
    /** npm 包名（如 @playwright/mcp）—— 有则用 npm install 装。 */
    val npmPackage: String = "",
    /** 是否需要额外下载浏览器（playwright）。 */
    val installBrowser: Boolean = false,
    /** 需要的 apt 依赖（如 libnss3）。 */
    val aptDeps: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
)

@Composable
private fun MarketItemCard(
    item: MarketItem,
    installed: Boolean,
    busy: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    val colors = CCMTheme.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.input)
            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    item.name,
                    style = CCMText.body13.copy(fontWeight = FontWeight.Medium),
                    color = colors.textMain,
                )
                if (item.size.isNotBlank()) {
                    Text(
                        item.size,
                        style = CCMText.body12.copy(fontSize = 9.sp),
                        color = colors.textSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.hover)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                item.description,
                style = CCMText.body12.copy(fontSize = 11.sp, lineHeight = 15.sp),
                color = colors.textSecondary,
                maxLines = 3,
            )
        }
        when {
            busy -> Text("…", style = CCMText.body12, color = colors.textSecondary)
            // 【2026-10-06 加】DSH 插件不在手机上装（要走 dsh-host）——
            // 只提供「复制安装命令」，用户拿去在 CLI/服务器上跑。
            // 显示成"复制命令"而不是"下载"，避免误导。
            item.type == "plugin" && item.entry.isNotBlank() -> Text(
                "复制命令",
                style = CCMText.body12.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                color = colors.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("dsh", item.entry))
                    }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
            installed -> Text(
                "卸载",
                style = CCMText.body12.copy(fontSize = 11.sp),
                color = colors.textSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onUninstall)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            )
            else -> Text(
                "下载",
                style = CCMText.body12.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                color = colors.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onInstall)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

/** MCP 的 env 配置对话框。 */
@Composable
private fun McpEnvDialog(
    item: MarketItem,
    onDismiss: () -> Unit,
    onConfirm: (Map<String, String>) -> Unit,
) {
    val colors = CCMTheme.colors
    // 【2026-10-06 用户反馈】原来把 item.env 的**提示文本**当值填进输入框
    // （如 MAIL_USER 框里预填"邮箱地址"）—— 用户报「输入框全部被真实字符占位」。
    // 正确做法：输入框空着，提示文本放 placeholder。
    val values = remember {
        androidx.compose.runtime.mutableStateMapOf<String, String>().apply {
            item.env.keys.forEach { k -> put(k, "") }
        }
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.72.dp))
                .background(colors.bgMain)
                .border(1.dp, colors.border, RoundedCornerShape(14.72.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.2.dp),
        ) {
            Text(
                "配置 ${item.name}",
                style = CCMText.body16.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textMain,
            )
            Text(
                item.description,
                style = CCMText.body12.copy(fontSize = 11.sp),
                color = colors.textSecondary,
            )
            item.env.forEach { (k, hint) ->
                Column {
                    Text(k, style = CCMText.body12.copy(fontSize = 10.48.sp), color = colors.textSecondary)
                    // 【2026-10-06】用 Box 叠 placeholder（BasicTextField 没有原生 placeholder）
                    Box {
                        if ((values[k] ?: "").isEmpty()) {
                            Text(
                                hint,
                                style = CCMText.body13.copy(color = colors.textSecondary.copy(alpha = 0.5f)),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            )
                        }
                        androidx.compose.foundation.text.BasicTextField(
                            value = values[k] ?: "",
                            onValueChange = { values[k] = it },
                            textStyle = CCMText.body13.copy(color = colors.textMain),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.claudeOrange),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(7.36.dp))
                                .background(colors.input)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(9.2.dp, Alignment.End),
            ) {
                Text(
                    "取消",
                    style = CCMText.body13,
                    color = colors.textSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Text(
                    "下载",
                    style = CCMText.body13.copy(fontWeight = FontWeight.Medium),
                    color = Color(0xFFD97757),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onConfirm(values.toMap()) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}
