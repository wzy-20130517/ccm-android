package com.ccm.app.ui.customize

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccm.app.ui.theme.CCMText

/**
 * 目录弹窗（技能 / 连接器 / 插件）—— 对齐 `customize/DirectoryModal.tsx`（984 行）。
 *
 * ## 结构
 * ```
 * 遮罩 bg-[rgba(31,31,30,0.42)] + backdrop-blur-3px + p-3（sm:p-6）
 * └─ 对话框 max-w-1024 / max-h-782 / rounded-16 / bg-#f8f8f6 / p-25
 *     ├─ 标题行：「目录」+ 关闭按钮（h-8 w-8 rounded-6）
 *     └─ 主体 mt-8，flex-col gap-6（lg: 才变横向 + 侧栏 200）
 *         ├─ 分区 Tab：技能 / 连接器 / 插件（移动端横排可滚动）
 *         └─ 右列
 *             ├─ 搜索框（h-9 / rounded-8 / px-13 / text-16）
 *             └─ 分区内容
 *                 ├─ skills：来源 Tab + 添加按钮 + 卡片列表（md: 两列）
 *                 ├─ connectors：来源 Tab + 卡片列表
 *                 └─ plugins：桌面版提示条 + 卡片列表
 * ```
 *
 * ## ★ 移动端形态（本机 393px，**不命中任何 `md:`/`lg:`**）
 * | 类 | 断点 | 393px 下 |
 * |---|---|---|
 * | `lg:flex-row lg:gap-8` | ≥1024 | 不命中 → **纵向堆叠**，gap 24 |
 * | `lg:w-[200px]` | ≥1024 | 不命中 → Tab 横排 + `overflow-x-auto` |
 * | `md:grid-cols-2` | ≥768 | 不命中 → **单列卡片** |
 * | `max-w-[731px]` | — | 343（393 − 25×2），未触及 |
 * | `sm:p-6` | ≥640 | 不命中 → 遮罩内距 **p-3 = 12** |
 *
 * ## ⚠️ 弹窗层级不受 zoom 影响
 * 它在 `fixed z-[105]` 层，**所有尺寸都是原始 px，不乘 0.92** ——
 * 这与项目里 `#root` 内的组件不同，别惯性乘 0.92。
 *
 * ## 配色（源码写死的十六进制，**不是** Tailwind 调色板）
 * 见 [DirectoryTone]。
 */
@Composable
fun DirectoryModal(
    modifier: Modifier = Modifier,
    initialSection: DirectorySection = DirectorySection.CONNECTORS,
    onClose: () -> Unit = {},
    onCreateWithClaude: () -> Unit = {},
    onUploadSkill: () -> Unit = {},
    onWriteSkill: () -> Unit = {},
    onOpenSkill: (String) -> Unit = {},
) {
    var section by remember(initialSection) { mutableStateOf(initialSection) }
    val query by remember(initialSection) { mutableStateOf("") }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DirectoryTone.scrim)
            .clickable(onClick = onClose)
            .padding(12.dp),                        // p-3
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .background(DirectoryTone.surface)
                .border(1.dp, DirectoryTone.border, RoundedCornerShape(16.dp))
                .clickable { }                       // 阻止冒泡
                .padding(25.dp),                     // p-[25px]
        ) {
            // ── 标题行 ──────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "目录",
                    style = CCMText.body16.copy(
                        fontSize = 22.sp,           // .directory-title-anthropic
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = DirectoryTone.textPrimary,
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)                 // h-8 w-8
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    CloseGlyph(color = DirectoryTone.textMuted, size = 16.dp)
                }
            }

            Spacer(Modifier.height(32.dp))          // mt-8

            // ── 分区 Tab（移动端横排 + 可滚动）──────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DirectorySection.entries.forEach { s ->
                    DirectoryTabButton(
                        label = s.label,
                        active = section == s,
                        onClick = { section = s },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))          // gap-6

            // ── 搜索框 ──────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)                   // h-9
                    .clip(RoundedCornerShape(8.dp))
                    .background(DirectoryTone.white)
                    .border(1.dp, DirectoryTone.border, RoundedCornerShape(8.dp))
                    .padding(horizontal = 13.dp),    // px-[13px]
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchGlyph(color = DirectoryTone.textMuted, size = 16.dp)
                Spacer(Modifier.width(8.dp))         // mr-2
                Text(
                    text = query.ifEmpty { section.placeholder },
                    style = CCMText.body16.copy(
                        fontSize = 16.sp,
                        letterSpacing = (-0.3125).sp,
                    ),
                    color = if (query.isEmpty()) DirectoryTone.textMuted else DirectoryTone.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(16.dp))          // mt-4

            // ── 分区内容 ────────────────────────────────────────
            Box(modifier = Modifier.weight(1f)) {
                when (section) {
                    DirectorySection.SKILLS -> SkillsSection(
                        onCreateWithClaude = onCreateWithClaude,
                        onUploadSkill = onUploadSkill,
                        onWriteSkill = onWriteSkill,
                        onOpenSkill = onOpenSkill,
                    )
                    DirectorySection.CONNECTORS -> ConnectorsSection()
                    DirectorySection.PLUGINS -> PluginsSection()
                }
            }
        }
    }
}

