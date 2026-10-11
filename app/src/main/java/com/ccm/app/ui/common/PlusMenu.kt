package com.ccm.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccm.app.R
import com.ccm.app.ui.theme.CCMTheme
import androidx.compose.ui.text.style.TextOverflow
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

    // 外壳 —— 对齐 Web `landingPlusMenuShellClass`：
    //   `w-[218px] rounded-[12px] border border-[rgba(31,31,30,0.3)]
    //    bg-white px-[7px] pb-px pt-[7px] shadow-[0_2px_8px_rgba(0,0,0,0.08)]`
    //
    // 【2026-10-11 完整还原】原来只做了尺寸和底色，漏了三样：
    //   ① `border` —— 没有边框，与浅色背景糊在一起看不出卡片边界
    //   ② `shadow` —— 没有阴影，缺少"浮在内容之上"的层次
    //   ③ 顺序 —— clip 之后画 border 会被裁掉一半，正确顺序是
    //      shadow（在外）→ clip → background → border
    //
    // 尺寸换算：Web px × 0.92 = APK dp（项目统一约定，见 CcmModal.kt 注释）
    Column(
        modifier = modifier
            .width(200.56.dp)                                    // w-[218px]
            .shadow(2.dp, RoundedCornerShape(11.04.dp))          // shadow-[0_2px_8px_rgba(0,0,0,0.08)]
            .clip(RoundedCornerShape(11.04.dp))                  // rounded-[12px]
            .background(colors.input)                            // bg-white / dark:bg-claude-input
            // ⚠️ 边框色**不能**用全局 `colors.border`（那是 CSS `:root` 的
            // `--border` = #E8E7E3，给输入框/分隔用的）。PlusMenu 的边框是
            // Web 里**硬编码**的 `border-[rgba(31,31,30,0.3)]`（暗色
            // `white/15`）—— 明显更深，是"卡片描边"而非"内容分隔"。
            .border(
                1.dp,
                if (CCMTheme.isDark) Color(0x26FFFFFF) else Color(0x4D1F1F1E),
                RoundedCornerShape(11.04.dp),
            )
            .padding(horizontal = 6.44.dp)                       // px-[7px]
            .padding(top = 6.44.dp, bottom = 1.dp),              // pt-[7px] pb-px
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
            // 【2026-10-06 修 P0】原来 onEnter = { submenu = PROJECTS } 且
            // onClick 里做 toggle —— 而 PlusMenuItem 的点击是
            // `.clickable { onEnter(); onClick() }`（串联），onEnter 先把它
            // 设成 PROJECTS，onClick 立刻读到 PROJECTS → toggle 走 null 分支。
            // Compose 的 MutableState 同线程读写即时可见，于是**每次点击都
            // 净设成 null**，子菜单永远打不开（首页/对话页两个入口都是）。
            //
            // 修：onEnter 只负责「收起别的子菜单」（触屏没有 hover，
            // 不需要"划过即展开"），展开/收起完全交给 onClick 的 toggle。
            onEnter = { if (submenu != SubmenuKind.PROJECTS) submenu = null },
            onClick = {
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
                // 分隔线色也是 Web 硬编码的 `bg-[rgba(31,31,30,0.15)]`
                // （暗色 `white/10`），不是全局 colors.border
                .background(if (CCMTheme.isDark) Color(0x1AFFFFFF) else Color(0x261F1F1E)),
        )
        Spacer(Modifier.height(6.44.dp))

        // ── 4. 技能（有子菜单）──────────────────────────────────────
        PlusMenuItem(
            iconRes = R.drawable.ic_pm_skills,
            label = "技能",
            trailingChevron = true,
            // 【2026-10-06 修】同「添加到项目」—— onEnter 不再抢占 onClick 的 toggle
            onEnter = { if (submenu != SubmenuKind.SKILLS) submenu = null },
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

        // 【2026-10-11 删】原来这里有个一级的「管理技能」—— 但 Web 里
        // 「管理技能」在**技能子菜单内**（技能列表 → 分隔线 → 管理技能），
        // 而 SkillsSubmenu 已经实现了它。这里再放一个是一级菜单里出现
        // 两次同名项（打开子菜单能看到两个「管理技能」）。

        // ── 分割线 2 ────────────────────────────────────────────────
        Spacer(Modifier.height(6.44.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 7.36.dp)
                .height(1.dp)
                // 分隔线色也是 Web 硬编码的 `bg-[rgba(31,31,30,0.15)]`
                // （暗色 `white/10`），不是全局 colors.border
                .background(if (CCMTheme.isDark) Color(0x1AFFFFFF) else Color(0x261F1F1E)),
        )
        Spacer(Modifier.height(6.44.dp))

        // ── 5. 添加连接器 ───────────────────────────────────────────
        PlusMenuItem(
            iconRes = R.drawable.ic_pm_connectors,
            label = "添加连接器",
            onEnter = { submenu = null },
            onClick = { onDismiss(); onConnectors() },
        )

        // ── 结尾分隔线 ──────────────────────────────────────────────
        // Web 的最后一项之后还有一条（`mx-[8px] my-[7px]`）—— 它的作用
        // 是给最后一项与容器底边之间留出呼吸感（pb-px 只有 1px）。
        Spacer(Modifier.height(6.44.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 7.36.dp)
                .height(1.dp)
                // 分隔线色也是 Web 硬编码的 `bg-[rgba(31,31,30,0.15)]`
                // （暗色 `white/10`），不是全局 colors.border
                .background(if (CCMTheme.isDark) Color(0x1AFFFFFF) else Color(0x261F1F1E)),
        )
        Spacer(Modifier.height(6.44.dp))
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
    // hover / 按压背景 —— 对齐 Web `hover:bg-[#F5F4F1] dark:hover:bg-white/5`。
    // ⚠️ 又是**硬编码值**，不是全局 `colors.hover`（= #EFEEEB，CSS :root 的
    // `--hover`，给侧栏之类用的）—— 菜单项的悬停色比它浅一档。
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(29.44.dp)
            .clip(RoundedCornerShape(7.36.dp))
            .background(
                when {
                    pressed -> if (CCMTheme.isDark) Color(0x1AFFFFFF) else Color(0xFFEFEEEB)
                    hovered -> if (CCMTheme.isDark) Color(0x0DFFFFFF) else Color(0xFFF5F4F1)
                    else -> Color.Transparent
                }
            )
            .clickable(interactionSource = interaction, indication = null) {
                onEnter(); onClick()
            }
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
    // 【2026-10-11 完整还原】原来是「浅灰底 + 小圆角」的内联块 —— 但 Web 的
    // 子菜单是**与主菜单完全相同的独立卡片**（`plusMenuSubmenuClass`）：
    //   w-[218px] rounded-[12px] border bg-white px-[7px] pb-px pt-[7px] shadow
    // 即：同样的白底、同样的深色描边、同样的 2px 阴影、同样的内边距。
    //
    // 手机屏幕窄（393dp），Web 的「悬浮在右侧」（`left-full ml-2`）会超出屏幕，
    // 所以这里**内联展开**（位置妥协），但**视觉必须一致**（样式不妥协）——
    // 之前只做了位置妥协，把样式也一起简化了，那是错的。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 200.dp)
            .shadow(2.dp, RoundedCornerShape(11.04.dp))
            .clip(RoundedCornerShape(11.04.dp))
            .background(colors.input)
            .border(
                1.dp,
                if (CCMTheme.isDark) Color(0x26FFFFFF) else Color(0x4D1F1F1E),
                RoundedCornerShape(11.04.dp),
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 6.44.dp)
            .padding(top = 6.44.dp, bottom = 1.dp),
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
        // Web: 项目列表 → 分隔线 → 新建项目
        Spacer(Modifier.height(6.44.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 7.36.dp)
                .height(1.dp)
                .background(if (CCMTheme.isDark) Color(0x1AFFFFFF) else Color(0x261F1F1E)),
        )
        Spacer(Modifier.height(6.44.dp))
        SubmenuRow(label = "新建项目", onClick = onCreate)
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
    // 【2026-10-11 完整还原】原来是「浅灰底 + 小圆角」的内联块 —— 但 Web 的
    // 子菜单是**与主菜单完全相同的独立卡片**（`plusMenuSubmenuClass`）：
    //   w-[218px] rounded-[12px] border bg-white px-[7px] pb-px pt-[7px] shadow
    // 即：同样的白底、同样的深色描边、同样的 2px 阴影、同样的内边距。
    //
    // 手机屏幕窄（393dp），Web 的「悬浮在右侧」（`left-full ml-2`）会超出屏幕，
    // 所以这里**内联展开**（位置妥协），但**视觉必须一致**（样式不妥协）——
    // 之前只做了位置妥协，把样式也一起简化了，那是错的。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 200.dp)
            .shadow(2.dp, RoundedCornerShape(11.04.dp))
            .clip(RoundedCornerShape(11.04.dp))
            .background(colors.input)
            .border(
                1.dp,
                if (CCMTheme.isDark) Color(0x26FFFFFF) else Color(0x4D1F1F1E),
                RoundedCornerShape(11.04.dp),
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 6.44.dp)
            .padding(top = 6.44.dp, bottom = 1.dp),
    ) {
        if (skills.isEmpty()) {
            Text(
                "暂无已启用技能",
                style = CCMText.body13.copy(fontSize = 12.sp),
                color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = 7.36.dp, vertical = 5.52.dp),
            )
        } else {
            skills.forEach { (id, name) ->
                SubmenuRow(label = name, onClick = { onPick(id) })
            }
        }
        // Web: 技能列表 → 分隔线 → 管理技能
        Spacer(Modifier.height(6.44.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 7.36.dp)
                .height(1.dp)
                .background(if (CCMTheme.isDark) Color(0x1AFFFFFF) else Color(0x261F1F1E)),
        )
        Spacer(Modifier.height(6.44.dp))
        SubmenuRow(label = "管理技能", onClick = onManage)
    }
}

