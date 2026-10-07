package com.ccm.app.core.plugin

import android.content.Context

/**
 * `/plugin` slash 命令的执行器（对齐 CLI cmd-integrations.mjs 的 /plugin）。
 *
 * handleSlashCommand 是**同步**的（跑在主线程 onSend 回调里），
 * 而插件操作要走网络（哪怕 127.0.0.1 也有超时）——
 * 所以拆两层：本类是 suspend 的真正执行器，UI 层在协程里调它，
 * 结果通过 session.injectNotice 回屏。SlashContext.runPlugin 就是这个入口。
 *
 * 子命令集（与 CLI 一致）：
 *   （无参）      状态（宿主 + 插件 + provider 数）
 *   providers     provider 清单（含 CCM 接入地址）
 *   bundles       可安装插件包
 *   install <s>   装包
 *   remove <n>    卸包
 *   enable <n>    启用
 *   disable <n>   禁用
 */
object PluginCommandRunner {

    /** 执行一条 /plugin 命令，返回给用户看的 markdown。 */
    suspend fun run(ctx: Context, sub: String, arg: String): String {
        val appCtx = ctx.applicationContext
        val host = DshHostManager(appCtx)

        // 自愈：宿主没起就先起（对齐 CLI 的 ensureHost —— 进程被杀后不用手动 start）
        if (!host.isAlive()) {
            when (host.state()) {
                DshHostManager.State.NotInstalled,
                DshHostManager.State.DepsMissing,
                -> {
                    val ok = host.deploy { }
                    if (!ok) return "**插件宿主**\n\n部署失败（Node/依赖没装上）——\n" +
                        "去「定制 → 插件」看安装日志。"
                }
                else -> Unit
            }
            val started = host.start { }
            if (!started) return "**插件宿主**\n\n启动失败 —— 查看 rootfs 内 " +
                "/root/.ccm/dsh-host/host.log"
        }

        return when (sub) {
            "", "status" -> renderStatus()
            "providers" -> renderProviders()
            "bundles" -> renderBundles()
            "install" -> {
                if (arg.isBlank()) return "用法: `/plugin install <插件包名>`"
                when (val r = PluginManager.install(arg)) {
                    is PluginManager.Result.Ok -> "✅ 已安装 `$arg`（宿主已热加载）"
                    is PluginManager.Result.Err -> "❌ 安装失败: ${r.message}"
                }
            }
            "remove" -> {
                if (arg.isBlank()) return "用法: `/plugin remove <插件包名>`"
                when (val r = PluginManager.remove(arg)) {
                    is PluginManager.Result.Ok -> "✅ 已从配置移除 `$arg`\n\n" +
                        "（npm 包保留；彻底删除需在宿主里 npm remove）"
                    is PluginManager.Result.Err -> "❌ 移除失败: ${r.message}"
                }
            }
            "enable", "disable" -> {
                if (arg.isBlank()) return "用法: `/plugin $arg <插件名>`"
                when (val r = PluginManager.setPlugin(arg, sub == "enable")) {
                    is PluginManager.Result.Ok -> "已${if (sub == "enable") "启用" else "禁用"}: `$arg`（重启宿主生效）"
                    is PluginManager.Result.Err -> "❌ ${r.message}"
                }
            }
            else -> "未知子命令: `$sub`\n\n" + USAGE
        }
    }

    private const val USAGE =
        "用法:\n" +
            "- `/plugin` 看插件宿主状态（插件 + provider）\n" +
            "- `/plugin providers` 看接入的 Provider\n" +
            "- `/plugin bundles` 看可安装插件包\n" +
            "- `/plugin install <包名>` 安装\n" +
            "- `/plugin remove <包名>` 移除\n" +
            "- `/plugin enable|disable <名>` 启停"

    private suspend fun renderStatus(): String {
        val lines = mutableListOf("**插件宿主**")
        when (val r = PluginManager.status()) {
            is PluginManager.Result.Err -> {
                lines += "\n状态获取失败: ${r.message}"
                return lines.joinToString("\n")
            }
            is PluginManager.Result.Ok -> {
                val s = r.value
                lines += "\n- 已加载插件: ${s.loaded.size}"
                val active = s.states.count { it.active }
                lines += "- 运行状态: $active 活动 / ${s.states.size - active} 挂起"
                lines += "- 服务: ${s.serviceCount} 就绪" +
                    if (s.failedServices.isNotEmpty()) "（${s.failedServices.size} 失败）" else ""
                lines += "- Provider: ${s.providerCount}"
                if (s.loaded.isNotEmpty()) {
                    lines += "\n插件:"
                    s.states.forEach { p ->
                        val mark = if (p.active) "●" else "○"
                        lines += "- $mark ${p.name}"
                    }
                }
            }
        }
        return lines.joinToString("\n")
    }

    private suspend fun renderProviders(): String {
        val lines = mutableListOf("**Provider**")
        when (val r = PluginManager.providers()) {
            is PluginManager.Result.Err -> return "**Provider**\n\n获取失败: ${r.message}"
            is PluginManager.Result.Ok -> {
                if (r.value.isEmpty()) return "**Provider**\n\n宿主没接任何 Provider（账号池/免费额度类插件才会产生）"
                r.value.forEach { p ->
                    lines += "\n### ${p.name}"
                    lines += "- id: `${p.id}`"
                    lines += "- 状态: ${if (p.ready) "就绪" else "未就绪（无账号/未配置上游）"}"
                    if (p.models.isNotEmpty()) lines += "- 模型: ${p.models.joinToString(", ")}"
                    lines += "- 接入: `${p.baseUrl}`"
                }
            }
        }
        return lines.joinToString("\n")
    }

    private suspend fun renderBundles(): String {
        val lines = mutableListOf("**可安装插件包**")
        when (val r = PluginManager.bundles()) {
            is PluginManager.Result.Err -> return "**可安装插件包**\n\n获取失败: ${r.message}"
            is PluginManager.Result.Ok -> {
                if (r.value.isEmpty()) return "**可安装插件包**\n\n没有可安装的包（或宿主清单为空）"
                r.value.forEach { b ->
                    val tag = if (b.installed) "（已装）" else ""
                    lines += "- `${b.name}`$tag" +
                        if (b.description.isNotBlank()) " — ${b.description.take(60)}" else ""
                }
            }
        }
        return lines.joinToString("\n")
    }
}
