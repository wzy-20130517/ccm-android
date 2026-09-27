package com.ccm.app.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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

/**
 * Admin · 套餐管理 —— 对齐 `AdminPlans.tsx`（202 行）。
 *
 * ## 源码结构
 * 1. 头部：标题「套餐管理」+ 刷新 / 添加套餐
 * 2. 错误条（`mb-4 p-3 bg-red-50 border-red-200`）
 * 3. 表单卡（展开时）：`grid grid-cols-2 gap-3` 8 个字段 + 保存 / 取消
 *    字段：套餐名称* / 价格（元）* / 时长（天）/ 美元额度* /
 *          存储额度（MB）/ 窗口预算 (真$/5h) / 周预算 (真$/周) / 描述
 * 4. 表格 9 列：名称（含描述副行）/ 价格 / 时长 / 美元额度 /
 *    窗口÷5h / 周预算 / 存储 / 状态 / 操作
 *    - 状态徽章：上架（bg-green-100 text-green-600）/ 下架（bg-gray-100 text-gray-500）
 *    - 下架行整行 `opacity-50`
 *    - 操作三按钮：编辑 / 上架下架 / 删除（图标 14px → 12.88）
 *
 * ## 单位换算（源码的隐藏单位，迁移时必须保留）
 * - `price` 存**分**（`Math.round(parseFloat(price_yuan) * 100)`）→ 显示 `/100`
 * - `token_quota` 存**万分之一美元**（`* 10000`）→ 显示 `/10000`
 * - `storage_quota` 存**字节** → 显示 `/1024/1024` MB
 *
 * ## 换算（Tailwind × 0.92）
 * - 卡片 `rounded-xl` → 圆角 11.04 · 内距 `p-5` → 18.40
 * - 输入框 `px-3 py-2 rounded-lg` → 11.04 × 7.36 / 圆角 7.36
 * - 表格 `px-4 py-3` → 14.72 × 11.04 · 表头底色 #F9FAFB
 * - 标签 `text-xs` → 11.04 · 正文 `text-sm` → 12.88
 *
 * ## ⚠️ 移动端
 * Admin 无移动端覆盖规则 → 9 列表格在 393px 下极窄。照搬源码（零变化要求），
 * 采用**横向滚动**保持列宽（等价 Web 的 `overflow-x: auto`）。
 */
