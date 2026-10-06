package com.ccm.app.ui.admin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CcmMono

/**
 * Admin · Dashboard —— 对齐 `AdminDashboard.tsx`（约 200 行）。
 *
 * ## 源码结构
 * 1. 9 个统计卡（`grid grid-cols-2 md:grid-cols-3 lg:grid-cols-5 gap-4`）
 *    - 总用户 / 今日新增 / 今日消息 / 今日 Token(入) / 今日 Token(出) /
 *      密钥池(可用/总) / 活跃订阅 / 今日成本 / 今日收入
 * 2. 折线图「活跃用户 & 新注册」（recharts LineChart，高 240）
 * 3. 柱状图「每日成本」（recharts BarChart）
 * 4. 天数切换（7/30/90）
 *
 * ## ★ 图表处理
 * 源码用 recharts（React 图表库）。Compose 没有等价物，这里用 **Canvas 手绘**
 * 折线/柱状图 —— 保持视觉形态（网格线、轴标签、图例）一致，不引入第三方图表库。
 *
 * 实测（Tailwind × 0.92）：
 * - 卡片：`p-5 rounded-xl` → 内距 18.40 / 圆角 11.04
 * - 图标底色块：`w-10 h-10 rounded-lg` → 36.8 / 圆角 7.36
 * - 数值：`text-2xl font-semibold` → 22.08
 * - 标签：`text-xs text-gray-500` → 11.04
 * - 网格：`grid-cols-2 gap-4` → 两列 / 间距 14.72
 */
@Composable
fun AdminDashboardPage(modifier: Modifier = Modifier) {
    var days by remember { mutableStateOf(30) }

    AdminPage(modifier = modifier.verticalScroll(rememberScrollState())) {
        AdminPageHeader(title = "Dashboard") {
            AdminButton(label = "刷新", onClick = { })
        }

        // ── 统计卡网格（移动端 grid-cols-2）───────────────────────
        // 源码是 9 张卡，两列布局 → 5 行（最后一行单卡）
        val cards = listOf(
            Triple("总用户", "1,284", AdminColors.blue500),
            Triple("今日新增", "23", AdminColors.green500),
            Triple("今日消息", "4,521", AdminColors.purple500),
            Triple("今日 Token (入)", "1.2M", AdminColors.orange500),
            Triple("今日 Token (出)", "847.3K", AdminColors.red500),
            Triple("密钥池 (可用/总)", "11/11", AdminColors.cyan500),
            Triple("活跃订阅", "36", AdminColors.indigo500),
            Triple("今日成本", "$12.4800", AdminColors.rose500),
            Triple("今日收入", "¥328.00", AdminColors.green500),
        )

        Column(verticalArrangement = Arrangement.spacedBy(AdminSpacing.gap4)) {
            cards.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(AdminSpacing.gap4)) {
                    row.forEach { (label, value, color) ->
                        StatCard(
                            label = label,
                            value = value,
                            accent = color,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // 奇数个时补空位，保持左对齐
                    if (row.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        Spacer(Modifier.height(AdminSpacing.p6))

        // ── 天数切换 ─────────────────────────────────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(AdminSpacing.p2)) {
            listOf(7, 30, 90).forEach { d ->
                AdminButton(
                    label = "$d 天",
                    onClick = { days = d },
                    variant = if (days == d) AdminButtonVariant.PRIMARY else AdminButtonVariant.SECONDARY,
                    small = true,
                )
            }
        }

        Spacer(Modifier.height(AdminSpacing.p4))

        // ── 折线图：活跃用户 & 新注册 ────────────────────────────
        AdminCard {
            AdminSectionTitle(text = "活跃用户 & 新注册")
            Spacer(Modifier.height(AdminSpacing.p3))
            LineChart(
                series = listOf(
                    ChartSeries("活跃用户", AdminColors.blue500, listOf(120f, 180f, 150f, 220f, 260f, 240f, 300f, 340f)),
                    ChartSeries("新注册", AdminColors.green500, listOf(10f, 18f, 15f, 22f, 26f, 24f, 30f, 34f)),
                ),
                labels = listOf("9-20", "9-21", "9-22", "9-23", "9-24", "9-25", "9-26", "9-27"),
                height = 220.8.dp,
            )
            Spacer(Modifier.height(AdminSpacing.p3))
            // 图例
            Row(horizontalArrangement = Arrangement.spacedBy(AdminSpacing.p4)) {
                LegendDot("活跃用户", AdminColors.blue500)
                LegendDot("新注册", AdminColors.green500)
            }
        }

        Spacer(Modifier.height(AdminSpacing.p4))

        // ── 柱状图：每日成本 ────────────────────────────────────
        AdminCard {
            AdminSectionTitle(text = "每日成本")
            Spacer(Modifier.height(AdminSpacing.p3))
            BarChart(
                values = listOf(8f, 12f, 9f, 15f, 11f, 18f, 14f),
                labels = listOf("9-21", "9-22", "9-23", "9-24", "9-25", "9-26", "9-27"),
                color = AdminColors.orange500,
                height = 220.8.dp,
            )
        }
    }
}

/**
 * 统计卡 —— 对应源码
 * `<div className="bg-white rounded-xl border border-gray-200 p-5">
 *    <div className="flex items-center justify-between">
 *      <div><div className="text-xs text-gray-500">label</div>
 *           <div className="text-2xl font-semibold text-gray-800 mt-1">value</div></div>
 *      <div className="w-10 h-10 rounded-lg ${color} flex items-center justify-center">
 *        <Icon className="text-white" size={20}/></div>
 *    </div>
 *  </div>`
 *
 * 实测（Tailwind × 0.92）：内距 18.40 · 圆角 11.04 · 图标块 36.8/圆角 7.36 ·
 * 数值 22.08 · 标签 11.04 · `mt-1` 3.68。
 */
@Composable
private fun StatCard(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(11.04.dp))
            .background(AdminColors.white)
            .padding(AdminSpacing.p5),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = CCMText.body12.copy(fontSize = AdminText.xs),
                    color = AdminColors.gray500,
                    maxLines = 2,
                )
                Spacer(Modifier.height(3.68.dp))        // mt-1
                Text(
                    text = value,
                    style = CCMText.body24.copy(
                        fontSize = AdminText.xl2,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = AdminColors.gray800,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(AdminSpacing.p2))
            Box(
                modifier = Modifier
                    .size(36.8.dp)                      // w-10 h-10
                    .clip(RoundedCornerShape(7.36.dp))
                    .background(accent),
                contentAlignment = Alignment.Center,
            ) {
                // 图标用白色方块占位（源码是 lucide 图标）
                Box(
                    modifier = Modifier
                        .size(18.4.dp)
                        .clip(RoundedCornerShape(3.68.dp))
                        .background(Color.White.copy(alpha = 0.85f)),
                )
            }
        }
    }
}

