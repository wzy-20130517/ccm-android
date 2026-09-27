package com.ccm.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import com.ccm.app.ui.common.SvgIcons

/**
 * 设置页 —— 对齐 Web `SettingsPage.tsx`（1056 行）。
 *
 * ## ★ 移动端形态与桌面完全不同（index.css:1266-1290）
 *
 * ```css
 * @media (max-width: 767px) {
 *   .settings-shell { flex-direction: column !important; }     // 横向 → 纵向
 *   .settings-nav {
 *     width: 100% !important;
 *     flex-direction: row !important;        // 竖列 → 横向 tab 条
 *     overflow-x: auto !important;           // 可横滚
 *     gap: 4px !important;
 *     padding: clamp(5px, 2vw, 10px) clamp(7px, 2.6vw, 14px) !important;
 *     border-bottom: 1px solid var(--claude-border);
 *   }
 *   .settings-nav > h1, .settings-nav > h2 { display: none !important; }  // 隐藏 "Settings" 标题
 *   .settings-content { width: 100% !important; }
 *   .settings-body {
 *     padding: clamp(10px, 3.6vw, 18px) clamp(9px, 3.4vw, 17px) clamp(56px, 18vw, 90px) !important;
 *   }
 * }
 * ```
 *
 * **所以移动端是**：
 * ```
 * ┌─ 横向 tab 条（General | Models | 环境 …），可横滚，底部有分割线
 * ├─ 内容区（占满宽度）
 * └─ 底部大留白（clamp(56, 18vw, 90) = 70.7dp，给底部手势区）
 * ```
 *
 * ## 本机实测值（393 视口，Playwright）
 * | 属性 | clamp 表达式 | 实测 | 屏幕值（×0.92） |
 * |---|---|---|---|
 * | nav 内边距 | `clamp(5,2vw,10)` / `clamp(7,2.6vw,14)` | 7.86 / 10.218 | **7.23 / 9.40** |
 * | nav 间距 | 4px | 4 | **3.68** |
 * | nav 高度 | — | 47.8 | **43.98** |
 * | body 内边距 | `clamp(10,3.6vw,18)` / `clamp(9,3.4vw,17)` | 14.148 / 13.362 | **13.02 / 12.29** |
 * | body 底部 | `clamp(56,18vw,90)` | 70.74 | **65.08** |
 *
 * ## 章节（源码 `tab` 状态）
 * | tab | 标题 | 可见条件 |
 * |---|---|---|
 * | general | General | 总是 |
 * | models | Models | `selfHosted` |
 * | account | Account | 非 selfHosted |
 * | usage | Usage | 非 selfHosted |
 * | environment | 环境 | `/api/ccm/status` 返回 `ccm:true` |
 *
 * @param initialTab  初始 tab
 * @param ccmAvailable CCM 原生外壳可用（决定「环境」tab 是否出现）
 * @param selfHosted  自部署模式（决定 Models/Account/Usage 三选）
 * @param onClose     关闭设置（Web 里设置是覆盖层，不是路由）
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    initialTab: SettingsTab = SettingsTab.GENERAL,
    ccmAvailable: Boolean = true,
    selfHosted: Boolean = true,
    onClose: () -> Unit = {},
) {
    val colors = CCMTheme.colors
    var tab by remember { mutableStateOf(initialTab) }

    // 可见的 tab（按源码的条件渲染规则）
    val tabs = buildList {
        add(SettingsTab.GENERAL)
        if (selfHosted) add(SettingsTab.MODELS) else add(SettingsTab.ACCOUNT)
        if (!selfHosted) add(SettingsTab.USAGE)
        if (ccmAvailable) add(SettingsTab.ENVIRONMENT)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgMain),
    ) {
        // ── 横向 tab 条（移动端形态）──────────────────────────────────
        //
        // ★ 2026-09-27 加关闭按钮（用户报「所有按钮点不动、侧边栏都点不开」的真因）：
        //   设置是**全屏覆盖层**（CcmApp.kt:316 用 fillMaxSize 盖住整个界面），
        //   而本函数声明了 `onClose` 却**从未调用** —— 没有任何 UI 元素挂它。
        //   加上全项目 0 处 BackHandler（系统返回键也不管用），
        //   结果就是：**一旦进了设置页就出不来**，整个 App 看起来「死了」。
        //   实测用户路径：点侧栏搜索 / 点首页提示条 → 进设置页 → 被困。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.bgMain)
                .padding(start = 4.dp, end = 9.40.dp, top = 7.23.dp, bottom = 7.23.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 关闭（返回）按钮 —— 40×40，对齐 TitleBar 的按钮规格
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    imageVector = SvgIcons.ArrowLeft,
                    contentDescription = "关闭设置",
                    modifier = Modifier.size(18.dp),
                    tint = colors.textMain,
                )
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(3.68.dp),
            ) {
                tabs.forEach { t ->
                    SettingsTabButton(
                        label = t.label,
                        active = t == tab,
                        onClick = { tab = t },
                    )
                }
            }
        }

        // tab 条底部分割线
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.border),
        )

        // ── 内容区 ────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = SettingsBodyPaddingH,
                    end = SettingsBodyPaddingH,
                    top = SettingsBodyPaddingTop,
                    bottom = SettingsBodyPaddingBottom,
                ),
        ) {
            when (tab) {
                SettingsTab.GENERAL -> SettingsGeneralTab()
                SettingsTab.MODELS -> SettingsModelsTab()
                SettingsTab.ACCOUNT -> SettingsAccountTab()
                SettingsTab.USAGE -> SettingsUsageTab()
                SettingsTab.ENVIRONMENT -> SettingsEnvironmentTab()
            }
        }
    }
}

/**
 * 设置 tab 按钮 —— 对齐源码：
 * `text-left px-3 py-2 rounded-lg text-[15px] font-medium`
 *
 * 激活态用 `bg-claude-btn-hover`（**不是** hover！两者在亮色下不同：
 * btnHover #EAE9E6 vs hover #EFEEEB）。
 *
 * 实测：65.7×32.3（General）/ 62.3×32.3（Models），padding 8px 12px →
 * 屏幕 **29.72 高 / 内距 7.36 × 11.04**。
 */
@Composable
private fun SettingsTabButton(label: String, active: Boolean, onClick: () -> Unit) {
    val colors = CCMTheme.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(7.36.dp))       // rounded-lg
            .background(if (active) colors.btnHover else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.04.dp, vertical = 7.36.dp),   // px-3 py-2
    ) {
        Text(
            text = label,
            // text-[15px] 移动端 clamp(12,3.25vw,15) → 实测 12.7725 → 屏幕 11.75
            style = CCMText.body15.copy(fontSize = 11.75.sp, fontWeight = FontWeight.Medium),
            color = if (active) colors.textMain else colors.textSecondary,
        )
    }
}

/** 设置页的 tab —— 对应源码的 `tab` 状态值 */
enum class SettingsTab(val key: String, val label: String) {
    GENERAL("general", "General"),
    MODELS("models", "Models"),
    ACCOUNT("account", "Account"),
    USAGE("usage", "Usage"),
    ENVIRONMENT("environment", "环境"),
}
