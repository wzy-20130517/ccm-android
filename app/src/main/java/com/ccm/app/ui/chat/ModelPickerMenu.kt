package com.ccm.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccm.app.core.provider.ProviderStore
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme

/** 独立模型菜单：DropdownMenu 锚定到 InputBar 中的模型按钮 Box。 */
@Composable
fun ModelPickerMenu(
    expanded: Boolean,
    items: List<ProviderStore.Item>,
    onPick: (providerId: String, model: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CCMTheme.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(260.dp),
    ) {
        if (items.isEmpty()) {
            DropdownMenuItem(
                text = { Text("还没有 Provider —— 到「设置 → 模型」里先加一个") },
                onClick = onDismiss,
            )
        }
        items.forEach { provider ->
            val models = (listOf(provider.model) + provider.models)
                .filter(String::isNotBlank).distinct()
            models.forEach { model ->
                val selected = provider.isCurrent && model == provider.model
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "$model(${provider.id})",
                                style = CCMText.body14.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                                color = if (provider.enabled) colors.textMain else colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (selected) Text("✓", color = androidx.compose.ui.graphics.Color(0xFF3B82F6))
                        }
                    },
                    onClick = { if (provider.enabled) onPick(provider.id, model) },
                    enabled = provider.enabled,
                )
            }
        }
    }
}
