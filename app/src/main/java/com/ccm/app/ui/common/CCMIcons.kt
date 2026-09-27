package com.ccm.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ccm.app.R

/**
 * CCM 图标集 —— 从 Web 版 `web/src/components/Icons.tsx` 搬运。
 *
 * ## 三类图标，三种实现
 *
 * | 来源 | 数量 | 实现方式 |
 * |---|---|---|
 * | PNG 位图（`src/assets/icons/*.png`） | 17 | 已复制到 `res/drawable/ic_*.png`，用 [PainterIcon] |
 * | SVG 描边（Icons.tsx 内联） | 7 | [CCMIcons] 的 ImageVector |
 * | lucide-react 图标 | 按需 | 手工重建（见 [LucideIcons]） |
 *
 * ## 为什么 PNG 直接搬而不是转成 Vector
 * Web 用的就是这些 PNG（110×110 等，显示时缩放到 20~24dp）。
 * 转成 VectorDrawable 会引入重绘误差，破坏"像素级零变化"。
 * 直接搬原图，缩放算法由 Android 与浏览器各自处理 —— 差异在 diff 容差内。
 *
 * ## 命名映射（res 资源名不能含连字符）
 * `sidebar-toggle.png` → `ic_sidebar_toggle.png` → `R.drawable.ic_sidebar_toggle`
 */
object CCMIcons {

    // ══ 一、PNG 位图图标 ══════════════════════════════════════════════
    // 显示尺寸对齐 Web 的 size 参数（默认 20，侧栏切换 24）

    /** `Icons.tsx: IconSidebarToggle` —— 侧栏折叠图标（**桌面端**用，移动端换 Menu） */
    val SidebarToggle = R.drawable.ic_sidebar_toggle

    /** `Icons.tsx: IconPlusCircle` —— 新建对话（110×110 源图） */
    val PlusCircle = R.drawable.ic_new_chat

    /** `Icons.tsx: IconCode` */
    val Code = R.drawable.ic_code

    /** `Sidebar.tsx` 的搜索入口 */
    val Search = R.drawable.ic_search

    /** `Sidebar.tsx` 的 Customize 入口 */
    val Customize = R.drawable.ic_customize

    /** `ProjectsPage.tsx` 的「开始项目」插图 */
    val StartProjects = R.drawable.ic_start_projects

    /** `CustomizePage.tsx` 的四个插图 */
    val Skills = R.drawable.ic_skills
    val Connectors = R.drawable.ic_connectors
    val CustomizeMain = R.drawable.ic_customize_main
    val CreateSkills = R.drawable.ic_create_skills

    /** Claude logo（300×300，Canvas 动画的静态版） */
    val ClaudeLogo = R.drawable.ic_claude_logo

    /** Claude 字标（334×106，横排文字 logo） */
    val ClaudeWordmark = R.drawable.ic_claude_wordmark

    /** 导航图标 */
    val Chats = R.drawable.ic_chats
    val Projects = R.drawable.ic_projects
    val Artifacts = R.drawable.ic_artifacts

    /** 其他 */
    val WebSearch = R.drawable.ic_web_search
    val ConnectTools = R.drawable.ic_connect_tools
}

// ══ 二、SVG 描边图标（ImageVector）════════════════════════════════════

/**
 * 内联 SVG 图标 —— 逐字搬运 `Icons.tsx` 的 path。
 *
 * ## 为什么手写 ImageVector 而不是用 Android Studio 的导入工具
 * 导入工具会把 path 重新优化（合并、精度调整），可能改变渲染结果。
 * 这里**原样保留** `d` 属性里的坐标精度，确保与 Web 一致。
 *
 * ## 默认尺寸
 * Web 的默认 `size = 20`，部分为 24。Compose 的 [Icon] 默认 24dp，
 * 使用时请显式传 [Icon] 的 `modifier = Modifier.size(20.dp)`。
 */
object SvgIcons {

