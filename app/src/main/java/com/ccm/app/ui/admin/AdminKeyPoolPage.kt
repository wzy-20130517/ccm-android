package com.ccm.app.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CcmMono

/**
 * Admin · 密钥池管理 —— 对齐 `AdminKeyPool.tsx`（465 行）。
 *
 * ## 源码结构
 * 1. 头部：标题 + 刷新 / 录入充值 / 添加密钥 三按钮
 * 2. 错误条（可选）
 * 3. 卡片「模型上游路由」：4 组（sonnet/haiku/opus/gpt）× base_url + 优先密钥
 * 4. 卡片「添加/编辑密钥」表单（展开时）
 * 5. 密钥表格（10 列：状态/API Key/中转/并发/充值单价/倍率/真实单价/今日消耗/错误/操作）
 * 6. 录入充值弹窗
 *
 * ## ★ 移动端
 * Admin 无移动端覆盖规则 → 表格**保持 10 列**，在 393px 下会被压得极窄。
 * 这是源码的真实行为（本机实测时该页需管理员权限，未能截图确认）。
 *
 * 但表格在 Compose 里直接横排 10 列会挤成不可读 —— 采用**横向滚动**保持列宽，
 * 这是 Web `overflow-x: auto` 的等效行为（表格在窄屏的默认处理）。
 *
 * 实测（Tailwind × 0.92）：
 * - 表头 `px-4 py-3 text-gray-600 font-medium` → 内距 14.72 × 11.04
 * - 单元格 `px-4 py-3 text-sm` → 同上
 * - 表格容器 `rounded-xl overflow-hidden` → 圆角 11.04
 * - 表头底色 `bg-gray-50` → #F9FAFB
 * - 行分隔 `border-b border-gray-100` → #F3F4F6
 */
