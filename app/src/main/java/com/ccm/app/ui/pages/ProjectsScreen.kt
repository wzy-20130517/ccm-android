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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccm.app.R
import com.ccm.app.ui.common.PainterIcon
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme

/**
 * 项目页 —— 对齐 Web 的 `/projects` 路由（`ProjectsPage.tsx`，858 行）。
 *
 * ## 实测数据（Playwright，393×852，空态）
 * | 元素 | 实测值 |
 * |---|---|
 * | `h1`「项目」 | 21.222 / 27.5886 / fw500 / Spectral / x=37.94 y=87.09 |
 * | 右上「New project」 | 121.94×30.34 / 圆角 8 / pad 6×14 / **深底浅字** / fw500 |
 * | 插图 | y≈240（居中） |
 * | 标题「想开始一个项目吗？」 | 17 / 25.5 / fw500 / **mb 12** / y=264.14 |
 * | 说明文案 | 12.7725 / 20.7553 / textSecondary / mb 14.148 / y=298.63 |
 * | 「新建项目」按钮 | 104.75×36.73 / 圆角 **12** / **描边** / pad 8×16 / fs **14.5** |
 *
 * ## 与 ChatsScreen 的布局差异（重要）
 * | | ChatsScreen | ProjectsScreen |
 * |---|---|---|
 * | 左内边距 | 16 | **37.94**（`px-8` 在移动端 ≈ 38） |
 * | 空态 | 无 | 垂直居中于剩余空间 |
 *
 * ## 插图
 * 用 `start-projects.png`（255×255 源图，实际显示尺寸待测）。
 * 截图显示是「两个方框 + 一只手拿卡片」的线稿。
 *
 * @param projects   项目列表（空则显示空态）
 * @param onCreate   点「新建项目」/「New project」
 * @param onOpen     点某个项目
 */
@Composable
fun ProjectsScreen(
    projects: List<ProjectItemUi> = emptyList(),
    modifier: Modifier = Modifier,
    onCreate: () -> Unit = {},
    onOpen: (ProjectItemUi) -> Unit = {},
) {
    val colors = CCMTheme.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgMain)
            .padding(horizontal = 37.94.dp),
    ) {
        Spacer(Modifier.height(43.09.dp))       // h1 y=87.09 − 顶栏 44

        // ── 标题行 ────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "项目",
                style = CCMText.body20.copy(
                    fontSize = 21.222.sp,
                    lineHeight = 27.5886.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = colors.textMain,
            )

            // 「New project」深色按钮 —— 实测 121.94×30.34 / 圆角 8 / pad 6×14
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.textMain)
                    .clickable(onClick = onCreate)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlusGlyph(tint = colors.bgMain, size = 14.dp, strokeWidth = 2.5f)
                Text(
                    text = "New project",
                    style = CCMText.body14.copy(fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
                    color = colors.bgMain,
                )
            }
        }

        if (projects.isEmpty()) {
            EmptyProjects(onCreate = onCreate)
        } else {
            Spacer(Modifier.height(16.dp))
            projects.forEach { p ->
                ProjectRow(p, onClick = { onOpen(p) })
            }
        }
    }
}

/**
 * 空态 —— 插图 + 标题 + 说明 + 描边按钮，整体水平居中。
 *
 * 实测：标题 y=264.14（顶栏 44 + 标题行 ~70 → 空态从约 220 开始）。
 */
@Composable
private fun EmptyProjects(onCreate: () -> Unit) {
    val colors = CCMTheme.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 128.dp),           // 标题行底 → 插图顶
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 插图（start-projects.png，255×255 源图）
        PainterIcon(
            R.drawable.ic_start_projects,
            size = 62.dp,
            tint = Color.Unspecified,          // 保留原图（线稿是深色描边）
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "想开始一个项目吗？",
            style = CCMText.body16.copy(
                fontSize = 17.sp,
                lineHeight = 25.5.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = colors.textMain,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = "上传资料、设置自定义指令，并在一个空间中整理对话。",
            // 实测 12.7725 / 20.7553
            style = CCMText.body13.copy(fontSize = 12.7725.sp, lineHeight = 20.7553.sp),
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(14.148.dp))

        // 「新建项目」描边按钮 —— 实测 104.75×36.73 / 圆角 12 / pad 8×16 / fs 14.5
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Transparent)
                .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                .clickable(onClick = onCreate)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlusGlyph(tint = colors.textMain, size = 14.5.dp, strokeWidth = 2f)
            Text(
                text = "新建项目",
                style = CCMText.body14.copy(fontSize = 14.5.sp, lineHeight = 21.75.sp, fontWeight = FontWeight.Medium),
                color = colors.textMain,
            )
        }
    }
}

/** 项目列表项（B4 后续填充真实样式；当前 Web 无项目，先做最小实现） */
@Composable
private fun ProjectRow(project: ProjectItemUi, onClick: () -> Unit) {
    val colors = CCMTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.72.dp),
    ) {
        Text(
            text = project.name,
            style = CCMText.body14.copy(fontWeight = FontWeight.Medium),
            color = colors.textMain,
        )
        if (project.description.isNotBlank()) {
            Spacer(Modifier.height(3.68.dp))
            Text(
                text = project.description,
                style = CCMText.body12,
                color = colors.textSecondary,
            )
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0xFFDAD9D4)),
    )
}

/**
 * 画 Plus 图标 —— 与 [ChatsScreen] 里的同名函数重复。
 *
 * TODO(阶段4·B5): 抽到 ui/common 里共用（现在两处各一份是临时状态）
 */
@Composable
private fun PlusGlyph(tint: Color, size: androidx.compose.ui.unit.Dp, strokeWidth: Float) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = strokeWidth / 24f * w
        drawLine(
            color = tint,
            start = androidx.compose.ui.geometry.Offset(w / 2, h * 0.2f),
            end = androidx.compose.ui.geometry.Offset(w / 2, h * 0.8f),
            strokeWidth = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = androidx.compose.ui.geometry.Offset(w * 0.2f, h / 2),
            end = androidx.compose.ui.geometry.Offset(w * 0.8f, h / 2),
            strokeWidth = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}

/** 项目条目（UI 层） */
data class ProjectItemUi(
    val id: String,
    val name: String,
    val description: String = "",
    val chatCount: Int = 0,
)
