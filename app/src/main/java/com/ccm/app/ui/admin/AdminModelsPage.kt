package com.ccm.app.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
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
import androidx.compose.ui.unit.sp
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CcmMono

/**
 * Admin · 模型管理 —— 对齐 `AdminModels.tsx`（330 行）。
 *
 * ## 源码结构
 * 1. 头部：标题 + 刷新 / 添加模型
 * 2. 卡片「首页模型选择（固定 3 个）」：3 个下拉 + 保存按钮
 * 3. 卡片「添加/编辑模型」表单（展开时）：
 *    模型 ID / 显示名称 / 模型倍率 / 输出倍率 / 缓存倍率 / 启用开关 / 排序
 * 4. 模型表格：ID / 名称 / 倍率 / 状态 / 排序 / 操作
 *
 * ## 移动端
 * 无覆盖规则。`grid-cols-1 md:grid-cols-3` 在 393px 下是 **1 列**（md 断点 768 未达），
 * 所以「首页模型选择」的 3 个下拉**纵向堆叠**。
 *
 * 实测（Tailwind × 0.92）：
 * - `gap-3` → 11.04 · `mt-3` → 11.04 · `px-4 py-2` → 14.72 × 7.36
 * - `disabled:bg-gray-50` → #F9FAFB
 */
@Composable
fun AdminModelsPage(modifier: Modifier = Modifier) {
    var showForm by remember { mutableStateOf(false) }
    var editId by remember { mutableStateOf<String?>(null) }

    AdminPage(modifier = modifier.verticalScroll(rememberScrollState())) {
        AdminPageHeader(title = "模型管理") {
            AdminButton(label = "刷新", onClick = { })
            AdminButton(
                label = "添加模型",
                onClick = { showForm = true; editId = null },
                variant = AdminButtonVariant.PRIMARY,
            )
        }

        // ── 首页模型选择 ─────────────────────────────────────────
        AdminCard {
            AdminSectionTitle(text = "首页模型选择（固定 3 个）")
            Spacer(Modifier.height(AdminSpacing.p3))
            // 移动端 grid-cols-1 → 纵向堆叠
            Column(verticalArrangement = Arrangement.spacedBy(AdminSpacing.gap3)) {
                (0..2).forEach { idx ->
                    Column {
                        LabeledSelect(
                            label = "常用模型 #${idx + 1}",
                            value = "请选择模型",
                        )
                    }
                }
            }
            Spacer(Modifier.height(AdminSpacing.p3))     // mt-3
            AdminButton(
                label = "保存常用模型",
                onClick = { },
                variant = AdminButtonVariant.PRIMARY,
                enabled = true,
            )
        }

        Spacer(Modifier.height(AdminSpacing.p6))

        // ── 添加/编辑模型表单 ────────────────────────────────────
        if (showForm) {
            AdminCard {
                AdminSectionTitle(text = if (editId != null) "编辑模型" else "添加模型")
                Spacer(Modifier.height(AdminSpacing.p3))
                Column(verticalArrangement = Arrangement.spacedBy(AdminSpacing.gap3)) {
                    LabeledInput(
                        label = "模型 ID *",
                        placeholder = "claude-opus-4-6",
                        disabled = editId != null,      // 编辑时 ID 不可改
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(AdminSpacing.gap3)) {
                        LabeledInput(
                            label = "显示名称 *",
                            placeholder = "Opus 4.6",
                            modifier = Modifier.weight(1f),
                        )
                        LabeledInput(
                            label = "模型倍率",
                            placeholder = "1.0",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(AdminSpacing.gap3)) {
                        LabeledInput(
                            label = "输出倍率",
                            placeholder = "1.0",
                            modifier = Modifier.weight(1f),
                        )
                        LabeledInput(
                            label = "缓存倍率",
                            placeholder = "0.1",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(AdminSpacing.gap3)) {
                        LabeledInput(
                            label = "排序",
                            placeholder = "0",
                            modifier = Modifier.weight(1f),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "启用",
                                style = CCMText.body12.copy(fontSize = AdminText.xs),
                                color = AdminColors.gray500,
                            )
                            Spacer(Modifier.height(3.68.dp))
                            Row(
                                modifier = Modifier.height(34.4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AdminBadge(
                                    text = "已启用",
                                    bg = AdminColors.blue50,
                                    fg = AdminColors.blue600,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(AdminSpacing.p3))
                Row(horizontalArrangement = Arrangement.spacedBy(AdminSpacing.p2)) {
                    AdminButton(
                        label = if (editId != null) "保存" else "添加",
                        onClick = { showForm = false; editId = null },
                        variant = AdminButtonVariant.PRIMARY,
                    )
                    AdminButton(
                        label = "取消",
                        onClick = { showForm = false; editId = null },
                    )
                }
            }
            Spacer(Modifier.height(AdminSpacing.p6))
        }

        // ── 模型表格 ─────────────────────────────────────────────
        ModelTable(
            models = listOf(
                ModelRow("deepseek-v4.1-flash", "V4.1 Flash", 1.0, 1.0, true, 0),
                ModelRow("deepseek-v4.1-pro", "V4.1 Pro", 2.5, 3.0, true, 1),
                ModelRow("deepseek-v4.1-lite", "V4.1 Lite", 0.5, 0.5, false, 2),
            ),
        )
    }
}

/** 模型表格行 */
private data class ModelRow(
    val id: String,
    val name: String,
    val modelMultiplier: Double,
    val outputMultiplier: Double,
    val enabled: Boolean,
    val sort: Int,
)

/**
 * 模型表格 —— 对应源码
 * `<table className="w-full text-sm">`，列：ID / 名称 / 倍率 / 状态 / 排序 / 操作。
 *
 * 同 [AdminKeyPoolPage] 的表格策略：**外层统一横向滚动**。
 */
@Composable
private fun ModelTable(models: List<ModelRow>) {
    val hScroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.04.dp))
            .background(AdminColors.white)
            .border(1.dp, AdminColors.gray200, RoundedCornerShape(11.04.dp)),
    ) {
        Column(modifier = Modifier.horizontalScroll(hScroll)) {
            Row(
                modifier = Modifier
                    .background(AdminColors.gray50)
                    .padding(horizontal = AdminSpacing.p4),
            ) {
                ModelCell("模型 ID", 156.dp, header = true)
                ModelCell("名称", 92.dp, header = true)
                ModelCell("模型倍率", 66.dp, header = true)
                ModelCell("输出倍率", 66.dp, header = true)
                ModelCell("状态", 60.dp, header = true)
                ModelCell("排序", 48.dp, header = true)
                ModelCell("操作", 82.dp, header = true)
            }
            Divider1(AdminColors.gray200)

            if (models.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 29.44.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "暂无模型",
                        style = CCMText.body14.copy(fontSize = AdminText.sm),
                        color = AdminColors.gray400,
                    )
                }
            }

            models.forEach { m ->
                Row(modifier = Modifier.padding(horizontal = AdminSpacing.p4)) {
                    ModelCell(m.id, 156.dp, mono = true)
                    ModelCell(m.name, 92.dp)
                    ModelCell("${m.modelMultiplier}x", 66.dp)
                    ModelCell("${m.outputMultiplier}x", 66.dp)
                    Box(modifier = Modifier.width(60.dp).padding(vertical = AdminSpacing.p3)) {
                        if (m.enabled) {
                            AdminBadge(
                                text = "启用",
                                bg = AdminColors.blue50,
                                fg = AdminColors.blue600,
                            )
                        } else {
                            AdminBadge(
                                text = "断供",
                                bg = AdminColors.red50,
                                fg = AdminColors.red600,
                            )
                        }
                    }
                    ModelCell("${m.sort}", 48.dp)
                    Row(
                        modifier = Modifier.width(82.dp).padding(vertical = AdminSpacing.p3),
                        horizontalArrangement = Arrangement.spacedBy(AdminSpacing.p2),
                    ) {
                        Text(
                            text = "编辑",
                            style = CCMText.body12.copy(fontSize = AdminText.xs),
                            color = AdminColors.blue600,
                        )
                        Text(
                            text = "删除",
                            style = CCMText.body12.copy(fontSize = AdminText.xs),
                            color = AdminColors.red500,
                        )
                    }
                }
                Divider1(AdminColors.gray100)
            }
        }
    }
}

/** 模型表单元格 */
@Composable
private fun ModelCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    header: Boolean = false,
    mono: Boolean = false,
) {
    Box(modifier = Modifier.width(width).padding(vertical = AdminSpacing.p3)) {
        Text(
            text = text,
            style = CCMText.body14.copy(
                fontSize = if (header) AdminText.xs else AdminText.sm,
                fontWeight = if (header) FontWeight.Medium else FontWeight.Normal,
                fontFamily = if (mono) CcmMono else com.ccm.app.ui.theme.CcmSans,
            ),
            color = if (header) AdminColors.gray500 else AdminColors.gray700,
            maxLines = 1,
        )
    }
}