/** 分区 Tab 按钮 —— 对应 `DirectoryTabButton`：`rounded-8 px-4 py-[6px] text-14` */
@Composable
private fun DirectoryTabButton(label: String, active: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) DirectoryTone.tabActive else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),   // gap-3
    ) {
        Text(
            text = label,
            style = CCMText.body14.copy(
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = (-0.1504).sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = DirectoryTone.textPrimary,
        )
    }
}

/** 来源 Tab 组 —— 对应 `DirectorySourceTabs`：`h-9 rounded-10 bg-#efeeeb p-1` */
@Composable
private fun SourceTabs(tabs: List<Pair<String, Int>>, activeIndex: Int, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(DirectoryTone.tabActive)
            .padding(4.dp),                          // p-1
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEachIndexed { i, (label, count) ->
            val on = i == activeIndex
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) DirectoryTone.white else Color.Transparent)
                    .clickable { onChange(i) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = label,
                    style = CCMText.body14.copy(
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        letterSpacing = (-0.1504).sp,
                        fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                    ),
                    color = if (on) DirectoryTone.textPrimary else DirectoryTone.textMuted,
                )
                Text(
                    text = "$count",
                    style = CCMText.body12.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = if (on) DirectoryTone.textPrimary else DirectoryTone.textMuted,
                )
            }
        }
    }
}

/** 技能分区 */
@Composable
private fun SkillsSection(
    onCreateWithClaude: () -> Unit,
    onUploadSkill: () -> Unit,
    onWriteSkill: () -> Unit,
    onOpenSkill: (String) -> Unit,
) {
    var source by remember { mutableStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SourceTabs(
                tabs = listOf("官方" to 12, "社区" to 8, "自定义" to 3),
                activeIndex = source,
                onChange = { source = it },
            )
            AddSkillButton(
                onCreateWithClaude = onCreateWithClaude,
                onUploadSkill = onUploadSkill,
                onWriteSkill = onWriteSkill,
            )
        }
        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),   // gap-4
        ) {
            // md:grid-cols-2 在 393px 下不生效 → 单列
            sampleSkills.forEach { card ->
                SkillCard(card = card, onSelect = { onOpenSkill(card.id) })
            }
        }
    }
}

/** 连接器分区 */
@Composable
private fun ConnectorsSection() {
    var source by remember { mutableStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        SourceTabs(
            tabs = listOf("官方" to 9, "社区" to 4),
            activeIndex = source,
            onChange = { source = it },
        )
        Spacer(Modifier.height(16.dp))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            sampleConnectors.forEach { card -> ConnectorCard(card = card) }
        }
    }
}