@Composable
fun AdminPlansPage(modifier: Modifier = Modifier) {
    var showForm by remember { mutableStateOf(false) }
    var editId by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf("") }

    // 表单字段（对应 EMPTY_FORM）
    var fName by remember { mutableStateOf("") }
    var fPrice by remember { mutableStateOf("") }
    var fDays by remember { mutableStateOf("30") }
    var fQuota by remember { mutableStateOf("") }
    var fStorage by remember { mutableStateOf("") }
    var fDesc by remember { mutableStateOf("") }
    var fWindow by remember { mutableStateOf("") }
    var fWeekly by remember { mutableStateOf("") }

    /** 重置表单（对应 `setForm(EMPTY_FORM)`） */
    fun resetForm() {
        fName = ""; fPrice = ""; fDays = "30"; fQuota = ""
        fStorage = ""; fDesc = ""; fWindow = ""; fWeekly = ""
    }

    AdminPage(modifier = modifier.verticalScroll(rememberScrollState())) {
        AdminPageHeader(title = "套餐管理") {
            AdminButton(label = "刷新", onClick = { })
            AdminButton(
                label = "添加套餐",
                onClick = { showForm = true; editId = null; resetForm() },
                variant = AdminButtonVariant.PRIMARY,
            )
        }

        if (error.isNotEmpty()) {
            AdminErrorBar(message = error, onDismiss = { error = "" })
            Spacer(Modifier.height(AdminSpacing.p4))
        }

        if (showForm) {
            AdminCard {
                AdminSectionTitle(text = if (editId != null) "编辑套餐" else "添加套餐")
                Spacer(Modifier.height(AdminSpacing.p3))

                // `grid grid-cols-2 gap-3` → 两列 / 间距 11.04
                Column(verticalArrangement = Arrangement.spacedBy(AdminSpacing.gap3)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(AdminSpacing.gap3)) {
                        AdminLabeledField("套餐名称 *", fName, { fName = it }, "基础版", Modifier.weight(1f))
                        AdminLabeledField("价格（元）*", fPrice, { fPrice = it }, "49.00", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(AdminSpacing.gap3)) {
                        AdminLabeledField("时长（天）", fDays, { fDays = it }, "30", Modifier.weight(1f))
                        AdminLabeledField("美元额度 *", fQuota, { fQuota = it }, "10", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(AdminSpacing.gap3)) {
                        AdminLabeledField("存储额度（MB）", fStorage, { fStorage = it }, "100", Modifier.weight(1f))
                        AdminLabeledField("窗口预算 (真$/5h)", fWindow, { fWindow = it }, "6.0", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(AdminSpacing.gap3)) {
                        AdminLabeledField("周预算 (真$/周)", fWeekly, { fWeekly = it }, "17.3", Modifier.weight(1f))
                        AdminLabeledField("描述", fDesc, { fDesc = it }, "可选说明", Modifier.weight(1f))
                    }
                }

                Spacer(Modifier.height(AdminSpacing.p3))
                Row(horizontalArrangement = Arrangement.spacedBy(AdminSpacing.p2)) {
                    AdminButton(
                        label = "保存",
                        onClick = {
                            if (fName.isEmpty() || fPrice.isEmpty() || fQuota.isEmpty()) {
                                error = "请填写名称、价格和美元额度"
                            } else {
                                showForm = false; editId = null; resetForm()
                            }
                        },
                        variant = AdminButtonVariant.PRIMARY,
                    )
                    AdminButton(
                        label = "取消",
                        onClick = { showForm = false; editId = null },
                        variant = AdminButtonVariant.SECONDARY,
                    )
                }
            }
            Spacer(Modifier.height(AdminSpacing.p6))
        }

        AdminPlanTable(plans = samplePlans)
    }
}

/** 带标签的输入框 —— 对应 `<div><label class="block text-xs text-gray-500 mb-1">…</label><input/></div>` */
@Composable
internal fun AdminLabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = CCMText.body12.copy(fontSize = AdminText.xs),
            color = AdminColors.gray500,
            modifier = Modifier.padding(bottom = AdminSpacing.p1),
        )
        AdminTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 套餐行数据（对应源码 `interface Plan`） */
internal data class AdminPlanRow(
    val id: Int,
    val name: String,
    val priceCents: Int,        // 分
    val durationDays: Int,
    val quotaTenThousandth: Int, // 万分之一美元
    val storageBytes: Long,
    val description: String?,
    val isActive: Boolean,
    val windowBudget: Double,
    val weeklyBudget: Double,
)

/**
 * 套餐表格 —— 对应源码
 * `<div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
 *    <table className="w-full text-sm">…`
 *
 * ⚠️ 横向滚动包在**最外层**（同 AdminKeyPool 的 KeyTable）：
 * 每行各自滚动会导致表头与数据列错位。
 */
