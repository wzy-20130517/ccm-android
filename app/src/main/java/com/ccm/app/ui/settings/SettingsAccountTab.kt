package com.ccm.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * 设置页 · Account tab —— 对齐 `SettingsPage.tsx:270 renderAccount()`。
 *
 * 结构（源码顺序）：
 *   1. 邮箱地址（只读展示）+ 右侧「修改密码 / 注销账号」
 *   2. 登录设备表（设备 / 位置 / 登录时间 / 最近活动 / 操作）
 *   3. 修改密码弹窗 + 注销账号弹窗
 *
 * ## 移动端实测
 * 源码的 `.flex.items-center.justify-between` 被 CSS 改成**纵向堆叠**，
 * 但带 `> button:only-child` 或含 `input[type=checkbox]` 的例外 ——
 * 「邮箱 + 两个操作按钮」这行含 2 个按钮，所以**是纵向的**。
 */
@Composable
fun SettingsAccountTab(modifier: Modifier = Modifier) {
    val colors = CCMTheme.colors
    var showPwdForm by remember { mutableStateOf(false) }
    var showDeleteAccount by remember { mutableStateOf(false) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(SettingsSectionGap)) {
        SettingsSection(title = "账号") {
            Column(verticalArrangement = Arrangement.spacedBy(18.4.dp)) {   // space-y-5
                Column {
                    SettingsLabel("邮箱地址")
                    Spacer(Modifier.height(SettingsLabelGap))
                    Text(
                        text = "3843364195@qq.com",
                        style = CCMText.body14.copy(fontSize = SettingsBody14Sp),
                        color = colors.textMain,
                    )
                    Spacer(Modifier.height(14.72.dp))       // mt-4
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.72.dp),   // gap-4
                    ) {
                        Text(
                            text = "修改密码",
                            style = CCMText.body13.copy(fontSize = SettingsLabelSizeSp),
                            color = colors.textSecondary,
                            modifier = Modifier.clickable { showPwdForm = true },
                        )
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(2.76.dp)            // h-3
                                .background(colors.border),
                        )
                        Text(
                            text = "注销账号",
                            style = CCMText.body13.copy(fontSize = SettingsLabelSizeSp),
                            color = Color(0xFFB9382C),
                            modifier = Modifier.clickable { showDeleteAccount = true },
                        )
                    }
                }

                // 登录设备列表（源码是 table，移动端横向挤压 → 改为纵向卡片）
                SettingsLabel("登录设备")
                Column(verticalArrangement = Arrangement.spacedBy(7.36.dp)) {
                    SessionRow(
                        device = "REDMI Note 15 Pro",
                        location = "江西 景德镇",
                        created = "Sep 27, 2026, 10:12 AM",
                        lastActive = "Sep 27, 2026, 11:04 AM",
                        isCurrent = true,
                    )
                }
                Text(
                    text = "No active sessions",
                    style = CCMText.body13.copy(fontSize = SettingsLabelSizeSp),
                    color = colors.textSecondary,
                    modifier = Modifier.padding(vertical = 3.68.dp),
                )
            }
        }
    }

    if (showPwdForm) {
        CcmPasswordDialog(onDismiss = { showPwdForm = false })
    }
    if (showDeleteAccount) {
        CcmDeleteAccountDialog(onDismiss = { showDeleteAccount = false })
    }
}

/**
 * 设备行 —— 源码是表格 `<tr>`（设备/位置/登录时间/最近活动/操作），
 * 移动端表格会被压成极窄列，这里改为纵向卡片。
 *
 * 源码里 `s.id === currentSessionId` 时显示 "Current" 徽章：
 * `<span className="ml-1 text-[11px] px-1.5 py-0.5 rounded-sm bg-neutral-200">`
 * 实测字号 `text-[11px]` → 屏幕 10.12，padding 1.38×5.52，圆角 1.84。
 */
@Composable
private fun SessionRow(
    device: String,
    location: String,
    created: String,
    lastActive: String,
    isCurrent: Boolean,
) {
    val colors = CCMTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.52.dp))
            .background(colors.input)
            .border(1.dp, colors.border, RoundedCornerShape(5.52.dp))
            .padding(11.04.dp),
        verticalArrangement = Arrangement.spacedBy(3.68.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = device,
                style = CCMText.body13.copy(
                    fontSize = SettingsLabelSizeSp,
                    fontWeight = FontWeight.Medium,
                ),
                color = colors.textMain,
            )
            if (isCurrent) {
                Spacer(Modifier.width(3.68.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(1.84.dp))
                        .background(colors.hover)
                        .padding(horizontal = 5.52.dp, vertical = 1.84.dp),
                ) {
                    Text(
                        text = "Current",
                        style = CCMText.body11.copy(fontSize = 10.12.sp),
                        color = colors.textSecondary,
                    )
                }
            }
        }
        Text(
            text = location,
            style = CCMText.body13.copy(fontSize = SettingsLabelSizeSp),
            color = colors.textSecondary,
        )
        Text(
            text = "登录 $created",
            style = CCMText.body13.copy(fontSize = SettingsLabelSizeSp),
            color = colors.textSecondary,
        )
        Text(
            text = "最近活动 $lastActive",
            style = CCMText.body13.copy(fontSize = SettingsLabelSizeSp),
            color = colors.textSecondary,
        )
    }
}

