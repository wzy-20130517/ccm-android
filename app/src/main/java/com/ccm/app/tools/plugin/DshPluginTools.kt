package com.ccm.app.tools.plugin

import android.content.Context
import com.ccm.app.core.plugin.DshHostManager
import com.ccm.app.core.plugin.PluginManager
import com.ccm.app.core.tool.Tool
import com.ccm.app.core.tool.ToolContext
import com.ccm.app.core.tool.ToolResult
import com.ccm.app.core.tool.ToolSchema
import com.ccm.app.core.tool.ToolSchema.bool
import com.ccm.app.core.tool.ToolSchema.int
import com.ccm.app.core.tool.ToolSchema.str
import kotlinx.serialization.json.JsonObject

/**
 * DSH 插件管理工具（对齐 CLI `core/tools/tools-dsh-plugin.mjs` 的 DshPlugin）。
 *
 * action 集与 CLI 一致（对齐官方 plugin_manager + 两个 CCM 专用查询）：
 *   list_plugins / list_bundles / set_plugin / install_bundle /
 *   remove_bundle / providers / status
 *
 * 与 CLI 的差异：
 *   · 自愈用 DshHostManager（proot 内部署+启动），不是 start.sh ——
 *     APK 的宿主在 rootfs 里，首次自动部署含 npm ci（约 334MB，较慢）
 *   · status 不自动拉起（它就是查状态的，拉起会让结果失真）——同 CLI
 */
class DshPluginTools(private val context: Context) {

    inner class DshPluginTool : Tool() {
        override val name = "DshPlugin"
        override val description = buildString {
            append("管理 DSH 插件宿主（dsh-host）里的插件。 ")
            append("action=list_plugins: 列出已加载插件与 provider 状态（支持 offset/limit 分页）。 ")
            append("action=list_bundles: 列出可安装的 DSH 插件包。 ")
            append("action=set_plugin: 启用/禁用插件（target=插件名，enabled=true/false）。 ")
            append("action=install_bundle: 安装插件（target=模块名，宿主内 npm install + 热加载）。 ")
            append("action=remove_bundle: 卸载插件（target=模块名，npm 包保留）。 ")
            append("action=providers: 列出 provider 及其 CCM 接入地址（baseUrl/apiKey）。 ")
            append("action=status: 宿主健康检查。 ")
            append("宿主未运行时自动拉起（首次使用要装运行时与依赖，约 334MB、可能十几分钟，autoStart:false 可跳过）。")
        }
        // 有写操作（install/remove/set），整体按非只读对待
        override val isReadOnly = false
        override val isDestructive = false
        override val isConcurrencySafe = false
        // 安装可能跑 npm install（宿主内最长 180s）+ 自愈部署（首次很慢）
        override val maxResultSizeChars = 20_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "action" to ToolSchema.string(
                "操作类型（对齐官方 plugin_manager，外加 providers/status 两个 CCM 专用查询）",
                enum = listOf(
                    "list_plugins", "list_bundles", "set_plugin",
                    "install_bundle", "remove_bundle", "providers", "status",
                ),
            ),
            "target" to ToolSchema.string("set_plugin/install_bundle/remove_bundle 的目标（插件模块名）"),
            "enabled" to ToolSchema.boolean("set_plugin 时必填：true=启用 false=禁用"),
            "offset" to ToolSchema.integer("list 分页起始（0-based，默认 0）", minimum = 0),
            "limit" to ToolSchema.integer("list 分页大小（1-100，默认 25）", minimum = 1, maximum = 100),
            "autoStart" to ToolSchema.boolean("宿主未运行时自动拉起（默认 true；首次安装依赖较慢）"),
            required = listOf("action"),
        )

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val action = input.str("action")
                ?: return ToolResult.Error("action is required")
            val target = input.str("target")
            val enabled = input.bool("enabled")
            val offset = input.int("offset") ?: 0
            val limit = (input.int("limit") ?: 25).coerceIn(1, 100)
            val autoStart = input.bool("autoStart") ?: true

            if (offset < 0) return ToolResult.Error("offset 必须是非负整数")

            val host = DshHostManager(context)

            // 自愈：宿主没起就拉起（status 跳过 —— 同 CLI）
            if (autoStart && action != "status") {
                if (!host.isAlive()) {
                    when (host.state()) {
                        DshHostManager.State.NotInstalled,
                        DshHostManager.State.DepsMissing,
                        -> {
                            ctx.ui.onProgress("首次安装插件宿主（约 334MB，较慢）…")
                            // ⚠️ deploy 的 onLog 是同步回调（exec 线程里调），
                            // 里面不能调 suspend 的 onProgress —— 收集起来，
                            // 失败时附进错误信息供排查。
                            val deployLog = mutableListOf<String>()
                            if (!host.deploy { s -> deployLog.add(s) }) {
                                return ToolResult.Error(
                                    "宿主未运行，自动部署失败（Node 或依赖没装上）—— " +
                                        "去「定制 → 插件」看安装日志，或先手动装 Node 工具链。\n" +
                                        "日志尾部: ${deployLog.takeLast(5).joinToString(" / ")}",
                                )
                            }
                        }
                        else -> Unit
                    }
                    ctx.ui.onProgress("启动插件宿主…")
                    val startLog = mutableListOf<String>()
                    if (!host.start { s -> startLog.add(s) }) {
                        return ToolResult.Error(
                            "宿主启动失败 —— 查看 rootfs 内 /root/.ccm/dsh-host/host.log\n" +
                                "日志: ${startLog.takeLast(3).joinToString(" / ")}",
                        )
                    }
                }
            }