/** 插件分区 */
@Composable
private fun PluginsSection() {
    Column(modifier = Modifier.fillMaxSize()) {
        // 桌面版提示条：`rounded-12 bg-#f4f4f1 px-13 py-13`
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(DirectoryTone.noticeBg)
                .border(1.dp, DirectoryTone.noticeBorder, RoundedCornerShape(12.dp))
                .padding(13.dp),
        ) {
            Text(
                text = "插件可以浏览，但目前只能在桌面应用中使用。下载桌面版 Claude",
                style = CCMText.body14.copy(
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    letterSpacing = (-0.1504).sp,
                ),
                color = DirectoryTone.textPrimary,
            )
        }
        Spacer(Modifier.height(16.dp))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            samplePlugins.forEach { card -> PluginCard(card = card) }
        }
    }
}

/** 技能卡 —— 对应 `SkillCard`：`min-h-118 rounded-16 p-17` */
@Composable
private fun SkillCard(card: DirectorySkillCardData, onSelect: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DirectoryTone.white)
            .border(1.dp, DirectoryTone.border, RoundedCornerShape(16.dp))
            .clickable(onClick = onSelect)
            .padding(17.dp),                         // p-[17px]
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),   // gap-3
        ) {
            // 图标底：`h-10 w-10 rounded-8 border`
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(DirectoryTone.surface)
                    .border(1.dp, DirectoryTone.border, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                SparkGlyph(color = DirectoryTone.textMuted, size = 18.dp)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = card.title,
                    style = CCMText.body14.copy(
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        letterSpacing = (-0.1504).sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = DirectoryTone.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = card.subtitle,
                    style = CCMText.body12.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = DirectoryTone.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 动作按钮：`h-8 w-8 rounded-6`
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onSelect),
                contentAlignment = Alignment.Center,
            ) {
                PlusGlyph(color = DirectoryTone.textMuted, size = 16.dp)
            }
        }

        Spacer(Modifier.height(12.dp))
        // 描述：`line-clamp-2`
        Text(
            text = card.description,
            style = CCMText.body12.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = DirectoryTone.textMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(12.dp))
        // 徽章：`h-6 rounded-full border px-2.5`
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToneBadge(
                text = card.badge,
                bg = card.badgeBg,
                borderColor = card.badgeBorder,
                fg = card.badgeText,
            )
            if (card.enabled) {
                Text(
                    text = "现在可用",
                    style = CCMText.body11.copy(fontSize = 11.sp, lineHeight = 16.sp),
                    color = DirectoryTone.textMuted,
                )
            }
        }
    }
}

/** 连接器卡 —— 结构同技能卡，徽章用四状态色调 */
@Composable
private fun ConnectorCard(card: DirectoryConnectorCardData) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DirectoryTone.white)
            .border(1.dp, DirectoryTone.border, RoundedCornerShape(16.dp))
            .padding(17.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(DirectoryTone.surface)
                    .border(1.dp, DirectoryTone.border, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                LinkGlyph(color = DirectoryTone.textMuted, size = 18.dp)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = card.title,
                    style = CCMText.body14.copy(
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        letterSpacing = (-0.1504).sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = DirectoryTone.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = card.subtitle,
                    style = CCMText.body12.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = DirectoryTone.textMuted,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = card.description,
            style = CCMText.body12.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = DirectoryTone.textMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))
        ToneBadge(
            text = card.badge,
            bg = card.badgeBg,
            borderColor = card.badgeBorder,
            fg = card.badgeText,
        )
    }
}

/** 插件卡 —— 对应 `PluginCard` */
@Composable
private fun PluginCard(card: PluginCardData) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DirectoryTone.white)
            .border(1.dp, DirectoryTone.border, RoundedCornerShape(16.dp))
            .padding(17.dp),
    ) {
        Text(
            text = card.title,
            style = CCMText.body14.copy(
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = (-0.1504).sp,
                fontWeight = FontWeight.Medium,
            ),
            color = DirectoryTone.textPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = card.provider,
            style = CCMText.body12.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = DirectoryTone.textMuted,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = card.description,
            style = CCMText.body12.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = DirectoryTone.textMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = card.downloads,
            style = CCMText.body11.copy(fontSize = 11.sp, lineHeight = 16.sp),
            color = DirectoryTone.textMuted,
        )
    }
}