/**
 * 修改密码弹窗 —— 对应源码
 * `<div className="fixed inset-0 z-[60] flex items-center justify-center p-4
 *   bg-black/50 backdrop-blur-sm"><div className="bg-white p-6 rounded-2xl
 *   w-full max-w-sm shadow-xl border border-claude-border">`
 *
 * 移动端：`w-[400px]` 类被覆盖成 `width:100%; max-width: calc(100vw - 20px)`
 * → 屏幕宽 373 × 0.92 = **343.16**，`p-6` = 22.08，`rounded-2xl` = 14.72。
 */
@Composable
private fun CcmPasswordDialog(onDismiss: () -> Unit) {
    val colors = CCMTheme.colors
    var current by remember { mutableStateOf("") }
    var newPwd by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    com.ccm.app.ui.common.CcmModal(onDismiss = onDismiss, cardWidth = 343.16.dp) {
        Text(
            text = "修改密码",
            style = CCMText.body18.copy(fontSize = 16.56.sp, fontWeight = FontWeight.SemiBold),
            color = colors.textMain,
        )
        Spacer(Modifier.height(14.72.dp))       // mb-4
        Column(verticalArrangement = Arrangement.spacedBy(11.04.dp)) {
            SettingsTextField(
                value = current,
                onValueChange = { current = it },
                placeholder = "当前密码",
            )
            SettingsTextField(
                value = newPwd,
                onValueChange = { newPwd = it },
                placeholder = "新密码（至少 6 位）",
            )
            SettingsTextField(
                value = confirm,
                onValueChange = { confirm = it },
                placeholder = "确认新密码",
            )
        }
        Spacer(Modifier.height(18.4.dp))        // pt-5
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(11.04.dp, Alignment.End),
        ) {
            com.ccm.app.ui.common.CcmSecondaryButton(
                label = "取消",
                onClick = onDismiss,
                modifier = Modifier.width(74.52.dp),      // px-4 × 2 + 文字宽
            )
            com.ccm.app.ui.common.CcmPrimaryButton(
                label = "更新密码",
                onClick = { },
                enabled = current.isNotEmpty() && newPwd.length >= 6 && newPwd == confirm,
                modifier = Modifier.width(90.16.dp),
            )
        }
    }
}

/**
 * 注销账号弹窗 —— 对应源码
 * `<div className="bg-white p-6 rounded-2xl w-full max-w-sm shadow-xl
 *   border border-red-200">`，标题 `text-[#B9382C]`。
 */
