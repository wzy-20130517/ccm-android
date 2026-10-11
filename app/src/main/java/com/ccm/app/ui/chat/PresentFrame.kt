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
import com.ccm.app.core.tool.PresentParam
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.atomic.AtomicReference

/**
 * Present 展示块的 WebView 渲染层（`kind` = svg / html / mermaid）。
 *
 * 对齐 Web 端 `web/src/components/PresentBlock.tsx` 的 iframe 分支 ——
 * 包括参数热更新、速度参数接管、高度自报三件事。
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
 * 4. **JS 桥只暴露一个方法**：见 [PresentHeightBridge]，只有 `reportHeight(Int)`，
 *    纯数字回传，**没有文件/网络/命令能力**。
 *
 * ⚠️ 这里刻意**不开** `domStorageEnabled`：localStorage 对展示块没有意义，
 * 但会给脚本一块可持久化的落脚点（跨消息残留）。要开的话先想清楚。
 *
 * ## 与 Web 的差异（有意为之）
 * - Web 的 mermaid 分支不注入参数运行时（拖滑块没反应）；这里**统一注入**，
 *   三种 kind 的 params 行为一致。
 * - Web 靠 `postMessage` 上报高度；这里靠 JS 桥回调 + `contentHeight` 兜底。
 */
@SuppressLint("JavascriptInterface", "SetJavaScriptEnabled")
@Composable
internal fun PresentFrame(
    kind: String,
    content: String,
    params: List<PresentParam>,
    values: Map<String, Double>,
    height: Dp,
    onHeightChanged: (Dp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isMermaid = kind == "mermaid"

    // 密度从 Compose 取（`Resources.getSystem()` 拿到的是系统默认密度，
    // 用户改过显示大小 / 用了 per-app density 时会和实际渲染不符）
    val density = androidx.compose.ui.platform.LocalDensity.current
    val toDp: (Int) -> Dp = remember(density) {
        { px -> with(density) { px.toFloat().toDp() } }
    }

    // 初始值：**首帧**用它构建 HTML，之后不再参与 —— 否则拖滑块会重建
    // 整个页面、动画从头播。这与 Web 的 `initialValues`（useMemo 只依赖 payload.at）同义。
    val initialValues = remember(content, params) { params.associate { it.name to it.value } }
    // content 里写了 {{name}} → 只能整体重渲染（占位符在 HTML 里，没法热替换）
    val placeholderMode = remember(content, params) { presentUsesPlaceholder(content, params) }

    // 最终 HTML。非占位符模式下 values 变化**不会**改变它（用的还是 initialValues），
    // 所以 AndroidView 的 `web.tag != preview` 守卫不会误判重建 ——
    // String 的 `!=` 是结构相等，内容一样就不重建。
    val preview = remember(kind, content, initialValues, placeholderMode, values) {
        val frameSource = if (placeholderMode) {
            presentApplyParams(content, values)
        } else {
            presentApplyParams(content, initialValues)
        }
        val runtime = presentParamRuntime(initialValues)
        when {
            isMermaid -> buildMermaidHtml(frameSource, runtime)
            kind == "svg" -> {
                // 模型可能只给了 <path>/<circle> 片段（没包 <svg>）—— 补上外壳
                val svg = if (frameSource.contains("<svg", ignoreCase = true)) {
                    frameSource
                } else {
                    "<svg xmlns=\"http://www.w3.org/2000/svg\">$frameSource</svg>"
                }
                wrapPresentSvg(svg, runtime)
            }
            else -> wrapPresentHtml(frameSource, runtime)
        }
    }

    // 上次下发给 JS 的参数（JSON）。用它去重：值没变就不发，
    // 避免每次 recompose 都白跑一次 evaluateJavascript。
    val lastSent = remember(content) { AtomicReference<String?>(null) }

    AndroidView(
        modifier = modifier.fillMaxWidth().height(height),
        factory = { context -> WebView(context).apply {
            settings.javaScriptEnabled = true
            // 见文件头安全模型：不开 domStorage（展示块不需要持久化落脚点）
            settings.domStorageEnabled = false
            // mermaid 要从 assets 读 mermaid.min.js；其余分支不需要文件访问，
            // 关掉后即使有脚本也读不到本地文件（第 3 层防线）。
            settings.allowFileAccess = isMermaid
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            // 不允许脚本自己开新窗口（展示块没有多窗口语义）
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)

            // 第 4 层防线：只暴露一个纯数据方法
            addJavascriptInterface(
                PresentHeightBridge(this) { px -> onHeightChanged(toDp(px)) },
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
                    // 页面就绪 → 补发一次当前参数。首次加载时 AndroidView 的
                    // update 可能早于脚本运行时就绪，那次下发会静默丢失。
                    lastSent.get()?.let { json ->
                        view.evaluateJavascript(setParamsJs(json), null)
                    }
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
                // 新内容（或占位符模式下的整体重渲染）→ 真正重新加载
                web.tag = preview
                lastSent.set(null)
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
            // 参数热更新：只调 JS，**不碰 tag/preview**，所以页面不重建、动画不重启
            val json = presentParamValuesJson(values)
            if (lastSent.get() != json) {
                lastSent.set(json)
                web.evaluateJavascript(setParamsJs(json), null)
            }
        },
    )
}

/** JS 桥的注册名。前面加下划线避免和页面自己的变量撞名。 */
private const val BRIDGE_NAME = "__ccmPresent"

/** 把参数值发进页面的 JS（带运行时存在性守卫，页面未就绪时不报错）。 */
private fun setParamsJs(json: String): String =
    "(function(){var f=window.__ccmPresentSetParams;if(typeof f==='function'){try{f($json)}catch(e){}}})()"

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
private class PresentHeightBridge(
    private val webView: WebView,
    private val onHeightPx: (Int) -> Unit,
) {
    @JavascriptInterface
    fun reportHeight(px: Int) {
        // 只在合理范围内采信：0 是「还没布局完」，过大是异常值
        if (px <= 0 || px > 100_000) return
        webView.post { onHeightPx(px) }
    }
}

// ──────────────────────────────────────────────────────────────
//  HTML 包装与参数运行时（对齐 Web 端 PresentBlock.tsx 的同名函数）
// ──────────────────────────────────────────────────────────────

/**
 * 注入到页面的参数运行时 —— 让「拖滑块」不重建页面。
 *
 * 与 Web 端 `paramRuntime()` 逐条对齐：
 * - 参数写成 `:root` 的 CSS 变量 → content 里 `var(--speed)` 直接生效，CSS 动画连续不断
 * - 同时挂 `window.PARAMS`，变化时调 `window.onParamChange(name, value)`
 * - **速度类参数**（speed/rate/velocity/转速…）改用 Web Animations 的 `playbackRate`：
 *   直接改 `animation-duration` 会让 CSS 按新周期重算进度、画面瞬移；
 *   `playbackRate` 只改快慢，当前进度保留 —— 这才是「对着画面调参数」的手感。
 * - 高度自报（load / +60ms / +400ms 三次，覆盖图片加载完后的二次布局）
 *
 * ⚠️ 这份 JS 用 Kotlin 原始字符串书写，**里面不能出现 `$` 后跟标识符**
 * （会被 Kotlin 当模板插值）。需要插值的地方走 `__INITIAL__` 占位替换。
 */
internal fun presentParamRuntime(initial: Map<String, Double>): String {
    val json = presentParamValuesJson(initial)
    return """
<script>
(function(){
  var P = $json;
  window.PARAMS = P;
  function applyVars(obj){
    var root = document.documentElement;
    for (var k in obj) { try { root.style.setProperty('--' + k, String(obj[k])); } catch(e){} }
  }
  applyVars(P);

  var SPEEDISH = /^(speed|rate|velocity|spin|rotation|rotate|fps|转速|速度|速率)$/i;
  var baseRate = null;
  function speedKey(){ for (var k in P) if (SPEEDISH.test(k)) return k; return null; }
  function tuneRate(v){
    if (!document.getAnimations) return false;
    var anims = document.getAnimations();
    if (!anims.length) return false;
    if (baseRate === null) baseRate = Number(P.__base) || v || 1;
    var ratio = baseRate ? (v / baseRate) : 1;
    if (!isFinite(ratio) || ratio <= 0) return false;
    var ok = false;
    for (var i = 0; i < anims.length; i++) {
      try { anims[i].playbackRate = ratio; ok = true; } catch(e){}
    }
    return ok;
  }
  var sk = speedKey();
  if (sk) baseRate = Number(P[sk]) || 1;

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

  window.__ccmPresentSetParams = function(vals){
    for (var k in vals) {
      var v = Number(vals[k]);
      if (!isFinite(v) || P[k] === v) continue;
      P[k] = v;
      var handled = false;
      if (SPEEDISH.test(k)) handled = tuneRate(v);
      if (!handled) { try { document.documentElement.style.setProperty('--' + k, String(v)); } catch(err){} }
      if (typeof window.onParamChange === 'function') { try { window.onParamChange(k, v); } catch(err){} }
    }
    if (typeof window.onParamsChange === 'function') { try { window.onParamsChange(P); } catch(err){} }
    reportHeight();
  };
  window.__ccmPresentReportHeight = reportHeight;

  window.addEventListener('load', reportHeight);
  setTimeout(reportHeight, 60);
  setTimeout(reportHeight, 400);
})();
</script>
"""
}

/** 把参数值编成 JSON 对象（`{"speed":1.5}`）。 */
internal fun presentParamValuesJson(values: Map<String, Double>): String =
    buildJsonObject {
        values.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
    }.toString()

/**
 * 把 `{{name}}` 占位符替换成当前值。
 *
 * 用了占位符就只能**整体重渲染**（占位符是源码文本，没法热替换），
 * 动画会从头播 —— 所以工具说明里推荐用 CSS 变量 / JS 回调。
 */
internal fun presentApplyParams(source: String, values: Map<String, Double>): String {
    if (source.isEmpty()) return ""
    var out = source
    for ((name, value) in values) {
        // 转义参数名里的正则元字符，避免 name 含特殊符号时炸掉
        val re = Regex("\\{\\{\\s*${Regex.escape(name)}\\s*\\}\\}")
        out = re.replace(out) { presentFormatNumber(value) }
    }
    return out
}

/** content 里是否用了 `{{占位符}}`（用了就只能整体重渲染）。 */
internal fun presentUsesPlaceholder(source: String, params: List<PresentParam>): Boolean =
    params.any { p ->
        Regex("\\{\\{\\s*${Regex.escape(p.name)}\\s*\\}\\}").containsMatchIn(source)
    }

/**
 * 数值 → 字符串，对齐 JS 的 `String(value)`：`1.0` 出 `"1"`，`0.5` 出 `"0.5"`。
 *
 * 不能直接用 `Double.toString()` —— 那会把 `1.0` 写成 `"1.0"`，
 * 而 Web 端替换出来是 `"1"`。源码视图里两边不一致会让人以为出了 bug。
 */
internal fun presentFormatNumber(v: Double): String =
    if (v.isFinite() && v == Math.floor(v) && Math.abs(v) < 1e15) {
        v.toLong().toString()
    } else {
        v.toString()
    }

/**
 * 包一层最小 HTML 骨架展示 SVG。
 *
 * 对齐 Web 的 `wrapSvg`。**关键点**（Web 端 2026-09-19 踩过）：
 * 不要给 `html,body` 设 `height:100%` —— 那会让 body 高度等于 WebView 初始高度，
 * `svg{max-height:100%}` 被这个死高度卡住，比初始高度高的图直接被压扁，
 * 而且上报高度的脚本量到的永远是被压过的值，容器也就永远不涨。
 * 正确做法：body 高度跟随内容，svg 宽度撑满、高度按自身比例。
 */
internal fun wrapPresentSvg(svg: String, runtime: String): String =
    """<!DOCTYPE html><html><head><meta charset="utf-8"><style>
html,body{margin:0;padding:0;background:transparent}
body{display:flex;align-items:center;justify-content:center;padding:8px;box-sizing:border-box}
svg{max-width:100%;height:auto;display:block}
</style>$runtime</head><body>$svg</body></html>"""

/**
 * html 类型：把参数运行时注入到用户 HTML 里。
 *
 * 有 `<head>` 就插进去；只有 `<html>` 就补一个 head；都没有就整体包一层。
 * 对齐 Web 的 `wrapHtml`。
 */
internal fun wrapPresentHtml(html: String, runtime: String): String {
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