    /**
     * `Icons.tsx: IconPlus` —— 通用加号（24×24 viewBox）。
     *
     * 原文：`<line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>`
     * 描边样式：`stroke="currentColor" strokeWidth="2" strokeLinecap="round"`
     */
    val Plus: ImageVector by lazy {
        ImageVector.Builder(
            name = "Plus",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = androidx.compose.ui.graphics.SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
            ) {
                // 竖线
                moveTo(12f, 5f); lineTo(12f, 19f)
                // 横线
                moveTo(5f, 12f); lineTo(19f, 12f)
            }
        }.build()
    }

    /**
     * `lucide-react: Menu` —— 汉堡菜单（移动端顶栏用，size=20）。
     *
     * lucide 标准实现：三条水平线，viewBox 24×24，strokeWidth 2，round cap。
     */
    val Menu: ImageVector by lazy {
        ImageVector.Builder(
            name = "Menu",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = androidx.compose.ui.graphics.SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(4f, 6f); lineTo(20f, 6f)
                moveTo(4f, 12f); lineTo(20f, 12f)
                moveTo(4f, 18f); lineTo(20f, 18f)
            }
        }.build()
    }

    /**
     * `lucide-react: ArrowLeft` —— 后退。
     *
     * ⚠️ Web 用 `strokeWidth={1.5}`（不是默认的 2），见 `App.tsx:717`。
     * lucide 的 ArrowLeft：`M19 12H5` + `m12 19-7-7 7-7`
     */
    val ArrowLeft: ImageVector by lazy {
        ImageVector.Builder(
            name = "ArrowLeft",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = androidx.compose.ui.graphics.SolidColor(Color.Black),
                strokeLineWidth = 1.5f,      // ← Web 显式指定
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(19f, 12f); lineTo(5f, 12f)
                moveTo(12f, 19f); lineTo(5f, 12f); lineTo(12f, 5f)
            }
        }.build()
    }

    /**
     * `lucide-react: ArrowRight` —— 前进。同样 `strokeWidth={1.5}`。
     */
    val ArrowRight: ImageVector by lazy {
        ImageVector.Builder(
            name = "ArrowRight",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = androidx.compose.ui.graphics.SolidColor(Color.Black),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(5f, 12f); lineTo(19f, 12f)
                moveTo(12f, 5f); lineTo(19f, 12f); lineTo(12f, 19f)
            }
        }.build()
    }
}

// ══ 三、渲染辅助 ══════════════════════════════════════════════════════

/**
 * 渲染 PNG 图标（`@DrawableRes`）。
 *
 * ```kotlin
 * PainterIcon(CCMIcons.Search, size = 20.dp, tint = CCMTheme.colors.textSecondary)
 * ```
 *
 * @param resId  drawable 资源
 * @param size   显示尺寸。Web 默认 20，侧栏切换 24
 * @param tint   着色。传 [Color.Unspecified] 保留原图颜色（彩色插图用）
 */
@Composable
fun PainterIcon(
    resId: Int,
    size: Dp = 20.dp,
    tint: Color = Color.Unspecified,
) {
    Image(
        painter = painterResource(resId),
        contentDescription = null,
        modifier = Modifier.size(size),
        colorFilter = if (tint == Color.Unspecified) null else ColorFilter.tint(tint),
    )
}

/**
 * 渲染 [ImageVector] 图标，自动套用 Material3 [Icon] 的 tint 行为。
 *
 * 用法对齐 Web 的 `<Icon size={20} className="opacity-80" />`：
 * ```kotlin
 * VectorIcon(SvgIcons.Menu, size = 20.dp, tint = colors.textSecondary)
 * ```
 */
@Composable
fun VectorIcon(
    imageVector: ImageVector,
    size: Dp = 20.dp,
    tint: Color = Color.Unspecified,
) {
    Icon(
        imageVector = imageVector,
        contentDescription = null,
        modifier = Modifier.size(size),
        tint = tint,
    )
}

/**
 * 用 [Canvas] 画一个简单的描边圆 —— 供测试/占位使用。
 * 真实图标请用上面的 [PainterIcon] / [VectorIcon]。
 */
@Composable
fun DebugCircle(color: Color, size: Dp = 20.dp) {
    Canvas(modifier = Modifier.size(size)) {
        drawCircle(
            color = color,
            radius = this.size.minDimension / 2f,
            style = Stroke(width = 1.5f),
            center = Offset(this.size.width / 2f, this.size.height / 2f),
        )
    }
}