/** 图表数据序列 */
private data class ChartSeries(val name: String, val color: Color, val points: List<Float>)

/**
 * 折线图 —— 替代 recharts `<LineChart>`。
 *
 * 源码配置：`CartesianGrid strokeDasharray="3 3"` / `XAxis tick={{fontSize:11}}` /
 * `YAxis tick={{fontSize:11}}` / `ResponsiveContainer height={240}`。
 *
 * 实测换算：图表高 240 → **220.8**，刻度字号 11 → **10.12**，
 * 网格线 `#E5E7EB` 虚线。
 */
@Composable
private fun LineChart(
    series: List<ChartSeries>,
    labels: List<String>,
    height: androidx.compose.ui.unit.Dp,
) {
    Column {
        Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
            val w = this.size.width
            val h = this.size.height
            val padLeft = 34.dp.toPx()          // Y 轴标签区
            val padBottom = 18.dp.toPx()        // X 轴标签区
            val plotW = w - padLeft
            val plotH = h - padBottom

            // 网格线（水平 5 条）
            repeat(5) { i ->
                val y = plotH * i / 4f
                drawLine(
                    color = AdminColors.gray200,
                    start = Offset(padLeft, y),
                    end = Offset(w, y),
                    strokeWidth = 1f,
                )
            }

            // 各序列折线
            //
            // 【2026-10-06 修两处】
            // ① maxV 原来写在 forEach **里面** —— 每条序列都把整个 series
            //    扫一遍求最大值（O(n²)）。提到循环外（循环不变量）。
            // ② 补零值保护（coerceAtLeast(1f)）—— 同文件的 BarChart 有，
            //    LineChart 没有。全零数据时 0f/0f = NaN，path.lineTo(NaN, NaN)
            //    的绘制行为未定义（接入真实数据后可能出现）。
            val maxV = (series.maxOfOrNull { it.points.maxOrNull() ?: 0f } ?: 0f)
                .coerceAtLeast(1f)
            series.forEach { s ->
                val step = plotW / (s.points.size - 1).coerceAtLeast(1)
                val path = androidx.compose.ui.graphics.Path()
                s.points.forEachIndexed { i, v ->
                    val x = padLeft + step * i
                    val y = plotH - (v / maxV) * plotH
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = s.color,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 2f,
                        cap = StrokeCap.Round,
                    ),
                )
                // 数据点
                s.points.forEachIndexed { i, v ->
                    val x = padLeft + step * i
                    val y = plotH - (v / maxV) * plotH
                    drawCircle(s.color, radius = 2.5f, center = Offset(x, y))
                }
            }
        }
        // X 轴标签（Compose 的 Canvas 里画文字要 nativeCanvas，改用 Row 排版更简单）
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 34.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            labels.forEach { l ->
                Text(
                    text = l,
                    style = CCMText.body11.copy(fontSize = 10.12.sp),
                    color = AdminColors.gray500,
                )
            }
        }
    }
}

