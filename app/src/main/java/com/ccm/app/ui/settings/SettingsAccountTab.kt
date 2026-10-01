package com.ccm.app.ui.settings

import androidx.compose.foundation.background
import android.widget.Toast
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
                        // ★ audit-settings #1：原写死真实邮箱 —— 自部署无账号体系，
                        //   不该显示（也无从获取）。诚实文案。
                        text = "仅本机模式（无账号体系）",
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
                    // ★ audit-settings #1：原写死机型/地点/日期（日期永久停在某天）。
                    //   机型取 Build.MODEL 真值；地点无来源不显示；
                    //   时间改为语义状态（无首装时间记录）。
                    SessionRow(
                        device = android.os.Build.MODEL,
                        location = "本机",
                        created = "单机运行",
                        lastActive = "活跃中",
                        isCurrent = true,
                    )
                }
                Text(
                    text = "",   // 原 "No active sessions" 与上面的设备行自相矛盾（写死遗留）

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
    val ctx = androidx.compose.ui.platform.LocalContext.current
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
                // ★ 2026-09-27：原来 `onClick = { }` 是空的 —— 点了毫无反应。
                //   本地版没有账号后端（配置全在本机），改密码无处可存。
                //   按「不静默吞掉操作」原则：弹提示说明，并把输入清掉。
                onClick = {
                    Toast.makeText(
                        ctx,
                        "本地版没有账号后端，密码不适用（配置都在本机）",
                        Toast.LENGTH_LONG,
                    ).show()
                    current = ""; newPwd = ""; confirm = ""
                    onDismiss()
                },
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
    val ctx = androidx.compose.ui.platform.LocalContext.current
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
                // ★ 2026-09-27：同样原来是空的。真删账号需要服务端，
                //   本地版只能提示（不做「看似删了其实没删」的假动作）。
                onClick = {
                    Toast.makeText(
                        ctx,
                        "本地版没有账号后端，无法删除账号",
                        Toast.LENGTH_LONG,
                    ).show()
                    password = ""
                    onDismiss()
                },
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

                // ★ 2026-10-01：从「无配额」占位改为**真数据** ——
                //   APK 能拿到的：会话数 / 存储占用 / 最近一轮 token。
                //   原来三行全是"请到系统设置查看"式甩锅文案，用户白点进来。
                val usageStats = remember {
                    try {
                        val st = com.ccm.app.AppGraph.storage
                        if (st == null) null else {
                            val sessDir = java.io.File(st.root, "sessions")
                            val files = sessDir.listFiles() ?: emptyArray()
                            val bytes = files.sumOf { it.length() }
                            Triple(files.size, bytes, com.ccm.app.AppGraph.session)
                        }
                    } catch (_: Throwable) { null }
                }

                // 会话数
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "会话数",
                            style = CCMText.body13.copy(fontSize = SettingsLabelSizeSp),
                            color = colors.textMain,
                        )
                        Text(
                            text = usageStats?.let { "${it.first} 个" } ?: "—",
                            style = CCMText.body13.copy(fontSize = SettingsLabelSizeSp),
                            color = colors.textSecondary,
                        )
                    }
                    Spacer(Modifier.height(7.36.dp))
                }

                // 存储占用（会话文件）
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "会话存储",
                            style = CCMText.body13.copy(fontSize = SettingsLabelSizeSp),
                            color = colors.textMain,
                        )
                        Text(
                            text = usageStats?.let { (_, b, _) ->
                                if (b < 1024) "${b} B"
                                else if (b < 1024 * 1024) "%.1f KB".format(b / 1024.0)
                                else "%.1f MB".format(b / 1024.0 / 1024.0)
                            } ?: "—",
                            style = CCMText.body13.copy(fontSize = SettingsLabelSizeSp),
                            color = colors.textSecondary,
                        )
                    }
                    Spacer(Modifier.height(7.36.dp))
                }

                // 最近一轮 token
                Column {
                    val sess = usageStats?.third
                    val st = sess?.state?.value
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "最近一轮 token",
                            style = CCMText.body13.copy(fontSize = SettingsLabelSizeSp),
                            color = colors.textMain,
                        )
                        Text(
                            text = st?.let {
                                "入 ${it.inputTokens} / 出 ${it.outputTokens}"
                            } ?: "—（本会话还没请求）",
                            style = CCMText.body13.copy(fontSize = SettingsLabelSizeSp),
                            color = colors.textSecondary,
                        )
                    }
                    Spacer(Modifier.height(7.36.dp))
                }

                // 额度说明（保留诚实告知：自部署无配额）
                Text(
                    text = "自部署模式不走服务端额度，以上为本机真实统计。",
                    // 10.21.sp = SettingsLabelSizeSp(11.21) - 1（TextUnit 不能直接减 Int）
                    style = CCMText.body12.copy(fontSize = 10.21.sp),
                    color = colors.textSecondary,
                )

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
                        text = "本机自部署",
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
                    // ★ audit-settings #2：原 12/348 假数据。改为可得真值：
                    //   历史会话数（SessionStore）+ 当前会话消息数（live state）。
                    val stStore = com.ccm.app.AppGraph.storage?.let { com.ccm.app.core.session.SessionStore(it) }
                    val historyCount = remember(stStore) { stStore?.list()?.size ?: 0 }
                    val liveMsgs = com.ccm.app.AppGraph.session?.state?.value?.bubbles?.size ?: 0
                    StatCard(value = "$historyCount", label = "历史会话", modifier = Modifier.weight(1f))
                    StatCard(value = "$liveMsgs", label = "本会话消息", modifier = Modifier.weight(1f))
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