            return when (action) {
                "status" -> {
                    when (val r = PluginManager.status()) {
                        is PluginManager.Result.Ok -> ToolResult.Success(r.value.toStringPretty())
                        is PluginManager.Result.Err -> ToolResult.Error(
                            "宿主未运行或不可达: ${r.message}",
                        )
                    }
                }

                "list_plugins" -> {
                    when (val r = PluginManager.status()) {
                        is PluginManager.Result.Err -> ToolResult.Error("宿主未运行: ${r.message}")
                        is PluginManager.Result.Ok -> {
                            val rows = r.value.states.map { p ->
                                buildString {
                                    append(p.name)
                                    append("  state=")
                                    append(p.state)
                                    if (!p.active) append("  （挂起中：依赖的服务未就绪）")
                                }
                            }
                            val page = rows.drop(offset).take(limit)
                            val out = buildString {
                                appendLine("entries:")
                                page.forEach { appendLine("  $it") }
                                appendLine("total: ${rows.size}")
                                val next = if (offset + page.size < rows.size) offset + page.size else null
                                if (next != null) appendLine("nextOffset: $next")
                                append("services: ${r.value.serviceCount}")
                                if (r.value.failedServices.isNotEmpty()) {
                                    append("（失败 ${r.value.failedServices.size}：${r.value.failedServices.joinToString(", ")}）")
                                }
                            }
                            ToolResult.Success(out)
                        }
                    }
                }

                "list_bundles" -> {
                    when (val r = PluginManager.bundles()) {
                        is PluginManager.Result.Err -> ToolResult.Error("获取失败: ${r.message}")
                        is PluginManager.Result.Ok -> {
                            val page = r.value.drop(offset).take(limit)
                            val out = buildString {
                                appendLine("entries:")
                                page.forEach { b ->
                                    appendLine("  ${b.name}${if (b.installed) "  (已装)" else ""}" +
                                        if (b.description.isNotBlank()) "  ${b.description.take(80)}" else "")
                                }
                                appendLine("total: ${r.value.size}")
                                val next = if (offset + page.size < r.value.size) offset + page.size else null
                                if (next != null) append("nextOffset: $next")
                            }
                            ToolResult.Success(out)
                        }
                    }
                }

                "set_plugin" -> {
                    if (target.isNullOrBlank()) return ToolResult.Error("set_plugin 需要 target")
                    if (enabled == null) return ToolResult.Error("set_plugin 需要 enabled（true/false）")
                    when (val r = PluginManager.setPlugin(target, enabled)) {
                        is PluginManager.Result.Ok -> ToolResult.Success(
                            "已${if (enabled) "启用" else "禁用"}: $target（重启宿主生效）",
                        )
                        is PluginManager.Result.Err -> ToolResult.Error("操作失败: ${r.message}")
                    }
                }

                "install_bundle" -> {
                    if (target.isNullOrBlank()) return ToolResult.Error("install_bundle 需要 target（插件模块名）")
                    ctx.ui.onProgress("安装 $target（宿主内 npm install，可能几分钟）…")
                    when (val r = PluginManager.install(target)) {
                        is PluginManager.Result.Ok -> ToolResult.Success("已安装并加载: ${r.value}")
                        is PluginManager.Result.Err -> ToolResult.Error("安装失败: ${r.message}")
                    }
                }

                "remove_bundle" -> {
                    if (target.isNullOrBlank()) return ToolResult.Error("remove_bundle 需要 target")
                    when (val r = PluginManager.remove(target)) {
                        is PluginManager.Result.Ok -> ToolResult.Success(
                            "已卸载: $target（npm 包保留，如需彻底移除由宿主执行 npm remove）",
                        )
                        is PluginManager.Result.Err -> ToolResult.Error("卸载失败: ${r.message}")
                    }
                }

                "providers" -> {
                    when (val r = PluginManager.providers()) {
                        is PluginManager.Result.Err -> ToolResult.Error("宿主未运行: ${r.message}")
                        is PluginManager.Result.Ok -> {
                            if (r.value.isEmpty()) return ToolResult.Success("没有已注册的 provider")
                            val out = buildString {
                                r.value.forEach { p ->
                                    appendLine("${p.id}  (${p.name})")
                                    appendLine("  baseUrl : ${p.baseUrl}")
                                    appendLine("  apiKey  : ${p.apiKey}")
                                    appendLine("  backend : ${if (p.ready) "ready" else "未就绪（无账号/未配置上游）"}")
                                    if (p.models.isNotEmpty()) appendLine("  models  : ${p.models.joinToString(", ")}")
                                    appendLine()
                                }
                            }.trimEnd()
                            ToolResult.Success(out)
                        }
                    }
                }

                else -> ToolResult.Error("未知 action: $action")
            }
        }

        /** status 输出走 JSON（与 CLI 的 JSON.stringify 一致，供模型解析） */
        private fun PluginManager.HostStatus.toStringPretty(): String = buildString {
            appendLine("running: true")
            appendLine("plugins: ${loaded.joinToString(", ")}")
            appendLine("pluginStates:")
            states.forEach { appendLine("  ${it.name}: ${if (it.active) "active" else "suspended(state=${it.state})"}") }
            appendLine("services: ${serviceCount}")
            if (failedServices.isNotEmpty()) appendLine("services.failed: ${failedServices.joinToString(", ")}")
            append("providers: ${providerCount}")
        }
    }
}
