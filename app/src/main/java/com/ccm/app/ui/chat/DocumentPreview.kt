package com.ccm.app.ui.chat

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 文档预览的 WebView 渲染层（html / svg / mermaid）。
 *
 * 【2026-10-11 从 PresentFrame 改造】Present 工具下线，改为「Agent 写文件 →
 * UI 推导文档卡片 → 点开预览」。渲染层本身（沙箱 + 高度自适应）完全保留，
 * 去掉了只为 Present 服务的那套参数滑块（params / 热更新 / 占位符）。
 *
 * ══════════════════════════════════════════════════════════════
 *  🔐 安全模型（改这段前先读）
 * ══════════════════════════════════════════════════════════════
 *
 * Web 端用 `<iframe sandbox="allow-scripts">`：**允许脚本，但不给 same-origin**
 * —— 脚本跑得起来，却摸不到父页面。Android 没有 sandbox 属性，等价物是四层：
 *
 * 1. **禁一切外部资源**：`shouldInterceptRequest` 只放行 `data:` / `about:`
 *    / `blob:` / 本包 assets，其余（http/https/file）一律返回空响应。
 *    → 脚本发不出网络请求（fetch/XHR/`<img>`/`<script src>` 全部走这条路）。
 * 2. **禁跳转**：`shouldOverrideUrlLoading` 拦掉一切离页导航。
 * 3. **禁本地文件**：`allowFileAccess=false` + `allowFileAccessFromFileURLs=false`
 *    + `allowUniversalAccessFromFileURLs=false` —— 页面的 baseUrl 虽然是
 *    `file:///android_asset/`，但脚本读不到任何本地文件。
 * 4. **JS 桥只暴露一个方法**：见 [DocumentHeightBridge]。
 *
 * ⚠️ 预览的是**模型生成的内容**，默认按不可信输入对待。放开任何一条前先想清楚
 * 「一个恶意 HTML 能拿它干什么」。
 *
 * ## 为什么不禁用 JS
 * svg 的动画、mermaid 的渲染都要 JS。禁 JS 等于把预览变成静态截图。
 * 四层防护已经把「脚本能做什么」压到只剩「在沙箱里画画」。
 *
 * @param content 文档内容（html / svg / mermaid 源码）
 * @param format 格式名（`html` / `svg` / `mermaid`；其它值按 html 处理）
 * @param height 容器高度（由调用方按内容高度自适应）
 * @param onHeightChanged 内容真实高度变化回调（页面自报 + WebView 兜底各报一次）
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DocumentPreview(
    content: String,
    format: String,
    height: Dp,
    onHeightChanged: (Dp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val kind = format.lowercase()
    val isMermaid = kind == "mermaid"

    // 密度从 Compose 取（`Resources.getSystem()` 拿到的是系统默认密度，
    // 用户改过显示大小 / 用了 per-app density 时会和实际渲染不符）
    val density = androidx.compose.ui.platform.LocalDensity.current
    val toDp: (Int) -> Dp = remember(density) {
        { px -> with(density) { px.toFloat().toDp() } }
    }

    // 最终 HTML。只在 content/format 变化时重算 —— 高度变化不重建页面
    // （否则每次高度回调都重新加载，动画从头播、还可能死循环）。
    val preview = remember(kind, content) {
        when {
            // mermaid 要带高度运行时 —— 图是**异步**渲染的（startOnLoad 在 load
            // 之后才画完），不注入上报脚本的话容器高度永远停在初始值
            isMermaid -> buildMermaidHtml(content, documentHeightRuntime())
            kind == "svg" -> {
                // 模型可能只给了 <path>/<circle> 片段（没包 <svg>）—— 补上外壳
                val svg = if (content.contains("<svg", ignoreCase = true)) {
                    content
                } else {
                    "<svg xmlns=\"http://www.w3.org/2000/svg\">$content</svg>"
                }
                wrapDocumentSvg(svg)
            }
            else -> wrapDocumentHtml(content)
        }
    }

    AndroidView(
        modifier = modifier.fillMaxWidth().height(height),
        factory = { context -> WebView(context).apply {
            settings.javaScriptEnabled = true
            // 见文件头安全模型：不开 domStorage（预览不需要持久化落脚点）
            settings.domStorageEnabled = false
            // mermaid 要从 assets 读 mermaid.min.js；其余分支不需要文件访问，
            // 关掉后即使有脚本也读不到本地文件（第 3 层防线）。
            settings.allowFileAccess = isMermaid
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            // 不允许脚本自己开新窗口（预览没有多窗口语义）
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)

            // 第 4 层防线：只暴露一个纯数据方法
            addJavascriptInterface(
                DocumentHeightBridge(this) { px -> onHeightChanged(toDp(px)) },
                BRIDGE_NAME,
            )

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val scheme = request.url.scheme?.lowercase()
                    val allow = when (scheme) {
                        // data/about：loadDataWithBaseURL 的内联内容
                        "data", "about" -> true
                        // blob：内存对象（canvas.toBlob 之类），不碰文件与网络
                        "blob" -> true
                        // 只有 mermaid 需要读本包 assets
                        "file" -> isMermaid && request.url.toString().contains("/android_asset/")
                        else -> false
                    }
                    return if (allow) {
                        null
                    } else {
                        // 空响应：脚本/标签拿到的就是「资源不存在」
                        WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    // 返回 true = 拦下。只放行渲染自身需要的 data/about；
                    // file 也收紧到本包 assets（原来放行所有 file://，虽然
                    // allowFileAccess=false 已经兜底，但没必要留口子）。
                    val url = request.url
                    val scheme = url.scheme?.lowercase()
                    val allow = scheme == "data" || scheme == "about" ||
                        (scheme == "file" && url.toString().contains("/android_asset/"))
                    return !allow
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    // 兜底高度：JS 桥没上报时（脚本被内容里的 CSP 之类挡住、
                    // 或页面根本没注入运行时）用 WebView 自己量的高度。
                    try {
                        val px = view.contentHeight
                        if (px > 0) onHeightChanged(toDp(px))
                    } catch (_: Throwable) {
                    }
                }
            }
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        } },
        update = { web ->
            if (web.tag != preview) {
                // 新内容 → 真正重新加载
                web.tag = preview
                // baseUrl 让 <script src="mermaid.min.js"> 解析到
                // file:///android_asset/mermaid.min.js；html/svg 无外链不受影响。
                web.loadDataWithBaseURL(
                    "file:///android_asset/",
                    preview,
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
        },
    )
}

/** JS 桥的注册名。前面加下划线避免和页面自己的变量撞名。 */
private const val BRIDGE_NAME = "__ccmDocument"

/**
 * JS → 原生的窄接口：**只有高度上报一个方法**。
 *
 * ## 为什么需要它
 * 内容高度只能由页面自己算（`documentElement.scrollHeight` 要等布局完成）。
 * Web 端靠 `parent.postMessage({__present:'height'})`，Android 的等价物就是这个桥。
 *
 * ## 为什么安全
 * - 方法签名只有 `Int`，**没有 String 参数、没有返回值** —— 没法当数据外带通道
 * - 类里没有其他 public 方法（`@JavascriptInterface` 之外的成员对 JS 不可见，
 *   但这里连额外成员都不给）
 * - 回调走 `webView.post` 回主线程，不在 JS 线程碰 Compose 状态
 *
 * ⚠️ 往这个类里加方法前先问：这个方法会不会让页面里的脚本获得
 * 读文件 / 发请求 / 执行命令的能力？会就别加。
 */
private class DocumentHeightBridge(
    private val webView: WebView,
    private val onHeightPx: (Int) -> Unit,
) {
    @JavascriptInterface
    fun reportHeight(px: Int) {
        // 只在合理范围内采信：0 是「还没布局完」，过大是脚本乱报
        if (px <= 0 || px > 100_000) return
        webView.post { onHeightPx(px) }
    }
}

// ──────────────────────────────────────────────────────────────
//  HTML 包装（对齐 Web 端 PresentBlock.tsx 的同名函数）
// ──────────────────────────────────────────────────────────────

/**
 * 注入到页面的高度上报运行时。
 *
 * 原来这份 JS 还负责参数热更新（CSS 变量 / window.PARAMS / 速度参数接管），
 * Present 下线后只剩**高度自报**一件事 —— 三次上报（load / +60ms / +400ms）
 * 覆盖图片加载完后的二次布局。
 *
 * ⚠️ 这份 JS 用 Kotlin 原始字符串书写，**里面不能出现 `$` 后跟标识符**
 * （会被 Kotlin 当模板插值）。
 */
internal fun documentHeightRuntime(): String = """
<script>
(function(){
  function reportHeight(){
    try {
      var h = Math.max(
        document.documentElement.scrollHeight || 0,
        document.body ? document.body.scrollHeight : 0
      );
      if (h > 0 && window.$BRIDGE_NAME && window.$BRIDGE_NAME.reportHeight) {
        window.$BRIDGE_NAME.reportHeight(Math.round(h));
      }
    } catch(e){}
  }
  window.__ccmDocumentReportHeight = reportHeight;
  window.addEventListener('load', reportHeight);
  setTimeout(reportHeight, 60);
  setTimeout(reportHeight, 400);
})();
</script>
"""

/**
 * 包一层最小 HTML 骨架展示 SVG。
 *
 * 对齐 Web 的 `wrapSvg`。**关键点**（Web 端 2026-09-19 踩过）：
 * 不要给 `html,body` 设 `height:100%` —— 那会让 body 高度等于 WebView 初始高度，
 * `svg{max-height:100%}` 被这个死高度卡住，比初始高度高的图直接被压扁，
 * 而且上报高度的脚本量到的永远是被压过的值，容器也就永远不涨。
 * 正确做法：body 高度跟随内容，svg 宽度撑满、高度按自身比例。
 */
internal fun wrapDocumentSvg(svg: String): String =
    """<!DOCTYPE html><html><head><meta charset="utf-8"><style>
html,body{margin:0;padding:0;background:transparent}
/* ⚠️ 不要用 display:flex 居中 —— flex 容器里 body 的 scrollHeight
   计算方式不同（flex 项可能不撑开父容器），导致高度上报不准 →
   容器高度不对 → SVG 被裁。用普通块级布局 + margin:auto 居中更稳。 */
body{padding:8px;box-sizing:border-box;text-align:center}
/* 【2026-10-11 修「svg 显示不全」】
   SVG 常自带固定尺寸属性（如 width="400" height="300"）——
   那个**属性**的优先级高于 CSS 的 height:auto，导致：
     · 400px 宽 > 屏幕宽 → 被裁（要手动滑）
     · height:auto 失效 → 高度按 300px 硬撑，与容器不匹配
   用 `!important` 强制覆盖两个属性：
     · width:100% → 撑满容器宽度
     · height:auto → 高度按 viewBox 比例自适应（关键：有了它，
       SVG 会按宽度等比缩放，不再被固定尺寸卡住）
   注意 `max-height:none` 也要加 —— 有些 SVG 自带 style 里限制了高度。 */
svg{width:100%!important;height:auto!important;max-width:100%!important;max-height:none!important;display:block}
</style>${documentHeightRuntime()}</head><body>$svg</body></html>"""

/**
 * html 类型：把高度上报运行时注入到用户 HTML 里。
 *
 * 有 `<head>` 就插进去；只有 `<html>` 就补一个 head；都没有就整体包一层。
 * 对齐 Web 的 `wrapHtml`。
 */
internal fun wrapDocumentHtml(html: String): String {
    val runtime = documentHeightRuntime()
    // 【为什么不用 Regex.replaceFirst(input) { transform }】那个重载在这里
    // 解析失败（CI 报 "actual type is Function1 but String was expected"）——
    // 编译器选了 String 版本的重载。改用 find() + 手工拼接，语义完全等价
    // 且不依赖重载解析。
    val headRe = Regex("<head[^>]*>", RegexOption.IGNORE_CASE)
    headRe.find(html)?.let { m ->
        // 有 <head>：运行时插在它后面
        return html.substring(0, m.range.last + 1) + runtime + html.substring(m.range.last + 1)
    }
    val htmlRe = Regex("<html[^>]*>", RegexOption.IGNORE_CASE)
    htmlRe.find(html)?.let { m ->
        // 只有 <html>：补一个 head
        return html.substring(0, m.range.last + 1) + "<head>$runtime</head>" + html.substring(m.range.last + 1)
    }
    // 都没有：整体包一层
    return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">$runtime</head><body>$html</body></html>"
}