@Composable
private fun AdminPlanTable(plans: List<AdminPlanRow>) {
    val hScroll = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.04.dp))
            .background(AdminColors.white)
            .border(1.dp, AdminColors.gray200, RoundedCornerShape(11.04.dp)),
    ) {
        Column(modifier = Modifier.horizontalScroll(hScroll)) {
            // ── 表头（bg-gray-50）────────────────────────────
            Row(
                modifier = Modifier
                    .background(AdminColors.gray50)
                    .padding(horizontal = AdminSpacing.p4),
            ) {
                AdminPlanCell("名称", header = true, width = 170.dp)
                AdminPlanCell("价格", header = true, width = 82.dp)
                AdminPlanCell("时长", header = true, width = 74.dp)
                AdminPlanCell("美元额度", header = true, width = 96.dp)
                AdminPlanCell("窗口/5h", header = true, width = 88.dp)
                AdminPlanCell("周预算", header = true, width = 88.dp)
                AdminPlanCell("存储", header = true, width = 82.dp)
                AdminPlanCell("状态", header = true, width = 74.dp)
                AdminPlanCell("操作", header = true, width = 92.dp)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(AdminColors.gray200),
            )

            if (plans.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 29.44.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "暂无套餐",
                        style = CCMText.body14.copy(fontSize = AdminText.sm),
                        color = AdminColors.gray400,
                    )
                }
            }

            plans.forEach { p ->
                Row(
                    modifier = Modifier
                        .padding(horizontal = AdminSpacing.p4)
                        .then(
                            // 下架行整行 `opacity-50`
                            if (!p.isActive) {
                                Modifier.background(AdminColors.white.copy(alpha = 0.5f))
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    // 名称 + 描述副行
                    Box(
                        modifier = Modifier
                            .width(170.dp)
                            .padding(vertical = AdminSpacing.p3),
                    ) {
                        Column {
                            Text(
                                text = p.name,
                                style = CCMText.body14.copy(
                                    fontSize = AdminText.sm,
                                    fontWeight = FontWeight.Medium,
                                ),
                                color = AdminColors.gray800,
                                maxLines = 1,
                            )
                            if (!p.description.isNullOrEmpty()) {
                                Text(
                                    text = p.description,
                                    style = CCMText.body12.copy(fontSize = AdminText.xs),
                                    color = AdminColors.gray400,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    AdminPlanCell("¥${"%.2f".format(p.priceCents / 100.0)}", width = 82.dp)
                    AdminPlanCell("${p.durationDays} 天", width = 74.dp)
                    AdminPlanCell("$${"%.2f".format(p.quotaTenThousandth / 10000.0)}", width = 96.dp)
                    AdminPlanCell("$${"%.1f".format(p.windowBudget)}", width = 88.dp)
                    AdminPlanCell(
                        text = if (p.weeklyBudget > 0) "$${"%.1f".format(p.weeklyBudget)}" else "-",
                        width = 88.dp,
                    )
                    AdminPlanCell("${p.storageBytes / 1024 / 1024}MB", width = 82.dp)

                    // 状态徽章：rounded-full px-2 py-0.5 text-xs
                    Box(
                        modifier = Modifier
                            .width(74.dp)
                            .padding(vertical = AdminSpacing.p3),
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (p.isActive) AdminColors.green100 else AdminColors.gray100,
                                )
                                .padding(horizontal = AdminSpacing.p2, vertical = 0.46.dp),
                        ) {
                            Text(
                                text = if (p.isActive) "上架" else "下架",
                                style = CCMText.body12.copy(fontSize = AdminText.xs),
                                color = if (p.isActive) AdminColors.green600 else AdminColors.gray500,
                            )
                        }
                    }

                    // 操作：编辑 / 上架下架 / 删除
                    Row(
                        modifier = Modifier
                            .width(92.dp)
                            .padding(vertical = AdminSpacing.p3),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AdminSpacing.p1),
                    ) {
                        AdminIconAction("编辑", AdminColors.gray400)
                        AdminIconAction(if (p.isActive) "下架" else "上架", AdminColors.amber500)
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

/** 表格单元格（`px-4 py-3 text-sm text-gray-600`，表头 `font-medium text-gray-600`） */
@Composable
private fun AdminPlanCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    header: Boolean = false,
) {
    Box(modifier = Modifier.width(width).padding(vertical = AdminSpacing.p3)) {
        Text(
            text = text,
            style = CCMText.body14.copy(
                fontSize = if (header) AdminText.xs else AdminText.sm,
                fontWeight = if (header) FontWeight.Medium else FontWeight.Normal,
            ),
            color = AdminColors.gray600,
            maxLines = 1,
        )
    }
}

/** 演示数据（源码从 `getPlans()` 拉取，这里静态样例） */
private val samplePlans = listOf(
    AdminPlanRow(
        id = 1, name = "基础版", priceCents = 4900, durationDays = 30,
        quotaTenThousandth = 100000, storageBytes = 100L * 1024 * 1024,
        description = "个人日常使用", isActive = true,
        windowBudget = 6.0, weeklyBudget = 17.3,
    ),
    AdminPlanRow(
        id = 2, name = "专业版", priceCents = 9900, durationDays = 30,
        quotaTenThousandth = 300000, storageBytes = 500L * 1024 * 1024,
        description = "高频开发者", isActive = true,
        windowBudget = 20.0, weeklyBudget = 60.0,
    ),
    AdminPlanRow(
        id = 3, name = "团队版（已下架）", priceCents = 29900, durationDays = 90,
        quotaTenThousandth = 1000000, storageBytes = 2048L * 1024 * 1024,
        description = "多人协作", isActive = false,
        windowBudget = 60.0, weeklyBudget = 180.0,
    ),
)
