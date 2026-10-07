package com.ccm.app.ui.pages

import androidx.compose.foundation.background
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
import com.ccm.app.R
import com.ccm.app.ui.common.PainterIcon
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme

/**
 * 定制页 —— 对齐 Web 的 `/customize` 路由（`CustomizePage.tsx`，1244 行）。
 *
 * ## ★ 移动端是「单栏互斥导航」（index.css:1316-1363）
 * ```css
 * .customize-shell { flex-direction: column !important; }
 * .customize-nav   { flex-direction: row !important; overflow-x: auto !important; }
 * ★ 关键：单屏互斥 —— 有详情时隐藏列表，没详情时隐藏详情
 * .customize-shell[data-detail-open="1"] .customize-list   { display: none !important; }
 * .customize-shell[data-detail-open="0"] .customize-detail { display: none !important; }
 * ```
 *
 * **所以移动端是**：
 * ```
 * ┌─ 顶部 tab 条：← 定制 | 技能 | 连接器（可横滚，实测 scrollW=456 > 屏宽 393）
 * ├─ 列表（独占剩余空间，自己滚）
 * └─ 详情（选了某项后**替换**列表显示，不是并排）
 * ```
 *
 * ## 实测数据（Playwright，393×852）
 * | 元素 | 实测值 |
 * |---|---|
 * | shell | 393×811.53 / `flex-direction: column` / `data-detail-open="0"` |
 * | nav | 393×**51.45** / `flex-direction: row` / `overflow-x: auto` / **scrollW 456** |
 * | nav 内边距 | **7.86 / 10.218**（`clamp(5,2vw,10)` `clamp(7,2.6vw,14)`） |
 * | nav 间距 | **6px**（注意与 settings 的 4px 不同） |
 * | tab「定制」 | 49.28×19.72 / fw500 / 无背景 |
 * | tab「技能」 | 157.52×36.02 / 圆角 8 / pad 8×12 / fs 12.7725 / **gap 12** |
 * | tab「连接器」 | 157.52×32.34（高度不同！内容决定） |
 *
 * > ⚠️ 三个 tab 的**高度不一致**（19.72 / 36.02 / 32.34）——
 * > 因为它们不是统一规格的按钮，而是「返回按钮 + 两个导航项」混排。
 * > 照抄这个差异，不要"统一美化"。
 *
 * ## 列表滚动的一个坑（源码注释里专门写了）
 * ```css
 * .customize-list {
 *   min-height: 0 !important;      （必须！否则 flex 项被内容撑到全高）
 *   overflow-y: auto !important;
 * }
 * ```
 * 源码注释：「实测 27718px —— 技能列表几百项」。
 * Compose 侧对应 `Modifier.weight(1f)` + `verticalScroll`（weight 已隐含 min-height:0 语义）。
 *
 * @param sections   列表内容
 * @param onDetailOpen 选中某项（移动端切到详情视图）
 */
@Composable
fun CustomizeScreen(
    modifier: Modifier = Modifier,
    sections: List<CustomizeItem> = emptyList(),
    onBack: () -> Unit = {},
) {
    val colors = CCMTheme.colors
    var activeTab by remember { mutableStateOf(CustomizeTab.SKILLS) }
    // 单屏互斥：null = 显示列表，非 null = 显示详情
    var detailItem by remember { mutableStateOf<CustomizeItem?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgMain),
    ) {
        // ── 顶部 tab 条（可横滚）──────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.218.dp, vertical = 7.86.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),   // ★ gap 6（不是 settings 的 4）
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 返回按钮（「← 定制」）—— 实测 49.28×19.72，无背景
            Row(
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BackArrowGlyph(tint = colors.textMain, size = 13.dp)
                Text(
                    text = "定制",
                    style = CCMText.body13.copy(fontWeight = FontWeight.Medium),
                    color = colors.textMain,
                )
            }

            // 两个导航项 —— 实测 157.52 宽 / 圆角 8 / pad 8×12 / gap 12
            CustomizeTab.entries.forEach { t ->
                CustomizeNavItem(
                    label = t.label,
                    iconRes = t.iconRes,
                    active = t == activeTab,
                    onClick = {
                        activeTab = t
                        detailItem = null      // 切 tab 时回到列表
                    },
                )
            }
        }

        // tab 条底边分割线
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.border),
        )

        // ── 内容：列表 或 详情（单屏互斥）────────────────────────────
        val detail = detailItem
        if (activeTab == CustomizeTab.PLUGINS) {
            // 插件面板自带滚动 + 完整操作（状态/启停/安装/Provider），
            // 不走 sections 过滤 —— 数据实时来自 DSH 宿主。
            PluginPanel()
        } else if (detail == null) {
            // 列表视图
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.218.dp, vertical = 11.04.dp),
                verticalArrangement = Arrangement.spacedBy(7.36.dp),
            ) {
                // ★ H4（2026-09-29）：sections 原来是调用方不传的死空态。
                //   现在按 tab 过滤 kind（skills / connectors 两组）。
                val shownItems = sections.filter { it.kind == activeTab.key }
                if (shownItems.isEmpty()) {
                    EmptyCustomize()
                } else {
                    shownItems.forEach { item ->
                        CustomizeListItem(item = item, onClick = { detailItem = item })
                    }
                }
            }
        } else {
            // 详情视图（移动端替换列表，不是并排）
            CustomizeDetail(item = detail, onBack = { detailItem = null })
        }
    }
}