@Composable
fun AdminKeyPoolPage(modifier: Modifier = Modifier) {
    var showAdd by remember { mutableStateOf(false) }
    var showRecharge by remember { mutableStateOf(false) }
    var editId by remember { mutableStateOf<Int?>(null) }

    AdminPage(modifier = modifier.verticalScroll(rememberScrollState())) {
        AdminPageHeader(title = "密钥池管理") {
            AdminButton(label = "刷新", onClick = { })
            AdminButton(
                label = "录入充值",
                onClick = { showRecharge = true },
                variant = AdminButtonVariant.SUCCESS,
            )
            AdminButton(
                label = "添加密钥",
                onClick = { showAdd = true; editId = null },
                variant = AdminButtonVariant.PRIMARY,
            )
        }

        // ── 模型上游路由 ─────────────────────────────────────────
        AdminCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AdminSectionTitle(text = "模型上游路由")
                AdminButton(
                    label = "保存路由",
                    onClick = { },
                    variant = AdminButtonVariant.PRIMARY,
                    small = true,
                )
            }
            Spacer(Modifier.height(AdminSpacing.p3))
            AdminHint(
                text = "可按模型族指定上游地址和优先密钥。优先密钥不可用时，" +
                    "会回退到同一上游地址下的其他可用密钥。",
            )
            Spacer(Modifier.height(AdminSpacing.p3))

            // 移动端 grid-cols-1 → 单列堆叠
            Column(verticalArrangement = Arrangement.spacedBy(AdminSpacing.gap3)) {
                listOf("Sonnet", "Haiku", "Opus", "GPT").forEach { group ->
                    UpstreamRouteBox(group = group)
                }
            }
        }

        Spacer(Modifier.height(AdminSpacing.p6))

        // ── 添加/编辑密钥表单 ────────────────────────────────────
        if (showAdd || editId != null) {
            AdminCard {
                AdminSectionTitle(text = if (editId != null) "编辑密钥" else "添加密钥")
                Spacer(Modifier.height(AdminSpacing.p3))
                Column(verticalArrangement = Arrangement.spacedBy(AdminSpacing.gap3)) {
                    if (editId == null) {
                        AdminTextField(
                            value = "",
                            onValueChange = { },
                            placeholder = "API Key *",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    AdminTextField(
                        value = "",
                        onValueChange = { },
                        placeholder = "Base URL *",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(AdminSpacing.gap3)) {
                        AdminTextField(
                            value = "",
                            onValueChange = { },
                            placeholder = "中转名称",
                            modifier = Modifier.weight(1f),
                        )
                        AdminTextField(
                            value = "",
                            onValueChange = { },
                            placeholder = "中转 URL",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(AdminSpacing.gap3)) {
                        NumberField(label = "最大并发", hint = "同时处理的请求上限", modifier = Modifier.weight(1f))
                        NumberField(label = "优先级", hint = "越大越优先选用", modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(AdminSpacing.gap3)) {
                        NumberField(label = "权重", hint = "同优先级下的分配比例", modifier = Modifier.weight(1f))
                        NumberField(label = "分组倍率", hint = "该密钥的计费倍率", modifier = Modifier.weight(1f))
                    }
                    NumberField(
                        label = "充值单价 (¥/站内$)",
                        hint = "真实单价 = 充值单价 × 倍率",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AdminTextField(
                        value = "",
                        onValueChange = { },
                        placeholder = "备注",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(AdminSpacing.p3))
                Row(horizontalArrangement = Arrangement.spacedBy(AdminSpacing.p2)) {
                    AdminButton(
                        label = if (editId != null) "保存" else "添加",
                        onClick = { showAdd = false; editId = null },
                        variant = AdminButtonVariant.PRIMARY,
                    )
                    AdminButton(
                        label = "取消",
                        onClick = { showAdd = false; editId = null },
                    )
                }
            }
            Spacer(Modifier.height(AdminSpacing.p6))
        }

        // ── 密钥表格 ─────────────────────────────────────────────
        KeyTable(
            rows = listOf(
                KeyRow(
                    id = 1, apiKey = "sk-1Kw9…Q2mN", relay = "英伟达",
                    concurrency = 3, maxConcurrency = 8,
                    chargeRate = 0.15, multiplier = 1.0, enabled = true,
                    healthStatus = "healthy", inputToday = 12400, outputToday = 8600,
                    errors = 0,
                ),
                KeyRow(
                    id = 2, apiKey = "sk-3Fp7…Lx8D", relay = "sharellm",
                    concurrency = 1, maxConcurrency = 4,
                    chargeRate = 0.28, multiplier = 1.5, enabled = true,
                    healthStatus = "healthy", inputToday = 8200, outputToday = 5100,
                    errors = 0,
                ),
                KeyRow(
                    id = 3, apiKey = "sk-9Qa2…Wm4K", relay = "WorkBuddy",
                    concurrency = 0, maxConcurrency = 2,
                    chargeRate = 0.12, multiplier = 1.0, enabled = false,
                    healthStatus = "down", inputToday = 0, outputToday = 0,
                    errors = 7,
                ),
            ),
        )
    }

    if (showRecharge) {
        AdminRechargeDialog(onDismiss = { showRecharge = false })
    }
}

/**
 * 上游路由配置块 —— 对应源码
 * `<div className="border border-gray-200 rounded-lg p-3">` +
 * `<div className="text-xs font-medium text-gray-700 mb-2">` +
 * `<input className="w-full mb-2 px-2 py-1.5 border rounded text-xs">` +
 * `<select className="w-full px-2 py-1.5 border rounded text-xs">`
 *
 * 实测（Tailwind × 0.92）：内距 **11.04** · 圆角 **7.36** ·
 * 输入框 `px-2 py-1.5` → **7.36 × 5.52** · 字号 `text-xs` → **11.04**。
 */
@Composable
private fun UpstreamRouteBox(group: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.36.dp))
            .border(1.dp, AdminColors.gray200, RoundedCornerShape(7.36.dp))
            .padding(AdminSpacing.p3),
    ) {
        Text(
            text = group,
            style = CCMText.body12.copy(
                fontSize = AdminText.xs,
                fontWeight = FontWeight.Medium,
            ),
            color = AdminColors.gray700,
        )
        Spacer(Modifier.height(AdminSpacing.p2))
        AdminTextField(
            value = "",
            onValueChange = { },
            placeholder = "https://api.example.com",
            modifier = Modifier.fillMaxWidth(),
            minHeight = 26.68.dp,
        )
        Spacer(Modifier.height(AdminSpacing.p2))
        // 下拉：不指定（按权重）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.68.dp)
                .clip(RoundedCornerShape(3.68.dp))
                .background(AdminColors.white)
                .border(1.dp, AdminColors.gray200, RoundedCornerShape(3.68.dp))
                .padding(horizontal = 7.36.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = "不指定（按权重）",
                style = CCMText.body12.copy(fontSize = AdminText.xs),
                color = AdminColors.gray700,
            )
        }
    }
}