/** 状态徽章 —— 对应源码 `inline-flex h-6 items-center rounded-full border px-2.5 text-[11px]` */
@Composable
private fun ToneBadge(text: String, bg: Color, borderColor: Color, fg: Color) {
    Box(
        modifier = Modifier
            .height(24.dp)                           // h-6
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(50))
            .padding(horizontal = 10.dp),            // px-2.5
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = CCMText.body11.copy(
                fontSize = 11.sp,
                lineHeight = 16.sp,
                letterSpacing = (-0.08).sp,
                fontWeight = FontWeight.Medium,
            ),
            color = fg,
        )
    }
}

/** 「添加」按钮 + 下拉菜单 —— 对应 `SkillsActionButton`（`h-9 rounded-10 px-4`） */
@Composable
private fun AddSkillButton(
    onCreateWithClaude: () -> Unit,
    onUploadSkill: () -> Unit,
    onWriteSkill: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(DirectoryTone.white)
                .border(1.dp, DirectoryTone.border, RoundedCornerShape(10.dp))
                .clickable { open = !open }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PlusGlyph(color = DirectoryTone.textPrimary, size = 15.dp)
            Text(
                text = "添加",
                style = CCMText.body14.copy(
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    letterSpacing = (-0.1504).sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = DirectoryTone.textPrimary,
            )
            ChevronDownGlyph(color = DirectoryTone.textMuted, size = 14.dp)
        }
        if (open) {
            Column(
                modifier = Modifier
                    .padding(top = 40.dp)
                    .width(180.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(DirectoryTone.white)
                    .border(1.dp, DirectoryTone.border, RoundedCornerShape(10.dp))
                    .padding(4.dp),
            ) {
                MenuItem("让 Claude 创建", onCreateWithClaude) { open = false }
                MenuItem("上传技能文件", onUploadSkill) { open = false }
                MenuItem("手写技能说明", onWriteSkill) { open = false }
            }
        }
    }
}

/** 下拉菜单项 */
@Composable
private fun MenuItem(label: String, onPick: () -> Unit, onDone: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { onPick(); onDone() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = CCMText.body14.copy(fontSize = 14.sp, lineHeight = 20.sp),
            color = DirectoryTone.textPrimary,
        )
    }
}

/** 空结果 —— 对应 `EmptyResults` */
@Composable
fun DirectoryEmptyResults(title: String, description: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = CCMText.body14.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            color = DirectoryTone.textPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = description,
            style = CCMText.body12.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = DirectoryTone.textMuted,
            textAlign = TextAlign.Center,
        )
    }
}

// ── 数据模型 ───────────────────────────────────────────────────────

/** 分区 —— 对应 `DirectorySection`；[placeholder] 来自 `sectionSearchPlaceholder` */
enum class DirectorySection(val label: String, val placeholder: String) {
    SKILLS("技能", "搜索技能…"),
    CONNECTORS("连接器", "搜索连接器…"),
    PLUGINS("插件", "搜索插件…"),
}

/** 技能卡数据 */
data class DirectorySkillCardData(
    val id: String,
    val title: String,
    val subtitle: String,
    val description: String,
    val badge: String,
    val badgeBg: Color,
    val badgeBorder: Color,
    val badgeText: Color,
    val enabled: Boolean = false,
)

/** 连接器卡数据 */
data class DirectoryConnectorCardData(
    val id: String,
    val title: String,
    val subtitle: String,
    val description: String,
    val badge: String,
    val badgeBg: Color,
    val badgeBorder: Color,
    val badgeText: Color,
)

/** 插件卡数据 */
data class PluginCardData(
    val id: String,
    val title: String,
    val provider: String,
    val description: String,
    val downloads: String,
)