/**
 * 导航项 —— 实测 157.52×36.02 / 圆角 8 / pad 8×12 / gap 12 / fs 12.7725。
 *
 * 注意：两个项的宽度**相同**（157.52），高度**不同**（36.02 / 32.34）——
 * 说明宽度是撑满（`flex: 0 0 auto` + 内容），高度由内容决定。
 */
@Composable
private fun CustomizeNavItem(
    label: String,
    iconRes: Int,
    active: Boolean,
    onClick: () -> Unit,
) {
    val colors = CCMTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) colors.btnHover else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PainterIcon(iconRes, size = 15.dp, tint = if (active) colors.textMain else colors.textSecondary)
        Text(
            text = label,
            // 实测 12.7725 / 19.1587 / fw500
            style = CCMText.body13.copy(
                fontSize = 12.7725.sp,
                lineHeight = 19.1587.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = if (active) colors.textMain else colors.textSecondary,
        )
    }
}

/** 列表项 */
@Composable
private fun CustomizeListItem(item: CustomizeItem, onClick: () -> Unit) {
    val colors = CCMTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.04.dp))
            .background(colors.input)
            .clickable(onClick = onClick)
            .padding(14.72.dp),
    ) {
        Text(
            text = item.name,
            style = CCMText.body14.copy(fontWeight = FontWeight.Medium),
            color = colors.textMain,
        )
        if (item.description.isNotBlank()) {
            Spacer(Modifier.height(3.68.dp))
            Text(
                text = item.description,
                style = CCMText.body12,
                color = colors.textSecondary,
            )
        }
    }
}

/** 详情视图（移动端独占一屏） */
@Composable
private fun CustomizeDetail(item: CustomizeItem, onBack: () -> Unit) {
    val colors = CCMTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.29.dp, vertical = 14.15.dp),
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onBack)
                .padding(bottom = 14.72.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.36.dp),
        ) {
            BackArrowGlyph(tint = colors.textSecondary, size = 13.dp)
            Text(
                text = "返回",
                style = CCMText.body13,
                color = colors.textSecondary,
            )
        }

        Text(
            text = item.name,
            // 源码：text-xl font-bold
            style = CCMText.body20.copy(fontWeight = FontWeight.Bold),
            color = colors.textMain,
        )
        Spacer(Modifier.height(11.04.dp))
        Text(
            text = item.description,
            style = CCMText.body13,
            color = colors.textSecondary,
        )
    }
}

/** 空态 */
@Composable
private fun EmptyCustomize() {
    val colors = CCMTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 80.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "暂无内容",
            style = CCMText.body13,
            color = colors.textSecondary,
        )
    }
}

/** 画返回箭头（lucide 的 ArrowLeft） */
@Composable
private fun BackArrowGlyph(tint: Color, size: androidx.compose.ui.unit.Dp) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = w * 0.09f
        drawLine(
            color = tint,
            start = androidx.compose.ui.geometry.Offset(w * 0.85f, h * 0.5f),
            end = androidx.compose.ui.geometry.Offset(w * 0.15f, h * 0.5f),
            strokeWidth = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = androidx.compose.ui.geometry.Offset(w * 0.45f, h * 0.18f),
            end = androidx.compose.ui.geometry.Offset(w * 0.15f, h * 0.5f),
            strokeWidth = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = androidx.compose.ui.geometry.Offset(w * 0.45f, h * 0.82f),
            end = androidx.compose.ui.geometry.Offset(w * 0.15f, h * 0.5f),
            strokeWidth = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}

/** 定制页的 tab */
enum class CustomizeTab(val key: String, val label: String, val iconRes: Int) {
    SKILLS("skills", "技能", R.drawable.ic_skills),
    CONNECTORS("connectors", "连接器", R.drawable.ic_connectors),
    // 2026-10-07 加：插件 tab（对齐 Web CustomizePage 的 plugins tab）——
    // 面板自带完整列表+操作（PluginPanel），不走 sections 过滤。
    PLUGINS("plugins", "插件", R.drawable.ic_plugins),
}

/** 定制项（UI 层） */
data class CustomizeItem(
    val id: String,
    val name: String,
    val description: String = "",
    val kind: String = "skill",
)