/** 带标签的数字输入 —— 对应源码 `<div><label text-xs text-gray-500 mb-1>…</label><input type=number></div>` */
@Composable
private fun NumberField(
    label: String,
    hint: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row {
            Text(
                text = label,
                style = CCMText.body12.copy(fontSize = AdminText.xs),
                color = AdminColors.gray500,
            )
            Spacer(Modifier.width(AdminSpacing.p1))
            Text(
                text = "— $hint",
                style = CCMText.body12.copy(fontSize = AdminText.xs),
                color = AdminColors.gray400,
            )
        }
        Spacer(Modifier.height(3.68.dp))        // mb-1
        AdminTextField(
            value = "",
            onValueChange = { },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 密钥表格行数据 */
private data class KeyRow(
    val id: Int,
    val apiKey: String,
    val relay: String,
    val concurrency: Int,
    val maxConcurrency: Int,
    val chargeRate: Double,
    val multiplier: Double,
    val enabled: Boolean,
    val healthStatus: String,
    val inputToday: Long,
    val outputToday: Long,
    val errors: Int,
)

/**
 * 密钥表格 —— 对应源码
 * `<div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
 *    <table className="w-full text-sm">…`
 *
 * 10 列：状态 / API Key / 中转 / 并发 / 充值单价 / 倍率 / 真实单价 / 今日消耗 / 错误 / 操作。
 *
 * ⚠️ **横向滚动**：Web 里 `<table className="w-full">` 在窄屏会被压窄；
 * 但 Compose 若不加横向滚动，10 列会挤到不可读。这里用 `horizontalScroll`
 * 保持每列最小宽度 —— 等效于浏览器给表格加的默认横向滚动行为。
 */
@Composable
private fun KeyTable(rows: List<KeyRow>) {
    // ★ 横向滚动必须包在**最外层**：如果每行各自 horizontalScroll，
    // 它们会独立滚动、表头与数据列错位（Compose 与 HTML table 的关键差异，
    // HTML 的 table 是一张整体滚动的表）。
    val hScroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.04.dp))
            .background(AdminColors.white)
            .border(1.dp, AdminColors.gray200, RoundedCornerShape(11.04.dp)),
    ) {
        Column(modifier = Modifier.horizontalScroll(hScroll)) {
            // ── 表头 ──────────────────────────────────────────
            Row(
                modifier = Modifier
                    .background(AdminColors.gray50)
                    .padding(horizontal = AdminSpacing.p4),
            ) {
                TableCell("状态", header = true, width = 74.dp)
                TableCell("API Key", header = true, width = 128.dp)
                TableCell("中转", header = true, width = 82.dp)
                TableCell("并发", header = true, width = 56.dp)
                TableCell("充值单价", header = true, width = 74.dp)
                TableCell("倍率", header = true, width = 56.dp)
                TableCell("真实单价", header = true, width = 74.dp)
                TableCell("今日消耗", header = true, width = 110.dp)
                TableCell("错误", header = true, width = 60.dp)
                TableCell("操作", header = true, width = 74.dp)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(AdminColors.gray200),
            )

            if (rows.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 29.44.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "暂无密钥，点击上方「添加密钥」开始",
                        style = CCMText.body14.copy(fontSize = AdminText.sm),
                        color = AdminColors.gray400,
                    )
                }
            }

            rows.forEach { r ->
                Row(
                    modifier = Modifier
                        .padding(horizontal = AdminSpacing.p4)
                        .then(
                            if (!r.enabled) {
                                Modifier.background(AdminColors.white.copy(alpha = 0.5f))
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    // 状态：圆点 + 启用/禁用
                    Row(
                        modifier = Modifier.width(74.dp).padding(vertical = AdminSpacing.p3),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AdminSpacing.p2),
                    ) {
                        AdminStatusDot(color = AdminStatusColor(r.healthStatus))
                        Text(
                            text = if (r.enabled) "启用" else "禁用",
                            style = CCMText.body12.copy(fontSize = AdminText.xs),
                            color = AdminColors.gray500,
                        )
                    }
                    TableCell(r.apiKey, mono = true, width = 128.dp)
                    TableCell(r.relay.ifEmpty { "-" }, width = 82.dp)
                    // 并发：蓝字 / 灰字
                    Row(
                        modifier = Modifier.width(56.dp).padding(vertical = AdminSpacing.p3),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${r.concurrency}",
                            style = CCMText.body14.copy(
                                fontSize = AdminText.sm,
                                fontWeight = FontWeight.Medium,
                            ),
                            color = AdminColors.blue600,
                        )
                        Text(
                            text = "/${r.maxConcurrency}",
                            style = CCMText.body14.copy(fontSize = AdminText.sm),
                            color = AdminColors.gray400,
                        )
                    }
                    TableCell("¥${"%.2f".format(r.chargeRate)}", width = 74.dp)
                    TableCell("${r.multiplier}x", width = 56.dp, size = AdminText.xs)
                    TableCell("¥${"%.2f".format(r.chargeRate * r.multiplier)}", width = 74.dp, size = AdminText.xs)
                    TableCell(
                        "入 ${"%.1f".format(r.inputToday / 1000.0)}K / 出 ${"%.1f".format(r.outputToday / 1000.0)}K",
                        width = 110.dp,
                        size = AdminText.xs,
                    )
                    // 错误列
                    Box(modifier = Modifier.width(60.dp).padding(vertical = AdminSpacing.p3)) {
                        if (r.errors > 0) {
                            Text(
                                text = "${r.errors} 次",
                                style = CCMText.body12.copy(fontSize = AdminText.xs),
                                color = AdminColors.red500,
                            )
                        } else {
                            Text(
                                text = "正常",
                                style = CCMText.body12.copy(fontSize = AdminText.xs),
                                color = AdminColors.green500,
                            )
                        }
                    }
                    // 操作列：编辑 / 启停 / 删除
                    //
                    // 【2026-10-06 修】74dp 装不下三个文字按钮（单个约 27.6dp
                    // × 3 + 两个 3.68 gap ≈ 90.2dp）→ 第三个按钮画到列外。
                    // 套餐页对同样三个按钮用的是 92dp（刚好装下）—— 74 是
                    // 照 Web 的图标按钮尺寸算的，但本项目用文字替代了图标。
                    Row(
                        modifier = Modifier.width(92.dp).padding(vertical = AdminSpacing.p3),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.68.dp),
                    ) {
                        AdminIconAction("编辑", AdminColors.gray400)
                        AdminIconAction("启停", AdminColors.gray400)
                        AdminIconAction("删除", AdminColors.gray400)
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(AdminColors.gray100),
                )
            }
        }
    }
}