/**
 * 柱状图 —— 替代 recharts `<BarChart>`。
 *
 * 源码配置：`<Bar dataKey="cost" fill="#f97316" />` + 同样的网格与轴。
 * 实测换算：柱色 `#F97316`，圆角由 recharts 默认（无）。
 */
@Composable
private fun BarChart(
    values: List<Float>,
    labels: List<String>,
    color: Color,
    height: androidx.compose.ui.unit.Dp,
) {
    Column {
        Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
            val w = this.size.width
            val h = this.size.height
            val padLeft = 34.dp.toPx()
            val padBottom = 18.dp.toPx()
            val plotW = w - padLeft
            val plotH = h - padBottom

            // 网格线
            repeat(5) { i ->
                val y = plotH * i / 4f
                drawLine(
                    color = AdminColors.gray200,
                    start = Offset(padLeft, y),
                    end = Offset(w, y),
                    strokeWidth = 1f,
                )
            }

            val maxV = values.max().coerceAtLeast(1f)
            val slot = plotW / values.size
            val barW = slot * 0.6f
            values.forEachIndexed { i, v ->
                val bh = (v / maxV) * plotH
                val x = padLeft + slot * i + (slot - barW) / 2f
                drawRect(
                    color = color,
                    topLeft = Offset(x, plotH - bh),
                    size = androidx.compose.ui.geometry.Size(barW, bh),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 34.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            labels.forEach { l ->
                Text(
                    text = l,
                    style = CCMText.body11.copy(fontSize = 10.12.sp),
                    color = AdminColors.gray500,
                )
            }
        }
    }
}

/** 图例项 —— recharts `<Legend>` 的等效物 */
@Composable
private fun LegendDot(label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AdminSpacing.p2),
    ) {
        Box(
            modifier = Modifier
                .size(9.2.dp)
                .clip(RoundedCornerShape(1.84.dp))
                .background(color),
        )
        Text(
            text = label,
            style = CCMText.body12.copy(fontSize = AdminText.xs),
            color = AdminColors.gray600,
        )
    }
}

/** Token 数量格式化 —— 对应源码 `formatTokens()` */
fun formatTokens(n: Long): String = when {
    n >= 1_000_000 -> "${(n / 100_000) / 10.0}M"
    n >= 1_000 -> "${(n / 100) / 10.0}K"
    else -> n.toString()
}

/** 金额格式化（分 → 元）—— 对应源码 `formatYuan()` */
fun formatYuan(fen: Long): String = "¥${(fen / 100.0).let { "%.2f".format(it) }}"

/** 成本格式化（cost_units → 美元）—— 对应源码 `todayCostDollar` */
fun formatCostDollar(units: Long): String = "$${units / 10000.0}"

/** 未使用但保留：等宽数值展示 */
@Composable
private fun MonoValue(text: String) {
    Text(
        text = text,
        style = CCMText.body14.copy(fontSize = AdminText.sm, fontFamily = CcmMono),
        color = AdminColors.gray800,
    )
}
