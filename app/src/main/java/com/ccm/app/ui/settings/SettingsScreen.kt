package com.ccm.app.ui.settings

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme

/**
 * 设置页 —— 对齐 Web `SettingsPage.tsx`（1056 行）。
 *
 * ## ★ 移动端形态与桌面完全不同（index.css:1266-1290）
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
 *   .settings-nav > h1, .settings-nav > h2 { display: none !important; }  // ★ 隐藏 "Settings" 标题
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
 * ## 本机实测值（393 视口）
 * | 属性 | clamp 表达式 | 屏幕值（×0.92） |
 * |---|---|---|
 * | nav 内边距 | `clamp(5,2vw,10)` / `clamp(7,2.6vw,14)` | 7.86 / 9.41 → **7.23 / 8.66** |
 * | nav 间距 | 4px | **3.68** |
 * | body 内边距 | `clamp(10,3.6vw,18)` / `clamp(9,3.4vw,17)` | 14.148 / 13.362 → **13.02 / 12.29** |
 * | body 底部 | `clamp(56,18vw,90)` | 70.74 → **65.08** |
 *
 * ## 章节（源码 `tab` 状态）
 * | tab | 标题 | 说明 |
 * |---|---|---|
 * | general | General | 账号 / 个人资料 / 默认模型 / 发送消息 / 外观 / 关于 / 使用量 |
 * | models | Models | 仅 `user_mode === 'selfhosted'` 时显示 |
 * | account | Account | 仅非 selfhosted |
 * | usage | Usage | 仅非 selfhosted |
 * | environment | 环境 | 仅 CCM 原生外壳（`/api/ccm/status` 返回 ccm:true） |
 *
 * @param initialTab  初始 tab
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .background(colors.bgMain)
                .padding(horizontal = 8.66.dp, vertical = 7.23.dp),
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
                    start = 12.29.dp,
                    end = 12.29.dp,
                    top = 13.02.dp,
                    bottom = 65.08.dp,      // clamp(56,18vw,90) × 0.92
                ),
        ) {
            when (tab) {
                SettingsTab.GENERAL -> GeneralSettings()
                SettingsTab.MODELS -> PlaceholderSection("模型", "Provider 与模型配置（对应 CLI 的 /config）")
                SettingsTab.ACCOUNT -> PlaceholderSection("账号", "登录态与账户信息")
                SettingsTab.USAGE -> PlaceholderSection("用量", "token 消耗统计")
                SettingsTab.ENVIRONMENT -> PlaceholderSection("环境", "CCM 原生外壳的运行状态")
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
            // text-[15px] → 实测 11.75dp
            style = CCMText.body15.copy(fontWeight = FontWeight.Medium),
            color = if (active) colors.textMain else colors.textSecondary,
        )
    }
}

/**
 * General 章节 —— 对齐 `renderGeneral()`（源码 7 个小节）。
 *
 * 每节：`<section>` + `<h3 text-[16px] font-semibold mb-5>` + 内容。
 */
@Composable
private fun GeneralSettings() {
    Column(verticalArrangement = Arrangement.spacedBy(36.8.dp)) {   // space-y-10
        SettingsSection(title = "账号") {
            LabeledValue(label = "邮箱地址", value = "-")
        }
        SettingsSection(title = "个人资料") {
            LabeledValue(label = "显示名称", value = "-")
        }
        SettingsSection(title = "默认模型") {
            LabeledValue(label = "模型", value = "Sonnet 4.6")
        }
        SettingsSection(title = "发送消息") {
            LabeledValue(label = "回车行为", value = "发送消息")
        }
        SettingsSection(title = "外观") {
            LabeledValue(label = "主题", value = "跟随系统")
        }
        SettingsSection(title = "关于") {
            LabeledValue(label = "版本", value = "0.1.0")
        }
        SettingsSection(title = "使用量") {
            LabeledValue(label = "本月 token", value = "-")
        }
    }
}

/** 设置小节 —— `<h3 text-[16px] font-semibold mb-5>` + 内容 */
@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    val colors = CCMTheme.colors
    Column {
        Text(
            text = title,
            // text-[16px] font-semibold → 实测 12.29dp
            style = CCMText.body16.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textMain,
        )
        Spacer(Modifier.height(18.4.dp))         // mb-5
        content()
    }
}

/**
 * 标签 + 值 —— 对齐源码：
 * `<label block text-[13px] font-medium text-claude-textSecondary mb-1.5>` +
 * `<div text-[14px] text-claude-text>`
 */
@Composable
private fun LabeledValue(label: String, value: String) {
    val colors = CCMTheme.colors
    Column {
        Text(
            text = label,
            style = CCMText.body13.copy(fontWeight = FontWeight.Medium),
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(5.52.dp))         // mb-1.5
        Text(
            text = value,
            style = CCMText.body14,
            color = colors.textMain,
        )
    }
}

/** 占位小节（待实现） */
@Composable
private fun PlaceholderSection(title: String, description: String) {
    val colors = CCMTheme.colors
    SettingsSection(title = title) {
        Text(
            text = description,
            style = CCMText.body13,
            color = colors.textSecondary,
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
