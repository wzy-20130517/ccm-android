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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme

/**
 * 产物页 —— 对齐 Web 的 `/artifacts` 路由（`ArtifactsPage.tsx`，423 行）。
 *
 * ## 实测数据（Playwright，393×852）
 * | 元素 | 实测值 |
 * |---|---|
 * | `h1`「产物」 | 21.222 / 27.5886 / fw500 / Spectral / x=34.64 y=57.39 |
 * | 「新建产物」 | 77.27×30.34 / 圆角 8 / pad 6×14 / 深底浅字 |
 * | 标签页 | 「灵感」(激活,textMain) / 「我的产物」(textSecondary) / **pad-bottom 12** |
 * | 标签页底边线 | 整行 1px（激活项下方有深色下划线） |
 * | 分类胶囊 | h=27.83 / **圆角 9999（全圆）** / pad 6×16 / fs 12.183 |
 * | 分类激活 | 深底浅字（`bg-claude-text` + `text-claude-bg`） |
 * | 分类未激活 | 透明底 + textSecondary，**fw 400** |
 * | 卡片 | w=287.69 / 预览图 h=215.77 / **圆角 16** / 白底 / overflow hidden |
 * | 卡片总高 | 261.42（含标题+描述） |
 *
 * ## 布局注意
 * - 卡片 x=47.66（页面 x=34.64 + 13.02 缩进）→ 卡片比标题略右缩
 * - 分类胶囊行**可横向滚动**（5 个胶囊总宽 > 屏宽）
 *
 * @param items        产物列表（灵感模式）
 * @param myItems      我的产物
 * @param onNewArtifact 点「新建产物」
 * @param onOpenItem   点某张卡片
 */