/**
 * 目录弹窗配色 —— 源码用写死的十六进制（**不是** Tailwind 调色板），
 * 四种状态各有亮/暗两套。这里只列亮色（CCM 默认亮色主题）。
 *
 * | 状态 | 边框 | 底 | 文字 |
 * |---|---|---|---|
 * | connected | #B8D8C0 | #F1F8F3 | #275437 |
 * | ready | #D7D1C4 | #F6F3EC | #5C5140 |
 * | manual | rgba(31,31,30,0.12) | #F8F8F6 | #6F6B63 |
 * | preview | #D3DCEB | #F1F5FB | #34537D |
 */
object DirectoryTone {
    val scrim = Color(0x6B1F1F1E)              // rgba(31,31,30,0.42)
    val surface = Color(0xFFF8F8F6)
    val white = Color(0xFFFFFFFF)
    val border = Color(0x261F1F1E)             // rgba(31,31,30,0.15)
    val tabActive = Color(0xFFEFEEEB)
    val noticeBg = Color(0xFFF4F4F1)
    val noticeBorder = Color(0x4D1F1F1E)       // rgba(31,31,30,0.3)
    val textPrimary = Color(0xFF121212)
    val textMuted = Color(0xFF7B7974)

    // 状态色调（badge）
    val connectedBorder = Color(0xFFB8D8C0)
    val connectedBg = Color(0xFFF1F8F3)
    val connectedText = Color(0xFF275437)
    val readyBorder = Color(0xFFD7D1C4)
    val readyBg = Color(0xFFF6F3EC)
    val readyText = Color(0xFF5C5140)
    val manualBorder = Color(0x1F1F1F1E)
    val manualBg = Color(0xFFF8F8F6)
    val manualText = Color(0xFF6F6B63)
    val previewBorder = Color(0xFFD3DCEB)
    val previewBg = Color(0xFFF1F5FB)
    val previewText = Color(0xFF34537D)
}

// ── 图标（Canvas 手绘，项目无 SVG 资源）────────────────────────────

@Composable
private fun CloseGlyph(color: Color, size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = 1.5.dp.toPx()
        drawLine(color, Offset(w * 0.25f, h * 0.25f), Offset(w * 0.75f, h * 0.75f), sw, StrokeCap.Round)
        drawLine(color, Offset(w * 0.75f, h * 0.25f), Offset(w * 0.25f, h * 0.75f), sw, StrokeCap.Round)
    }
}

@Composable
private fun SearchGlyph(color: Color, size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        drawCircle(color, radius = w * 0.28f, center = Offset(w * 0.42f, h * 0.42f), style = Stroke(width = 1.5.dp.toPx()))
        drawLine(color, Offset(w * 0.63f, h * 0.63f), Offset(w * 0.85f, h * 0.85f), 1.5.dp.toPx(), StrokeCap.Round)
    }
}

@Composable
private fun PlusGlyph(color: Color, size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = 1.8.dp.toPx()
        drawLine(color, Offset(w * 0.5f, h * 0.2f), Offset(w * 0.5f, h * 0.8f), sw, StrokeCap.Round)
        drawLine(color, Offset(w * 0.2f, h * 0.5f), Offset(w * 0.8f, h * 0.5f), sw, StrokeCap.Round)
    }
}