@Composable
private fun CcmDeleteAccountDialog(onDismiss: () -> Unit) {
    val colors = CCMTheme.colors
    var password by remember { mutableStateOf("") }

    com.ccm.app.ui.common.CcmModal(onDismiss = onDismiss, cardWidth = 343.16.dp) {
        Text(
            text = "注销账号",
            style = CCMText.body18.copy(fontSize = 16.56.sp, fontWeight = FontWeight.SemiBold),
            color = Color(0xFFB9382C),
        )
        Spacer(Modifier.height(7.36.dp))        // mb-2
        Text(
            text = "此操作不可撤销。您的所有数据将被永久删除。",
            style = CCMText.body14.copy(fontSize = SettingsBody14Sp),
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(14.72.dp))       // mb-4
        SettingsTextField(
            value = password,
            onValueChange = { password = it },
            placeholder = "输入密码以确认",
        )
        Spacer(Modifier.height(18.4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(11.04.dp, Alignment.End),
        ) {
            com.ccm.app.ui.common.CcmSecondaryButton(
                label = "取消",
                onClick = onDismiss,
                modifier = Modifier.width(74.52.dp),
            )
            com.ccm.app.ui.common.CcmPrimaryButton(
                label = "永久删除",
                onClick = { },
                enabled = password.isNotEmpty(),
                modifier = Modifier.width(90.16.dp),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Usage tab
// ─────────────────────────────────────────────────────────────

/**
 * 设置页 · Usage tab —— 对齐 `SettingsPage.tsx:853 renderUsage()`。
 *
 * 源码结构：
 *   1. token 额度条（`usage_percent` 决定颜色：>90 红 / >70 黄 / 否则橙）
 *   2. 存储空间条（非 Electron 才显示 —— 原生版恒显示）
 *   3. 计划卡片（plan）
 *   4. 消息统计（今天 / 本月）
 *
 * 进度条实测：`h-2` = 8 → 屏幕 **7.36**，`rounded-full` 全圆角，
 * 底色 `bg-claude-border`，填充色三档。
 */
@Composable
fun SettingsUsageTab(modifier: Modifier = Modifier) {
    val colors = CCMTheme.colors

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(SettingsSectionGap)) {
        SettingsSection(title = "用量") {
            Column(verticalArrangement = Arrangement.spacedBy(18.4.dp)) {

                // token 额度
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "额度",
                            style = CCMText.body13.copy(fontSize = SettingsLabelSizeSp),
                            color = colors.textMain,
                        )
                        Text(
                            text = "已使用 12.34%",
                            style = CCMText.body13.copy(fontSize = SettingsLabelSizeSp),
                            color = colors.textSecondary,
                        )
                    }
                    Spacer(Modifier.height(7.36.dp))        // mb-2
                    UsageBar(percent = 12.34f)
                }

                // 存储空间
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "存储空间",
                            style = CCMText.body13.copy(fontSize = SettingsLabelSizeSp),
                            color = colors.textMain,
                        )
                        Text(
                            text = "已使用 128.0 MB / 1.0 GB",
                            style = CCMText.body13.copy(fontSize = SettingsLabelSizeSp),
                            color = colors.textSecondary,
                        )
                    }
                    Spacer(Modifier.height(7.36.dp))
                    UsageBar(percent = 12.8f, fixedColor = Color(0xFFD97757))
                }

                // 计划卡片
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(11.04.dp))     // rounded-xl
                        .background(colors.input)
                        .border(1.dp, colors.border, RoundedCornerShape(11.04.dp))
                        .padding(11.04.dp),
                    verticalArrangement = Arrangement.spacedBy(3.68.dp),
                ) {
                    Text(
                        text = "免费版",
                        style = CCMText.body16.copy(
                            fontSize = 12.29.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = colors.textMain,
                    )
                    Text(
                        text = "当前计划",
                        style = CCMText.body12.copy(fontSize = 10.48.sp),
                        color = colors.textSecondary,
                    )
                }

                // 消息统计（两列）
                Row(horizontalArrangement = Arrangement.spacedBy(11.04.dp)) {
                    StatCard(value = "12", label = "今日消息", modifier = Modifier.weight(1f))
                    StatCard(value = "348", label = "本月消息", modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * 用量进度条 —— 对应源码
 * `<div className="h-2 bg-claude-border rounded-full overflow-hidden">
 *    <div className="h-full rounded-full transition-all duration-500 ease-out"
 *         style={{ width: `${Math.min(percent,100)}%`, backgroundColor: ... }}/>
 *  </div>`
 *
 * 三档颜色：>90% `#D93025` / >70% `#F9AB00` / 否则 `#D97757`。
 */
@Composable
private fun UsageBar(percent: Float, fixedColor: Color? = null) {
    val colors = CCMTheme.colors
    val fill = fixedColor ?: when {
        percent > 90f -> Color(0xFFD93025)
        percent > 70f -> Color(0xFFF9AB00)
        else -> Color(0xFFD97757)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(7.36.dp)                        // h-2
            .clip(CircleShape)
            .background(colors.border),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth((percent / 100f).coerceIn(0f, 1f))
                .height(7.36.dp)
                .clip(CircleShape)
                .background(fill),
        )
    }
}

/** 统计卡片 —— 源码 `bg-claude-bg border border-claude-border rounded-xl text-center` */
@Composable
private fun StatCard(value: String, label: String, modifier: Modifier = Modifier) {
    val colors = CCMTheme.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(11.04.dp))
            .background(colors.bgMain)
            .border(1.dp, colors.border, RoundedCornerShape(11.04.dp))
            .padding(vertical = 11.04.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.84.dp),
    ) {
        Text(
            text = value,
            style = CCMText.body20.copy(fontSize = 18.4.sp, fontWeight = FontWeight.SemiBold),
            color = colors.textMain,
        )
        Text(
            text = label,
            style = CCMText.body12.copy(fontSize = 11.04.sp),
            color = colors.textSecondary,
        )
    }
}
