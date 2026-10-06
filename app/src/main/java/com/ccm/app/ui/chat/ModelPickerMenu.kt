package com.ccm.app.ui.chat

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.ccm.app.core.provider.ProviderStore
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme

/** Web ModelSelector 的 Compose 对应实现，直接锚在模型按钮父 Box。 */
@Composable
fun ModelPickerMenu(
    expanded: Boolean,
    dropUp: Boolean = false,
    items: List<ProviderStore.Item>,
    thinkingEnabled: Boolean = false,
    effort: String = "none",
    onPick: (providerId: String, model: String) -> Unit,
    onThinkingChange: (Boolean) -> Unit = {},
    onEffortChange: (String) -> Unit = {},
    onDismiss: () -> Unit,
) {
    if (!expanded) return
    val colors = CCMTheme.colors
    val density = LocalDensity.current
    val edge = with(density) { 8.dp.roundToPx() }
    val positionProvider = remember(dropUp, edge) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val x = (anchorBounds.right - popupContentSize.width).coerceIn(
                    edge,
                    (windowSize.width - popupContentSize.width - edge).coerceAtLeast(edge),
                )
                val below = anchorBounds.bottom + edge
                val above = anchorBounds.top - popupContentSize.height - edge
                val y = if (dropUp && above >= edge) above else if (!dropUp && below + popupContentSize.height <= windowSize.height - edge) below else above
                return IntOffset(x, y.coerceIn(edge, (windowSize.height - popupContentSize.height - edge).coerceAtLeast(edge)))
            }
        }
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, clippingEnabled = true),
    ) {
        var showEffortOptions by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .width(239.2.dp) // Web 260px × page zoom .92
                .heightIn(max = 420.dp)
                .shadow(12.dp, RoundedCornerShape(11.04.dp))
                .clip(RoundedCornerShape(11.04.dp))
                .background(colors.input)
                .border(1.dp, colors.border, RoundedCornerShape(11.04.dp))
                .verticalScroll(rememberScrollState())
                .padding(vertical = 3.68.dp), // Web py-1 × .92
        ) {
            if (items.isEmpty()) {
                Text(
                    "还没有可用模型",
                    style = CCMText.body14.copy(fontSize = 13.34.sp, fontWeight = FontWeight.Medium),
                    color = colors.textSecondary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.72.dp, vertical = 7.36.dp),
                )
            }
            items.forEach { provider ->
                val models = (listOf(provider.model) + provider.models).filter(String::isNotBlank).distinct()
                models.forEach { model ->
                    val selected = provider.isCurrent && model == provider.model
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = provider.enabled) { onPick(provider.id, model) }
                            .padding(horizontal = 14.72.dp, vertical = 7.36.dp), // Web px-4 py-2 × .92
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.36.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "$model(${provider.id})",
                                style = CCMText.body14.copy(fontSize = 13.34.sp, fontWeight = FontWeight.Medium),
                                color = if (provider.enabled) colors.textMain else colors.textSecondary.copy(alpha = .45f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (selected) Text("✓", color = Color(0xFF3B82F6), style = CCMText.body14.copy(fontSize = 16.56.sp))
                    }
                }
            }

            MenuDivider()
            // ★ 2026-10-03：档位列表原来追加在「扩展思考」行**下方** ——
            //   菜单贴着输入栏向上弹（dropUp），下方就是屏幕边缘，
            //   列表被 heightIn 截断，用户不下滑根本看不到。
            //   改为渲染在行的**上方**（对齐 Web 的 bottom-full 向上弹浮层），
            //   展开时把菜单往上撑，永远可见。
            if (showEffortOptions) {
                Text(
                    "思考强度",
                    style = CCMText.body11.copy(fontSize = 10.12.sp, fontWeight = FontWeight.Medium),
                    color = colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 14.72.dp, vertical = 3.68.dp),
                )
                listOf(
                    Triple("low", "低", "快速响应"),
                    Triple("medium", "中", "日常任务"),
                    Triple("high", "高", "复杂问题"),
                    Triple("xhigh", "极高", "深度分析"),
                    Triple("max", "最大", "尽可能深入"),
                ).forEach { (key, label, hint) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 5.52.dp)
                            .clip(RoundedCornerShape(7.36.dp))
                            .background(if (effort == key) colors.hover else Color.Transparent)
                            .clickable { onEffortChange(key); showEffortOptions = false }
                            .padding(horizontal = 9.2.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.36.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(5.52.dp)
                                    .clip(CircleShape)
                                    .background(if (effort == key) colors.accent else colors.border),
                            )
                            Text(
                                label,
                                style = CCMText.body12.copy(fontSize = 11.5.sp),
                                color = if (effort == key) colors.textMain else colors.textSecondary,
                            )
                        }
                        Text(
                            hint,
                            style = CCMText.body11.copy(fontSize = 9.2.sp),
                            color = colors.textSecondary,
                        )
                    }
                }
                MenuDivider()
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.72.dp, vertical = 7.36.dp), // Web px-4 py-2 × .92
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("扩展思考", style = CCMText.body14.copy(fontSize = 13.34.sp, fontWeight = FontWeight.Medium), color = colors.textMain)
                    Spacer(Modifier.height(1.dp))
                    // 【2026-10-06 问题8】用户报「多处地方都能调节思考强度，
                    // 不知道调哪个」。这里加一句说明：三个入口改的是**同一份**
                    // 配置（Provider 的 effort 字段），在哪调都一样。
                    Text(
                        "为复杂任务进行更深入的思考 · 与设置页同一份配置",
                        style = CCMText.body12.copy(fontSize = 11.5.sp),
                        color = colors.textSecondary,
                    )
                }
                Text(
                    text = effortLabel(effort),
                    style = CCMText.body11.copy(fontSize = 10.12.sp, fontWeight = FontWeight.Medium),
                    color = colors.textSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.52.dp))
                        .clickable { showEffortOptions = !showEffortOptions }
                        .padding(horizontal = 7.36.dp, vertical = 4.dp),
                )
                Box(
                    modifier = Modifier
                        .size(width = 36.8.dp, height = 22.08.dp)
                        .clip(CircleShape)
                        .background(if (thinkingEnabled) Color(0xFF3A6FE0) else colors.border)
                        .clickable { if (items.any { it.isCurrent && it.enabled }) onThinkingChange(!thinkingEnabled) }
                        .padding(2.76.dp),
                    contentAlignment = if (thinkingEnabled) Alignment.CenterEnd else Alignment.CenterStart,
                ) {
                    Box(Modifier.size(16.56.dp).shadow(1.dp, CircleShape).background(Color.White, CircleShape))
                }
            }
        }
    }
}

@Composable
private fun MenuDivider() {
    val colors = CCMTheme.colors
    Box(Modifier.fillMaxWidth().padding(horizontal = 14.72.dp, vertical = 3.68.dp).height(.92.dp).background(colors.border))
}

private fun effortLabel(effort: String): String = when (effort.lowercase()) {
    "low" -> "低"
    "medium" -> "中"
    "high" -> "高"
    "xhigh" -> "极高"
    "max" -> "最大"
    "none", "" -> "关闭"
    else -> effort
}