/** 带标签的输入框 —— 对应 `<div><label text-xs text-gray-500 mb-1>…</label><input …></div>` */
@Composable
private fun LabeledInput(
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    disabled: Boolean = false,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = CCMText.body12.copy(fontSize = AdminText.xs),
            color = AdminColors.gray500,
        )
        Spacer(Modifier.height(3.68.dp))            // mb-1
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.4.dp)
                .clip(RoundedCornerShape(7.36.dp))
                // disabled:bg-gray-50
                .background(if (disabled) AdminColors.gray50 else AdminColors.white)
                .border(1.dp, AdminColors.gray200, RoundedCornerShape(7.36.dp))
                .padding(horizontal = 11.04.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = placeholder,
                style = CCMText.body14.copy(fontSize = 14.72.sp),
                color = AdminColors.gray400,
            )
        }
    }
}

/** 带标签的下拉（只读展示） */
@Composable
private fun LabeledSelect(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = CCMText.body12.copy(fontSize = AdminText.xs),
            color = AdminColors.gray500,
        )
        Spacer(Modifier.height(3.68.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.4.dp)
                .clip(RoundedCornerShape(7.36.dp))
                .background(AdminColors.white)
                .border(1.dp, AdminColors.gray200, RoundedCornerShape(7.36.dp))
                .padding(horizontal = 11.04.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value,
                style = CCMText.body14.copy(fontSize = 14.72.sp),
                color = AdminColors.gray700,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 1px 分隔线（Admin 内部复用） */
@Composable
internal fun Divider1(color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color),
    )
}
