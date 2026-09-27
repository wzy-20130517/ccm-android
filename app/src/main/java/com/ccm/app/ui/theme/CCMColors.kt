package com.ccm.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * CCM 颜色 Token —— 1:1 映射 Web 版 CSS 变量。
 *
 * 数据源：`~/claude-code-mobile/web/src/index.css:7-41`（:root 与 .dark 两套）
 *
 * ## 为什么不用 Material3 的 ColorScheme
 * Web 版的配色不是 Material 体系，硬套会引入 Material 的默认语义色（primary/secondary/
 * surfaceVariant…）导致颜色对不上。这里保持与 CSS 变量一一对应，Material3 只用来拿
 * 组件的行为（涟漪、无障碍），颜色一律从 [CCMColors] 取。
 *
 * ## 两个易踩的坑
 * 1. `hover` 与 `btnHover` **亮色下不同**（#EFEEEB vs #EAE9E6），暗色下才相同（都是 #121212）。
 *    别为了"简化"把它们合并。
 * 2. `textMain` 与 `textModelBody` **暗色下不同**（#FFFFFF vs #EDEAE1）。
 *    模型输出的正文用 [textModelBody]（暖白），UI 文字用 [textMain]（纯白）。
 */
@Immutable
data class CCMColors(
    // ── 14 个核心 token（与 index.css:7-41 严格一一对应）────────────────
    /** `--bg-claude-main` 主背景 / 页面底色 */
    val bgMain: Color,
    /** `--bg-claude-sidebar` 侧栏背景 */
    val bgSidebar: Color,
    /** `--border-claude` 边框、分割线 */
    val border: Color,
    /** `--text-claude-main` 主文字 */
    val textMain: Color,
    /** `--text-claude-model-body` 模型正文专用（暗色下比 [textMain] 偏暖） */
    val textModelBody: Color,
    /** `--text-claude-secondary` 次要文字、说明、占位符 */
    val textSecondary: Color,
    /** `--bg-claude-accent` 品牌主色（明暗相同） */
    val accent: Color,
    /** `--bg-claude-hover` hover 底色（亮色下 ≠ [btnHover]） */
    val hover: Color,
    /** `--bg-claude-btn-hover` 按钮 hover 底色 */
    val btnHover: Color,
    /** `--bg-claude-input` 输入框 / 代码块底 */
    val input: Color,
    /** `--bg-claude-avatar` 头像底（明暗反转） */
    val avatarBg: Color,
    /** `--text-claude-avatar` 头像文字（明暗反转） */
    val avatarText: Color,
    /** `--bg-mode-tabs` 模式标签容器 */
    val modeTabs: Color,
    /** `--bg-mode-tab-active` 选中的模式标签 */
    val modeTabActive: Color,

    // ── 扩展语义色（来自实测硬编码值，见 MEASURED.md §6）─────────────────
    /** 实测：`h1` 标题与 `textarea` 输入文字色 `rgb(55,55,52)` */
    val textTitle: Color,
    /** 成功绿（工具执行成功、签到成功等） */
    val success: Color,
    /** 错误红 */
    val error: Color,
    /** 蓝色强调（链接、研究标记） */
    val blueAccent: Color,
    /** Claude 品牌橙（与 [accent] 是两套，别混用） */
    val claudeOrange: Color,

    // ── 侧栏文字（index.css:274-291 的特殊覆盖）──────────────────────
    /**
     * 侧栏文字色。
     *
     * Web 用 `html.dark .bg-claude-sidebar { color: rgb(190,189,180) !important }`
     * 在暗色下强制覆盖成暖灰；亮色下不覆盖，等于 [textMain]。
     * 两套值已分别写进 [LightCCMColors] / [DarkCCMColors]，直接用即可。
     */
    val sidebarText: Color,

    // ── 遮罩（模态背景）──────────────────────────────────────────────
    /** `bg-black/40` */
    val scrim40: Color,
    /** `bg-black/50` */
    val scrim50: Color,
    /** `bg-black/60` */
    val scrim60: Color,
    /** `bg-black/80` */
    val scrim80: Color,
) {
    /** 侧栏文字 hover 色 —— Web 里是 `#FFFFFF !important`（明暗两套相同） */
    val sidebarTextHover: Color get() = Color.White
}

/** 亮色主题 —— 对应 CSS `:root`（index.css:7-22） */
val LightCCMColors = CCMColors(
    bgMain = Color(0xFFF8F8F6),
    bgSidebar = Color(0xFFF7F7F4),
    border = Color(0xFFE8E7E3),
    textMain = Color(0xFF2A2A2A),
    textModelBody = Color(0xFF2A2A2A),
    textSecondary = Color(0xFF666666),
    accent = Color(0xFFCC7C5E),
    hover = Color(0xFFEFEEEB),
    btnHover = Color(0xFFEAE9E6),
    input = Color(0xFFFFFFFF),
    avatarBg = Color(0xFF333333),
    avatarText = Color(0xFFFFFFFF),
    modeTabs = Color(0xFFEFEEEB),
    modeTabActive = Color(0xFFF8F8F6),

    textTitle = Color(0xFF373734),
    success = Color(0xFF4B9C68),
    error = Color(0xFFB9382C),
    blueAccent = Color(0xFF387EE0),
    claudeOrange = Color(0xFFD97757),

    // 亮色下 Web 未覆盖侧栏文字色 → 等于 textMain
    sidebarText = Color(0xFF2A2A2A),

    scrim40 = Color(0x66000000),
    scrim50 = Color(0x80000000),
    scrim60 = Color(0x99000000),
    scrim80 = Color(0xCC000000),
)

/** 暗色主题 —— 对应 CSS `.dark`（index.css:24-40） */
val DarkCCMColors = CCMColors(
    bgMain = Color(0xFF1F1F1E),
    bgSidebar = Color(0xFF1E1E1C),
    border = Color(0xFF444341),
    textMain = Color(0xFFFFFFFF),
    textModelBody = Color(0xFFEDEAE1),
    textSecondary = Color(0xFFABA499),
    accent = Color(0xFFCC7C5E),
    hover = Color(0xFF121212),
    btnHover = Color(0xFF121212),
    input = Color(0xFF30302E),
    avatarBg = Color(0xFFD4D4D4),
    avatarText = Color(0xFF222222),
    modeTabs = Color(0xFF121212),
    modeTabActive = Color(0xFF1F1F1E),

    // 暗色下标题沿用亮色标题色（Web 未单独覆盖 h1）
    textTitle = Color(0xFFEDEAE1),
    success = Color(0xFF4B9C68),
    error = Color(0xFFB9382C),
    blueAccent = Color(0xFF387EE0),
    claudeOrange = Color(0xFFD97757),

    // index.css:275 —— html.dark .bg-claude-sidebar { color: rgb(190,189,180) }
    sidebarText = Color(0xFFBEBDB4),

    scrim40 = Color(0x66000000),
    scrim50 = Color(0x80000000),
    scrim60 = Color(0x99000000),
    scrim80 = Color(0xCC000000),
)