/** 表格单元格 */
@Composable
private fun TableCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    header: Boolean = false,
    mono: Boolean = false,
    size: androidx.compose.ui.unit.TextUnit = AdminText.sm,
) {
    Box(modifier = Modifier.width(width).padding(vertical = AdminSpacing.p3)) {
        Text(
            text = text,
            style = CCMText.body14.copy(
                fontSize = size,
                fontWeight = if (header) FontWeight.Medium else FontWeight.Normal,
                fontFamily = if (mono) CcmMono else com.ccm.app.ui.theme.CcmSans,
            ),
            // 【2026-10-06 修】原来是 if (header) gray600 else gray600 ——
            // 两个分支同值，三元无意义。
            // 修正为「表头浅、数据行深」（与 AdminModelsPage:295 一致）。
            color = if (header) AdminColors.gray500 else AdminColors.gray700,
            maxLines = 1,
        )
    }
}


/**
 * 录入充值弹窗 —— 对应源码
 * `<div className="fixed inset-0 bg-black/30 flex items-center justify-center z-50">
 *    <div className="bg-white rounded-xl p-6 w-[480px] max-h-[80vh] overflow-y-auto">`
 *
 * ⚠️ 移动端：`w-[480px]` 命中 `[class*="w-[480px]"]`？—— **不命中**。
 * 那条规则只覆盖 `w-[800px]/w-[720px]/w-[672px]/w-[560px]/w-[460px]/w-[440px]/
 * w-[400px]/w-[360px]`，**没有 480**。所以这个弹窗在 393px 屏上会**溢出**。
 * 这是源码的既有 bug，迁移时**照搬**（零变化要求），不擅自修。
 */
@Composable
private fun AdminRechargeDialog(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            // 【2026-10-06 修】同 AdminRedemptionPage 的弹窗：fillMaxWidth 只定宽，
            // 遮罩盖不住全屏、弹窗贴左上角。改 fillMaxSize 让 contentAlignment 生效。
            .fillMaxSize()
            .background(AdminColors.gray800.copy(alpha = 0.3f))
            .clickable(onClick = onDismiss)
            .padding(AdminSpacing.p4),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(441.6.dp)                    // w-[480px] × 0.92（照搬源码的溢出行为）
                .clip(RoundedCornerShape(11.04.dp))
                .background(AdminColors.white)
                .clickable { }                      // 阻止冒泡
                .padding(AdminSpacing.p6),
        ) {
            AdminSectionTitle(text = "录入充值")
            Spacer(Modifier.height(AdminSpacing.p4))
            Column(verticalArrangement = Arrangement.spacedBy(AdminSpacing.gap3)) {
                NumberField(label = "充值金额 (¥)", hint = "必填", modifier = Modifier.fillMaxWidth())
                AdminTextField(
                    value = "",
                    onValueChange = { },
                    placeholder = "备注",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(AdminSpacing.p4))
            Row(horizontalArrangement = Arrangement.spacedBy(AdminSpacing.p2)) {
                AdminButton(
                    label = "确认",
                    onClick = onDismiss,
                    variant = AdminButtonVariant.SUCCESS,
                )
                AdminButton(label = "取消", onClick = onDismiss)
            }
        }
    }
}
