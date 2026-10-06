package com.ccm.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccm.app.R
import com.ccm.app.ui.theme.CCMTheme
import com.ccm.app.ui.theme.CCMText

/**
 * 加号菜单 —— 对齐 Web `MainContent.tsx:4457` 的 `renderSharedPlusMenu`。
 *
 * ═══════════════════════════════════════════════════════════════
 * 【2026-10-06 问题26 新建】
 *
 * 用户原话：「输入框旁的加号点开后与 web 页不同，这个必须改得完全一样，
 * 上次改个模型栏你也是一直偷懒」。
 *
 * 之前 APK 的加号**直接拉起文件选择器**，没有任何菜单 —— 与 Web 完全不同。
 * 本文件按 Web 的 JSX 逐项复刻。
 * ═══════════════════════════════════════════════════════════════
 *
 * ## Web 的完整结构（6 项 + 2 条分割线）
 * ```
 * ┌─────────────────────────┐
 * │ 📎 添加文件或照片        │
 * │ 📷 截取屏幕             │
 * │ 📁 添加到项目     ›     │  ← 有子菜单（项目列表 + 新建项目）
 * │ ─────────────────────── │
 * │ ✨ 技能                  │  ← 有子菜单（技能列表 + 管理技能）
 * │ ⚙️  管理技能             │
 * │ ─────────────────────── │
 * │ 🔌 添加连接器            │
 * └─────────────────────────┘
 * ```
 *
 * ## 样式（Web 原值 → 屏幕值，×0.92）
 * - 外壳：`w-[218px] rounded-[12px] px-[7px] pb-px pt-[7px]`
 *   → **200.56dp 宽 / 11.04dp 圆角 / 水平 6.44dp / 上 6.44dp**
 * - 项：`h-[32px] gap-[8px] rounded-[8px] px-[8px] py-[6px]`
 *   → **29.44dp 高 / 7.36dp 间距 / 7.36dp 圆角 / 7.36dp 内距**
 * - 图标：`h-[20px] w-[20px]` → **18.4dp**
 * - 文字：`text-[14px] leading-[20px] tracking-[-0.1504px]`
 *   → **12.88sp / 18.4sp / −0.1384sp**
 * - 分割线：`mx-[8px] my-[7px] h-px` → **水平 7.36dp / 垂直 6.44dp / 1px**
 * - 子菜单：`absolute left-full top-0 ml-2 w-[218px] max-h-[30vh]`
 *   → **左偏移 200.56dp / 顶部对齐 / 宽 200.56dp / 高上限 30vh**
 *
 * ## 为什么两个分割线的位置不同
 * 第一条在「添加到项目」之后（把「输入内容」和「管理」分开），
 * 第二条在「管理技能」之后（把「技能」和「连接器」分开）。
 * 这个分组是 Web 的设计，照搬。
 *
 * @param onDismiss     点任意项后收起菜单
 * @param onAttach      添加文件或照片 → 拉起图片选择器
 * @param onScreenshot  截取屏幕 → 调用 phone.screenshot 桥
 * @param onAddToProject 添加到项目 → 打开项目子菜单
 * @param projects      项目列表（子菜单用）
 * @param onPickProject 选中某个项目
 * @param onCreateProject 新建项目
 * @param skills        技能列表（子菜单用）
 * @param onPickSkill   选中某个技能
 * @param onManageSkills 管理技能 → 跳定制页
 * @param onConnectors  添加连接器 → 跳定制页连接器 tab
 */
