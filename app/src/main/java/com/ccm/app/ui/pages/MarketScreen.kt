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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

/**
 * 市场 —— skill / MCP / 插件 的下载入口。
 *
 * ═══════════════════════════════════════════════════════════════
 * 【2026-10-06 问题40 新建】
 *
 * ## 为什么独立成页（不塞进定制页的 tab）
 *
 * 定制页已有 3 个 tab（技能 / 连接器 / ...），实测 tab 条宽度
 * 已经超出屏宽（scrollW=456 > 393）。再塞一个会更挤。
 *
 * ## 三类可下载的东西
 *
 * | 类型 | 装到哪 | 数据源 |
 * |---|---|---|
 * | **Skill** | `files/skills/<名字>/SKILL.md` | 内置 5 个 + 用户自定义 |
 * | **MCP** | `files/mcp.json` 的 mcpServers | 内置模板（mail-qq 等）|
 * | **插件** | DSH 宿主（暂未接入 APK）| — |
 *
 * ## 现状（如实标注）
 *
 * - **Skill**：内置 5 个已随 APK 打包（首次启动解压），这里能看/删
 * - **MCP**：内置模板能一键写入 mcp.json（用户填 key 后可用）
 * - **插件**：APK 无 DSH 宿主 —— 如实说明
 * ═══════════════════════════════════════════════════════════════
 */
@Composable
fun MarketScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    val colors = CCMTheme.colors
    var activeTab by remember { mutableStateOf(MarketTab.SKILL) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgMain),
    ) {
        // ── 顶部：返回 + 三个 tab ────────────────────────────────────
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
                MarketTabItem(
                    label = t.label,
                    active = t == activeTab,
                    onClick = { activeTab = t },
                )
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.border),
        )

        // ── 内容 ─────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (activeTab) {
                MarketTab.SKILL -> SkillMarketSection()
                MarketTab.MCP -> McpMarketSection()
                MarketTab.PLUGIN -> PluginMarketSection()
            }
        }
    }
}

/** 市场 tab。 */
private enum class MarketTab(val label: String) {
    SKILL("技能"),
    MCP("MCP"),
    PLUGIN("插件"),
}

@Composable
private fun MarketTabItem(label: String, active: Boolean, onClick: () -> Unit) {
    val colors = CCMTheme.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) colors.hover else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            style = CCMText.body13,
            color = if (active) colors.textMain else colors.textSecondary,
        )
    }
}

// ══════════════════════════════════════════════════════════════════
//  技能市场
// ══════════════════════════════════════════════════════════════════