@Composable
private fun ChevronDownGlyph(color: Color, size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val p = Path().apply {
            moveTo(w * 0.25f, h * 0.4f)
            lineTo(w * 0.5f, h * 0.65f)
            lineTo(w * 0.75f, h * 0.4f)
        }
        drawPath(p, color, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** 技能卡图标（源码是 skills.png，这里用四角星近似） */
@Composable
private fun SparkGlyph(color: Color, size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val p = Path().apply {
            moveTo(w * 0.5f, h * 0.1f)
            lineTo(w * 0.6f, h * 0.4f)
            lineTo(w * 0.9f, h * 0.5f)
            lineTo(w * 0.6f, h * 0.6f)
            lineTo(w * 0.5f, h * 0.9f)
            lineTo(w * 0.4f, h * 0.6f)
            lineTo(w * 0.1f, h * 0.5f)
            lineTo(w * 0.4f, h * 0.4f)
            close()
        }
        drawPath(p, color, style = Stroke(width = 1.4.dp.toPx()))
    }
}

/** 连接器图标（双环） */
@Composable
private fun LinkGlyph(color: Color, size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val s = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(color, radius = w * 0.18f, center = Offset(w * 0.35f, h * 0.35f), style = s)
        drawCircle(color, radius = w * 0.18f, center = Offset(w * 0.65f, h * 0.65f), style = s)
    }
}

// ── 演示数据 ──────────────────────────────────────────────────────

private val sampleSkills = listOf(
    DirectorySkillCardData(
        id = "create-with-claude", title = "让 Claude 创建技能", subtitle = "对话式生成",
        description = "描述你想要的能力，Claude 会帮你写出完整的技能定义。",
        badge = "官方", badgeBg = DirectoryTone.readyBg, badgeBorder = DirectoryTone.readyBorder,
        badgeText = DirectoryTone.readyText,
    ),
    DirectorySkillCardData(
        id = "write-skill", title = "手写技能说明", subtitle = "高级用法",
        description = "直接编辑 SKILL.md，完全控制触发条件与执行步骤。",
        badge = "官方", badgeBg = DirectoryTone.readyBg, badgeBorder = DirectoryTone.readyBorder,
        badgeText = DirectoryTone.readyText,
    ),
    DirectorySkillCardData(
        id = "termux-video", title = "termux-video", subtitle = "视频制作",
        description = "在 Termux 上用 HTML + headless Chromium + ffmpeg 合成动态视频。",
        badge = "自定义", badgeBg = DirectoryTone.connectedBg, badgeBorder = DirectoryTone.connectedBorder,
        badgeText = DirectoryTone.connectedText, enabled = true,
    ),
    DirectorySkillCardData(
        id = "anti-ai-slop", title = "anti-ai-slop", subtitle = "视觉设计",
        description = "消除视觉产物的 AI 味，提供配色系统与自检清单。",
        badge = "社区", badgeBg = DirectoryTone.previewBg, badgeBorder = DirectoryTone.previewBorder,
        badgeText = DirectoryTone.previewText,
    ),
)

private val sampleConnectors = listOf(
    DirectoryConnectorCardData(
        id = "github", title = "GitHub", subtitle = "代码仓库",
        description = "读写仓库文件、管理 issue 与 PR。",
        badge = "已连接", badgeBg = DirectoryTone.connectedBg,
        badgeBorder = DirectoryTone.connectedBorder, badgeText = DirectoryTone.connectedText,
    ),
    DirectoryConnectorCardData(
        id = "playwright", title = "Playwright", subtitle = "浏览器自动化",
        description = "驱动 Chromium 完成网页操作与截图。",
        badge = "已连接", badgeBg = DirectoryTone.connectedBg,
        badgeBorder = DirectoryTone.connectedBorder, badgeText = DirectoryTone.connectedText,
    ),
    DirectoryConnectorCardData(
        id = "mail", title = "邮箱", subtitle = "IMAP / SMTP",
        description = "收发邮件、自动提取验证码。",
        badge = "已就绪", badgeBg = DirectoryTone.readyBg,
        badgeBorder = DirectoryTone.readyBorder, badgeText = DirectoryTone.readyText,
    ),
)

private val samplePlugins = listOf(
    PluginCardData(
        id = "figma", title = "Figma", provider = "Figma Inc.",
        description = "读取设计稿、导出切图与标注。", downloads = "12.4k 次安装",
    ),
    PluginCardData(
        id = "notion", title = "Notion", provider = "Notion Labs",
        description = "同步页面与数据库内容。", downloads = "8.1k 次安装",
    ),
)