@Composable
private fun SubmenuRow(
    label: String,
    onClick: () -> Unit,
) {
    val colors = CCMTheme.colors
    // 【2026-10-11 对齐 Web】子菜单项的 class 是：
    //   `flex h-[32px] w-full items-center rounded-[8px] px-[8px]
    //    text-[14px] hover:bg-[#F5F4F1]`
    // —— 与**主菜单项完全相同**（h-32 / rounded-8 / px-8 / text-14）。
    //
    // 原来是 h-29.44 / rounded-5.52 / px-11.04 / text-12sp —— 全都不一样，
    // 看起来像"另一种控件"。实际只是同一个按钮的两种尺寸，不该有差异。
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(29.44.dp)                                        // h-[32px] × .92
            .clip(RoundedCornerShape(7.36.dp))                       // rounded-[8px]
            .background(
                when {
                    pressed -> if (CCMTheme.isDark) Color(0x1AFFFFFF) else Color(0xFFEFEEEB)
                    hovered -> if (CCMTheme.isDark) Color(0x0DFFFFFF) else Color(0xFFF5F4F1)
                    else -> Color.Transparent
                }
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 7.36.dp),                          // px-[8px]
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = CCMText.body14.copy(                             // text-[14px]
                fontSize = 12.88.sp,
                lineHeight = 18.4.sp,
            ),
            color = colors.textMain,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