@Composable
private fun SkillMarketSection() {
    val colors = CCMTheme.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var refresh by remember { mutableStateOf(0) }

    val skills = remember(refresh) {
        try {
            val dir = java.io.File(ctx.filesDir, "skills")
            if (!dir.exists()) emptyList()
            else (dir.listFiles() ?: emptyArray())
                .filter { it.isDirectory || it.name.endsWith(".md") }
                .map { f ->
                    val name = if (f.isDirectory) f.name else f.name.removeSuffix(".md")
                    val desc = try {
                        val md = if (f.isDirectory) java.io.File(f, "SKILL.md") else f
                        if (md.exists()) {
                            val text = md.readText()
                            // 从 frontmatter 提取 description
                            Regex("description:\\s*(.+)").find(text)?.groupValues?.get(1)?.take(80) ?: ""
                        } else ""
                    } catch (_: Throwable) { "" }
                    name to desc
                }
                .sortedBy { it.first }
        } catch (_: Throwable) { emptyList() }
    }

    SectionHeader("已装技能（${skills.size} 个）")
    if (skills.isEmpty()) {
        EmptyHint("还没有技能。\\n\\n内置技能会在首次启动时自动解压到 files/skills/。")
    } else {
        skills.forEach { (name, desc) ->
            MarketCard(
                title = name,
                subtitle = desc.ifBlank { "（无描述）" },
                badge = "内置",
                actions = listOf(
                    "删除" to {
                        try {
                            val dir = java.io.File(ctx.filesDir, "skills")
                            val f = java.io.File(dir, name)
                            if (f.isDirectory) f.deleteRecursively() else f.delete()
                            refresh++
                        } catch (_: Throwable) {}
                    },
                ),
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    SectionHeader("怎么加更多")
    HintCard(
        "**方式一**：把 `<名字>.md` 放到工作区的 `skills/` 目录\\n" +
            "**方式二**：放到应用私有目录的 `files/skills/<名字>/SKILL.md`\\n\\n" +
            "格式：\\n```markdown\\n---\\nname: 技能名\\ndescription: 什么时候用\\n---\\n\\n正文（Agent 展开后看到的内容）\\n```"
    )
}

// ══════════════════════════════════════════════════════════════════
//  MCP 市场
// ══════════════════════════════════════════════════════════════════

/** 内置 MCP 模板（用户填 key 后一键写入 mcp.json）。 */
private data class McpTemplate(
    val name: String,
    val description: String,
    /** 生成配置 JSON 的函数（参数是用户填的 env）。 */
    val buildConfig: (Map<String, String>) -> String,
    /** 需要用户填的环境变量（键 → 提示）。 */
    val requiredEnv: Map<String, String> = emptyMap(),
)

@Composable
private fun McpMarketSection() {
    val colors = CCMTheme.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<McpTemplate?>(null) }

    val installed = remember(refresh) {
        try {
            val f = java.io.File(ctx.filesDir, "mcp.json")
            if (!f.exists()) emptySet()
            else {
                val o = org.json.JSONObject(f.readText())
                val servers = o.optJSONObject("mcpServers") ?: org.json.JSONObject()
                servers.keys().asSequence().toSet()
            }
        } catch (_: Throwable) { emptySet() }
    }

    val templates = remember {
        listOf(
            McpTemplate(
                name = "mail-qq",
                description = "QQ 邮箱收发（IMAP + SMTP）—— 支持接码、读邮件、发邮件",
                requiredEnv = mapOf(
                    "MAIL_USER" to "邮箱地址（如 xxx@qq.com）",
                    "MAIL_PASS" to "授权码（不是 QQ 密码）",
                    "MAIL_HOST" to "imap.qq.com",
                    "MAIL_PORT" to "993",
                ),
                buildConfig = { env ->
                    """{"command":"node","args":["/path/to/mcp-mail/server.mjs"],"env":${org.json.JSONObject(env).toString()}}"""
                },
            ),
        )
    }

    SectionHeader("已装 MCP（${installed.size} 个）")
    if (installed.isEmpty()) {
        EmptyHint("还没有配置 MCP 服务器。")
    } else {
        installed.forEach { name ->
            MarketCard(
                title = name,
                subtitle = "已配置（在 files/mcp.json）",
                badge = "已装",
                actions = listOf(
                    "删除" to {
                        try {
                            val f = java.io.File(ctx.filesDir, "mcp.json")
                            if (f.exists()) {
                                val o = org.json.JSONObject(f.readText())
                                o.optJSONObject("mcpServers")?.remove(name)
                                f.writeText(o.toString(2))
                                refresh++
                            }
                        } catch (_: Throwable) {}
                    },
                ),
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    SectionHeader("可安装")
    templates.filter { it.name !in installed }.forEach { t ->
        MarketCard(
            title = t.name,
            subtitle = t.description,
            badge = "模板",
            actions = listOf("安装" to { editing = t }),
        )
    }

    if (templates.all { it.name in installed }) {
        EmptyHint("所有内置模板都已安装。\\n\\n_自定义 MCP：直接编辑 `files/mcp.json`_")
    }

    // 安装对话框（填 env）
    editing?.let { t ->
        McpInstallDialog(
            template = t,
            onDismiss = { editing = null },
            onConfirm = { env ->
                try {
                    val f = java.io.File(ctx.filesDir, "mcp.json")
                    val o = if (f.exists()) org.json.JSONObject(f.readText()) else org.json.JSONObject()
                    val servers = o.optJSONObject("mcpServers") ?: org.json.JSONObject()
                    servers.put(t.name, org.json.JSONObject(t.buildConfig(env)))
                    o.put("mcpServers", servers)
                    f.writeText(o.toString(2))
                    refresh++
                    editing = null
                    android.widget.Toast.makeText(ctx, "已安装 ${t.name}", android.widget.Toast.LENGTH_SHORT).show()
                } catch (e: Throwable) {
                    android.widget.Toast.makeText(ctx, "安装失败：${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            },
        )
    }
}

@Composable
private fun McpInstallDialog(
    template: McpTemplate,
    onDismiss: () -> Unit,
    onConfirm: (Map<String, String>) -> Unit,
) {
    val colors = CCMTheme.colors
    val values = remember {
        androidx.compose.runtime.mutableStateMapOf<String, String>().apply {
            template.requiredEnv.forEach { (k, v) -> put(k, v) }
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
                "安装 ${template.name}",
                style = CCMText.body16.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textMain,
            )
            Text(
                template.description,
                style = CCMText.body12.copy(fontSize = 11.sp),
                color = colors.textSecondary,
            )
            template.requiredEnv.forEach { (k, hint) ->
                Column {
                    Text(k, style = CCMText.body12.copy(fontSize = 10.48.sp), color = colors.textSecondary)
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
                    "安装",
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

// ══════════════════════════════════════════════════════════════════
//  插件市场
// ══════════════════════════════════════════════════════════════════

@Composable
private fun PluginMarketSection() {
    SectionHeader("插件")
    HintCard(
        "**APK 暂不支持插件** —— 这是架构限制：\\n\\n" +
            "插件（DSH / Cordis 体系）需要**常驻宿主进程**（Node.js 运行时 + 插件加载器）。\\n" +
            "APK 没有 Node 运行时（Node 官方不发 Android 版）。\\n\\n" +
            "**替代路径**：\\n" +
            "- 需要工具扩展 → 用 **MCP**（技能 tab 旁边那个）\\n" +
            "- 需要领域知识 → 用 **技能**（SKILL.md）\\n" +
            "- 需要 hooks → 放 `hooks.json`"
    )
}

// ══════════════════════════════════════════════════════════════════
//  通用组件
// ══════════════════════════════════════════════════════════════════

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = CCMText.body13.copy(fontWeight = FontWeight.Medium),
        color = CCMTheme.colors.textSecondary,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun MarketCard(
    title: String,
    subtitle: String,
    badge: String = "",
    actions: List<Pair<String, () -> Unit>> = emptyList(),
) {
    val colors = CCMTheme.colors
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
                    title,
                    style = CCMText.body13.copy(fontWeight = FontWeight.Medium),
                    color = colors.textMain,
                )
                if (badge.isNotBlank()) {
                    Text(
                        badge,
                        style = CCMText.body12.copy(fontSize = 9.sp),
                        color = colors.textSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.hover)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = CCMText.body12.copy(fontSize = 11.sp),
                    color = colors.textSecondary,
                    maxLines = 2,
                )
            }
        }
        actions.forEach { (label, fn) ->
            Text(
                label,
                style = CCMText.body12.copy(fontSize = 11.sp),
                color = colors.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = fn)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = CCMText.body12.copy(fontSize = 11.sp),
        color = CCMTheme.colors.textSecondary,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun HintCard(text: String) {
    Text(
        text,
        style = CCMText.body12.copy(fontSize = 11.sp, lineHeight = 16.sp),
        color = CCMTheme.colors.textSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CCMTheme.colors.input.copy(alpha = 0.6f))
            .padding(12.dp),
    )
}
