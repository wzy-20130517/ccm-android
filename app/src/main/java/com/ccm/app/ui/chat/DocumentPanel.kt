package com.ccm.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ccm.app.R
import com.ccm.app.ui.common.PainterIcon
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme

/**
 * 文档全屏预览面板 —— 点文档卡片打开。
 *
 * 对齐 Web 的 `web/src/components/DocumentPanel.tsx`（全屏抽屉：顶栏 + 内容区）。
 * APK 侧用 `Dialog` 实现（`usePlatformDefaultWidth = false` 才能占满宽度）。
 *
 * ## 渲染分支（按格式）
 * | 格式 | 渲染方式 | 理由 |
 * |---|---|---|
 * | `html` | [DocumentPreview] 沙箱 WebView | 要真渲染 HTML/CSS |
 * | `svg` | [DocumentPreview] 沙箱 WebView | 同上，SVG 也是浏览器活 |
 * | `markdown` | [MarkdownRenderer] | 项目已有渲染器，不必绕 WebView |
 * | 其它（text/json/yaml/…） | 等宽 `Text` | 纯文本，等宽最易读 |
 *
 * ## 为什么 markdown 不走 WebView
 * 项目已有 [MarkdownRenderer]（解析 + 代码块 + 复制按钮，样式与对话一致）。
 * 走 WebView 就得再引一套 JS markdown 库、样式还得重新对齐 —— 没理由。
 *
 * ## 为什么 html/svg 必须走 WebView
 * 它们需要浏览器引擎（CSS 布局、SVG 绘制、JS 动画）。Compose 没有等价能力。
 *
 * @param filename 文件名（顶栏显示）
 * @param content  文件内容
 * @param format   格式名（`DocumentMeta.resolveDocumentFormat` 的产物）
 * @param onDismiss 关闭
 */
@Composable
fun DocumentPanel(
    filename: String,
    content: String,
    format: String,
    onDismiss: () -> Unit,
) {
    val colors = CCMTheme.colors
    val ctx = LocalContext.current
    val screenH = LocalConfiguration.current.screenHeightDp.dp

    // 复制按钮的「已复制」反馈（1.5s 后自动复原）
    var copied by remember(filename) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1500)
            copied = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        // usePlatformDefaultWidth=false 才能撑满宽度（默认 Dialog 有 280dp 左右的上限）
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.bgMain)
                // 全屏 Dialog 是独立窗口，拿不到主界面的 insets 补偿 ——
                // 不加这个的话顶栏会被状态栏压住、底栏被导航栏压住
                // （与 CcmApp.kt 的 .windowInsetsPadding(WindowInsets.statusBars) 同理）。
                .windowInsetsPadding(
                    WindowInsets.statusBars.union(WindowInsets.navigationBars),
                ),
        ) {
            // ── 顶栏：文件名 + 复制 + 关闭 ──────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.bgSidebar)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = filename,
                    style = CCMText.body14SemiBold,
                    color = colors.textMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 复制按钮
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            try {
                                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText(filename, content))
                                copied = true
                            } catch (_: Throwable) {
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (copied) {
                        Text("✓", style = CCMText.body14, color = colors.success)
                    } else {
                        PainterIcon(R.drawable.ic_copy, size = 17.dp, tint = colors.textSecondary)
                    }
                }
                // 关闭按钮
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    PainterIcon(R.drawable.ic_close, size = 17.dp, tint = colors.textSecondary)
                }
            }

            // ── 内容区 ──────────────────────────────────────────────────
            Box(modifier = Modifier.fillMaxSize()) {
                when (format.lowercase()) {
                    // html / svg / mermaid → 沙箱 WebView（要浏览器引擎）
                    "html", "svg", "mermaid" -> {
                        // 高度：先给一屏，页面自报后跟着涨（内容比屏幕高时
                        // WebView 自己会滚 —— 不要再套 verticalScroll，
                        // 两层滚动嵌套会互相抢手势）
                        var webHeight by remember(content) { mutableStateOf(screenH) }
                        DocumentPreview(
                            content = content,
                            format = format,
                            height = webHeight.coerceIn(200.dp, screenH * 3),
                            onHeightChanged = { webHeight = it },
                        )
                    }
                    // markdown → 项目已有的渲染器（与对话正文同一套样式）
                    "markdown" -> {
                        MarkdownRenderer(
                            content = content,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                    // 其它（text / json / yaml / xml / csv …）→ 等宽文本
                    else -> {
                        Text(
                            text = content,
                            style = CCMText.body12.copy(fontFamily = FontFamily.Monospace),
                            color = colors.textModelBody,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 文档卡片列表 —— 一条消息里被写过的文件（推导自 toolCards，见
 * `ChatSession.deriveDocuments`）。
 *
 * 对齐 Web：`MainContent.tsx:1355-1375`（在消息正文之后渲染 DocumentCard）。
 *
 * @param documents 推导出的文档（已按扩展名过滤）
 * @param onOpen    点卡片（打开 [DocumentPanel]）
 */
@Composable
fun DocumentCardList(
    documents: List<com.ccm.app.core.ChatSession.DocumentEntry>,
    onOpen: (com.ccm.app.core.ChatSession.DocumentEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (documents.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        documents.forEach { doc ->
            com.ccm.app.ui.common.DocumentCard(
                // displayName：内联 artifact 用 title 属性，文件来源用文件名
                title = doc.displayName,
                // 副标题用 DocumentMeta 生成（`Document · MARKDOWN`），
                // 与 Web 的 getDocumentSubtitle 一致 —— 不自己拼字符串。
                //
                // 【2026-10-11 修】format 参数原来写死空串 —— 文件来源靠扩展名
                // 能推断出来，但**内联 artifact 没有文件名**（path 为空），
                // 只能靠它的 format 属性。不传进去的话 html/svg 全被当成 text。
                subtitle = com.ccm.app.ui.common.DocumentMeta.getDocumentSubtitle(
                    com.ccm.app.ui.common.DocumentMeta.resolveDocumentFormat(
                        format = doc.inlineFormat,
                        filename = doc.filename,
                        content = doc.content,
                    ),
                ),
                onOpen = { onOpen(doc) },
            )
        }
    }
}
