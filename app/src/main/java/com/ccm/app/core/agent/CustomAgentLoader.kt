package com.ccm.app.core.agent

import java.io.File

/**
 * 自定义子 Agent 加载器 —— 移植自 CLI `core/agent/custom-agents.mjs`。
 *
 * ═══════════════════════════════════════════════════════════════
 * 【2026-10-06 问题40 新建】
 *
 * 用户报「工具/命令未接入、行为降级」。
 *
 * APK 原来 `/agents` 只列**内置** 4 种类型，注释写「自定义子 Agent
 * （`.claude/agents/` 下的 .md 文件）的加载在 CLI 侧」—— 这是**功能缺失**，
 * 不是"不需要"。
 *
 * 本文件补上：从文件系统读 `.md` 角色卡（frontmatter + 正文）。
 * ═══════════════════════════════════════════════════════════════
 *
 * ## 加载顺序（对齐 CLI）
 * 1. 应用私有目录 `files/agents/`（对应 CLI 的数据目录 `~/.claude-code-mobile/agents/`）
 * 2. 工作区 `.claude/agents/`（项目级角色）
 *
 * 同名时**前面的优先**（用户级覆盖项目级）。
 *
 * ## 文件格式（与 CLI 完全一致）
 * ```markdown
 * ---
 * name: cto
 * description: 技术架构负责人
 * tools: Read, Write, Edit, Bash
 * maxTurns: 60
 * ---
 *
 * 你是 CTO，负责技术架构与工程质量…
 * ```
 *
 * frontmatter 可省（则用文件名当 name，全文当 prompt）。
 */
class CustomAgentLoader(private val roots: List<File>) {

    /** 一个自定义 Agent 的配置。 */
    data class CustomAgent(
        val name: String,
        val description: String,
        /** 工具白名单（空 = 全部工具）。 */
        val toolNames: List<String>,
        /** 轮次上限。 */
        val maxTurns: Int,
        /** 追加到系统提示词的角色说明。 */
        val promptAddition: String,
        /** 来源文件路径。 */
        val source: String,
    )

    private val agents = LinkedHashMap<String, CustomAgent>()

    init {
        reload()
    }

    /** 重新扫描所有根目录。 */
    fun reload() {
        agents.clear()
        for (root in roots) {
            if (!root.exists() || !root.isDirectory) continue
            val files = try { root.listFiles() } catch (_: Throwable) { null } ?: continue
            for (f in files) {
                if (!f.isFile || !f.name.endsWith(".md", ignoreCase = true)) continue
                try {
                    val raw = f.readText()
                    val (meta, body) = parseFrontmatter(raw)
                    val name = (meta["name"] ?: f.name.removeSuffix(".md")).trim()
                    if (name.isBlank()) continue
                    agents.putIfAbsent(
                        name,
                        CustomAgent(
                            name = name,
                            description = meta["description"]?.trim() ?: "",
                            toolNames = meta["tools"]
                                ?.split(",")
                                ?.map { it.trim() }
                                ?.filter { it.isNotEmpty() }
                                ?: emptyList(),
                            maxTurns = meta["maxTurns"]?.trim()?.toIntOrNull() ?: 30,
                            promptAddition = "\n# 你的角色：自定义子 Agent（$name）\n$body\n" +
                                "**重要**：你运行在后台，永远不要调用 AskUserQuestion。\n",
                            source = f.absolutePath,
                        ),
                    )
                } catch (_: Throwable) {
                    // 单个文件读失败不影响其他
                }
            }
        }
    }

    /** 列全部（供 /agents 展示）。 */
    fun list(): List<CustomAgent> = agents.values.toList()

    /** 按名字取（供派活时用）。 */
    fun get(name: String): CustomAgent? = agents[name]

    /** 是否为空。 */
    fun isEmpty(): Boolean = agents.isEmpty()

    /**
     * 拼给系统提示词的段落（对齐 CLI 的 `formatForPrompt()`）。
     *
     * 让模型知道「有哪些自定义角色可选」。
     */
    fun formatForPrompt(): String {
        val list = list()
        if (list.isEmpty()) return ""
        return buildString {
            append("\n\n# 用户自定义 subagent（.claude/agents）\n")
            list.forEach { a ->
                append("- `${a.name}`: ${a.description}\n")
            }
            append("Agent 工具的 subagent_type 可填以上名称。\n")
        }
    }

    private fun parseFrontmatter(raw: String): Pair<Map<String, String>, String> {
        if (!raw.startsWith("---")) return emptyMap<String, String>() to raw
        val end = raw.indexOf("\n---", 3)
        if (end < 0) return emptyMap<String, String>() to raw
        val head = raw.substring(3, end).trim()
        val body = raw.substring(end + 4).removePrefix("\n")
        val meta = mutableMapOf<String, String>()
        for (line in head.split("\n")) {
            val m = Regex("^([A-Za-z0-9_-]+):\\s*(.*)$").find(line) ?: continue
            meta[m.groupValues[1]] = m.groupValues[2]
                .trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
                .trim()
        }
        return meta to body
    }

    companion object {
        /**
         * 构造（问题40）。
         *
         * @param appRoot 应用私有根（files/）
         * @param cwd 工作区（读 `.claude/agents/`）
         */
        fun create(appRoot: File?, cwd: String): CustomAgentLoader {
            val roots = mutableListOf<File>()
            appRoot?.let { roots += File(it, "agents") }
            if (cwd.isNotBlank()) roots += File(cwd, ".claude/agents")
            return CustomAgentLoader(roots)
        }
    }
}