@Composable
fun PlusMenu(
    onDismiss: () -> Unit,
    onAttach: () -> Unit,
    onScreenshot: () -> Unit,
    onAddToProject: () -> Unit = {},
    projects: List<String> = emptyList(),
    onPickProject: (String) -> Unit = {},
    onCreateProject: () -> Unit = {},
    skills: List<Pair<String, String>> = emptyList(),   // (id, name)
    onPickSkill: (String) -> Unit = {},
    onManageSkills: () -> Unit = {},
    onConnectors: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = CCMTheme.colors
    // 子菜单展开状态（同一时刻只有一个，对齐 Web 的 showSkillsSubmenu/showProjectsSubmenu）
    var submenu by remember { mutableStateOf<SubmenuKind?>(null) }

    // 外壳：Web `w-[218px] rounded-[12px] border px-[7px] pb-px pt-[7px]`
    Column(
        modifier = modifier
            .width(200.56.dp)
            .clip(RoundedCornerShape(11.04.dp))
            .background(colors.input)
            .padding(horizontal = 6.44.dp)
            .padding(top = 6.44.dp, bottom = 1.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // ── 1. 添加文件或照片 ────────────────────────────────────────
        PlusMenuItem(
            iconRes = R.drawable.ic_pm_attach,
            label = "添加文件或照片",
            onEnter = { submenu = null },
            onClick = { onDismiss(); onAttach() },
        )

        // ── 2. 截取屏幕 ─────────────────────────────────────────────
        PlusMenuItem(
            iconRes = R.drawable.ic_pm_screenshot,
            label = "截取屏幕",
            onEnter = { submenu = null },
            onClick = { onDismiss(); onScreenshot() },
        )

        // ── 3. 添加到项目（有子菜单）────────────────────────────────
        PlusMenuItem(
            iconRes = R.drawable.ic_pm_project,
            label = "添加到项目",
            trailingChevron = true,
            onEnter = { submenu = SubmenuKind.PROJECTS },
            onClick = {
                // Web：hover 展开子菜单，点击是**新建项目**（chevron 只是指示）
                // 这里改为：点击展开/收起子菜单（触屏没有 hover）
                submenu = if (submenu == SubmenuKind.PROJECTS) null else SubmenuKind.PROJECTS
            },
        )
        if (submenu == SubmenuKind.PROJECTS) {
            ProjectSubmenu(
                projects = projects,
                onPick = { name -> onDismiss(); onPickProject(name) },
                onCreate = { onDismiss(); onCreateProject() },
            )
        }

        // ── 分割线 1 ────────────────────────────────────────────────
        Spacer(Modifier.height(6.44.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 7.36.dp)
                .height(1.dp)
                .background(colors.border),
        )
        Spacer(Modifier.height(6.44.dp))

        // ── 4. 技能（有子菜单）──────────────────────────────────────
        PlusMenuItem(
            iconRes = R.drawable.ic_pm_skills,
            label = "技能",
            trailingChevron = true,
            onEnter = { submenu = SubmenuKind.SKILLS },
            onClick = {
                submenu = if (submenu == SubmenuKind.SKILLS) null else SubmenuKind.SKILLS
            },
        )
        if (submenu == SubmenuKind.SKILLS) {
            SkillsSubmenu(
                skills = skills,
                onPick = { id -> onDismiss(); onPickSkill(id) },
                onManage = { onDismiss(); onManageSkills() },
            )
        }

        // ── 5. 管理技能 ─────────────────────────────────────────────
        PlusMenuItem(
            iconRes = R.drawable.ic_pm_skills,
            label = "管理技能",
            onEnter = { submenu = null },
            onClick = { onDismiss(); onManageSkills() },
        )

        // ── 分割线 2 ────────────────────────────────────────────────
        Spacer(Modifier.height(6.44.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 7.36.dp)
                .height(1.dp)
                .background(colors.border),
        )
        Spacer(Modifier.height(6.44.dp))

        // ── 6. 添加连接器 ───────────────────────────────────────────
        PlusMenuItem(
            iconRes = R.drawable.ic_pm_connectors,
            label = "添加连接器",
            onEnter = { submenu = null },
            onClick = { onDismiss(); onConnectors() },
        )
    }
}

/** 子菜单类型（同一时刻只展开一个）。 */
private enum class SubmenuKind { PROJECTS, SKILLS }

/**
 * 单个菜单项 —— 对齐 Web `landingPlusMenuItemClass`。
 *
 * `flex h-[32px] w-full items-center gap-[8px] rounded-[8px] px-[8px] py-[6px]`
 * → **29.44dp 高 / 7.36dp 间距 / 7.36dp 圆角 / 7.36dp 内距**
 */
@Composable
private fun PlusMenuItem(
    iconRes: Int,
    label: String,
    onClick: () -> Unit,
    onEnter: () -> Unit = {},
    trailingChevron: Boolean = false,
) {
    val colors = CCMTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(29.44.dp)
            .clip(RoundedCornerShape(7.36.dp))
            .clickable { onEnter(); onClick() }
            .padding(horizontal = 7.36.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.36.dp),
    ) {
        PainterIcon(
            iconRes,
            size = 18.4.dp,
            tint = colors.textMain,
        )
        Text(
            text = label,
            style = CCMText.body14.copy(
                fontSize = 12.88.sp,
                lineHeight = 18.4.sp,
            ),
            color = colors.textMain,
            modifier = Modifier.weight(1f),
        )
        if (trailingChevron) {
            PainterIcon(
                R.drawable.ic_pm_chevron,
                size = 14.72.dp,
                tint = colors.textSecondary,
            )
        }
    }
}

/**
 * 项目子菜单 —— 对齐 Web 的 `plusMenuSubmenuClass` 容器。
 *
 * Web 里它是**悬浮在右侧**的（`absolute left-full ml-2`）。
 * 手机上屏幕窄（393dp），悬浮右侧会超出屏幕 —— 改为**内联展开**
 * （缩进 + 浅底色区分层级），信息结构一致。
 */
@Composable
private fun ProjectSubmenu(
    projects: List<String>,
    onPick: (String) -> Unit,
    onCreate: () -> Unit,
) {
    val colors = CCMTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 200.dp)
            .clip(RoundedCornerShape(7.36.dp))
            .background(colors.hover.copy(alpha = 0.4f))
            .verticalScroll(rememberScrollState())
            .padding(vertical = 3.68.dp),
    ) {
        if (projects.isEmpty()) {
            Text(
                "还没有项目",
                style = CCMText.body12,
                color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = 14.72.dp, vertical = 7.36.dp),
            )
        }
        projects.forEach { name ->
            SubmenuRow(label = name, onClick = { onPick(name) })
        }
        SubmenuRow(label = "新建项目", onClick = onCreate, accent = true)
    }
}

/** 技能子菜单 —— 同 ProjectSubmenu 的容器策略。 */
@Composable
private fun SkillsSubmenu(
    skills: List<Pair<String, String>>,
    onPick: (String) -> Unit,
    onManage: () -> Unit,
) {
    val colors = CCMTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 200.dp)
            .clip(RoundedCornerShape(7.36.dp))
            .background(colors.hover.copy(alpha = 0.4f))
            .verticalScroll(rememberScrollState())
            .padding(vertical = 3.68.dp),
    ) {
        skills.forEach { (id, name) ->
            SubmenuRow(label = name, onClick = { onPick(id) })
        }
        SubmenuRow(label = "管理技能", onClick = onManage, accent = true)
    }
}

@Composable
private fun SubmenuRow(
    label: String,
    onClick: () -> Unit,
    accent: Boolean = false,
) {
    val colors = CCMTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(29.44.dp)
            .clip(RoundedCornerShape(5.52.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.04.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = CCMText.body13.copy(fontSize = 12.sp),
            color = if (accent) colors.accent else colors.textMain,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
    }
}