@Composable
fun ArtifactsScreen(
    items: List<ArtifactItemUi> = emptyList(),
    modifier: Modifier = Modifier,
    myItems: List<ArtifactItemUi> = emptyList(),
    onNewArtifact: () -> Unit = {},
    onOpenItem: (ArtifactItemUi) -> Unit = {},
) {
    val colors = CCMTheme.colors

    var activeTab by remember { mutableStateOf(0) }          // 0=灵感 1=我的产物
    var activeCategory by remember { mutableStateOf("全部") }

    // ★ M1（2026-09-28）：activeCategory 原来只改胶囊高亮、不参与取数 ——
    //   「点了没反应」。现在真过滤（Web ArtifactsPage.tsx:114 同款）。
    val baseItems = if (activeTab == 0) items else myItems
    val shown = if (activeCategory == "全部") baseItems
    else baseItems.filter { it.category == activeCategory }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgMain)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(13.39.dp))

        // ── 标题行 ────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 34.64.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "产物",
                style = CCMText.body20.copy(
                    fontSize = 21.222.sp,
                    lineHeight = 27.5886.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = colors.textMain,
            )

            // 「新建产物」深色按钮 —— 77.27×30.34 / 圆角 8 / pad 6×14
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.textMain)
                    .clickable(onClick = onNewArtifact)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "新建产物",
                    style = CCMText.body14.copy(
                        fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium,
                    ),
                    color = colors.bgMain,
                )
            }
        }

        Spacer(Modifier.height(26.2.dp))       // 标题底 82.78 → 标签页顶 112.73 − pad

        // ── 标签页（灵感 / 我的产物）───────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 47.66.dp),
            horizontalArrangement = Arrangement.spacedBy(10.84.dp),
        ) {
            listOf("灵感", "我的产物").forEachIndexed { i, label ->
                val isActive = i == activeTab
                Column(
                    modifier = Modifier
                        .clickable { activeTab = i }
                        .padding(bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = label,
                        // 实测 12.3795 / 18.5693 / fw500
                        style = CCMText.body12.copy(
                            fontSize = 12.3795.sp,
                            lineHeight = 18.5693.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = if (isActive) colors.textMain else colors.textSecondary,
                    )
                    if (isActive) {
                        Spacer(Modifier.height(3.dp))
                        Box(
                            modifier = Modifier
                                .width(22.75.dp)
                                .height(1.dp)
                                .background(colors.textMain),
                        )
                    }
                }
            }
        }

        // 标签页整行底边线（贯穿全宽）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.border),
        )

        Spacer(Modifier.height(26.04.dp))      // 底边线 → 分类胶囊顶 168.88

        // ── 分类胶囊（横向可滚动）─────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 34.64.dp),
            horizontalArrangement = Arrangement.spacedBy(7.36.dp),
        ) {
            ArtifactCategories.forEach { cat ->
                val isActive = cat == activeCategory
                Box(
                    modifier = Modifier
                        .height(27.83.dp)
                        .clip(RoundedCornerShape(13.92.dp))          // 全圆角
                        .background(if (isActive) colors.textMain else Color.Transparent)
                        .clickable { activeCategory = cat }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = cat,
                        // 实测 12.183 / 18.2745；激活 fw500，未激活 fw400
                        style = CCMText.body12.copy(
                            fontSize = 12.183.sp,
                            lineHeight = 18.2745.sp,
                            fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                        ),
                        color = if (isActive) colors.bgMain else colors.textSecondary,
                    )
                }
            }
        }

        Spacer(Modifier.height(13.02.dp))      // 胶囊底 196.71 → 卡片区

        // ── 分组标题（如「放松一下」）──────────────────────────────────
        if (shown.isNotEmpty()) {
            Text(
                text = activeCategory,
                style = CCMText.body13,
                color = colors.textMain,
                modifier = Modifier.padding(start = 47.66.dp, bottom = 13.02.dp),
            )
        }

        // ── 卡片列表 ──────────────────────────────────────────────────
        Column(
            modifier = Modifier.padding(horizontal = 47.66.dp),
            verticalArrangement = Arrangement.spacedBy(11.04.dp),
        ) {
            shown.forEach { item ->
                ArtifactCard(item = item, onClick = { onOpenItem(item) })
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

/**
 * 产物卡片 —— 预览图（圆角 16，overflow hidden）+ 标题 + 描述。
 *
 * 实测：w=287.69 / 预览图 h=215.77 / 圆角 16 / 白底。
 */
@Composable
private fun ArtifactCard(item: ArtifactItemUi, onClick: () -> Unit) {
    val colors = CCMTheme.colors

    Column(modifier = Modifier.clickable(onClick = onClick)) {
        // 预览图容器 —— 圆角 16 + overflow hidden + 白底
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(215.77.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            // TODO(阶段4·B4-d): 真实预览（SVG/HTML 渲染）
            // Web 用 iframe 渲染产物 HTML；Compose 侧需要 WebView 或自绘
            Text(
                text = item.previewText.ifEmpty { "预览" },
                style = CCMText.body14,
                color = Color(0xFF999999),
            )
        }

        Spacer(Modifier.height(11.04.dp))

        Text(
            text = item.title,
            style = CCMText.body13.copy(fontWeight = FontWeight.Medium),
            color = colors.textMain,
        )

        Spacer(Modifier.height(3.68.dp))

        Text(
            text = item.description,
            style = CCMText.body12,
            color = colors.textSecondary,
        )
    }
}

/** 分类标签 —— 对齐 Web 的 `ArtifactsPage.tsx` */
// ★ M3：Web CATEGORY_ORDER 6 项，原来缺「放松一下」→ 该分类数据永远不可达
val ArtifactCategories = listOf("全部", "学习", "生活技巧", "游戏", "创意", "放松一下")

/** 产物条目（UI 层） */
data class ArtifactItemUi(
    val id: String,
    val title: String,
    val description: String = "",
    /** 预览区展示的文字（真实预览需 WebView 渲染 HTML，见 TODO） */
    val previewText: String = "",
    val category: String = "全部",
)
