package com.ccm.app.ui.chat

/**
 * Slash 命令公共清单（2026-09-30 多 Agent 扩充）——首页 [com.ccm.app.ui.pages.LandingScreen]
 * 与对话页 [ChatScreen] 的候选面板共用，避免两份表漂移。
 *
 * ## 全表来源（三处，必须同步）
 * 1. SlashCommandHandler.kt 的四个分区（会话/查询/配置/工具）
 * 2. ChatScreenConnected.kt 老 when 的基础命令
 * 3. 本表（候选面板展示）
 *
 * ## 分组（面板按此顺序展示）
 */
val COMMON_SLASH_COMMANDS: List<Pair<String, String>> = listOf(
    // ── 基础 ──
    "/clear" to "清空当前对话",
    "/new" to "新建会话",
    "/help" to "显示可用命令",
    "/stop" to "停止当前任务",
    "/retry" to "重跑最后一条消息",
    // ── 会话 ──
    "/save" to "手动存档当前会话",
    "/rename" to "重命名当前会话（/rename 标题）",
    "/delete" to "删除当前会话",
    "/load" to "打开会话切换器",
    "/resume" to "打开会话切换器",
    "/summary" to "生成对话摘要",
    "/branch" to "从当前会话分支",
    "/incognito" to "无痕会话",
    // ── 查询 ──
    "/context" to "上下文用量",
    "/cost" to "token 用量",
    "/stats" to "会话统计",
    "/status" to "当前配置状态",
    "/tasks" to "当前任务列表",
    "/todos" to "当前待办清单",
    "/files" to "工作区文件列表",
    "/trace" to "最近运行 trace",
    "/errors" to "最近错误",
    // ── 配置 ──
    "/model" to "选择模型",
    "/style" to "选择输出风格",
    "/effort" to "思考强度（/effort high）",
    "/temperature" to "采样温度（/temperature 0.7）",
    "/greeting" to "开场白开关（/greeting on|off）",
    "/me" to "用户资料（/me set 字段 值）",
    "/markdown" to "markdown 样式说明",
    "/voice" to "语音朗读说明",
    // ── 工具 ──
    "/compact" to "压缩历史（截断旧工具输出）",
    "/copy" to "复制最后一条回复",
    "/permissions" to "查看权限规则",
    "/export" to "导出对话（系统分享）",
    "/skills" to "列出可用技能",
    "/agents" to "列出子 agent 类型",
    "/goal" to "目标模式说明",
    "/mem" to "查看项目记忆 CLAUDE.md",
    "/memory" to "查看项目记忆 CLAUDE.md",
    "/automem" to "自动记忆开关说明",
    "/github" to "GitHub 配置说明",
    "/mail" to "邮箱配置说明",
    "/mcp" to "MCP 服务器说明",
    "/pexels" to "图库 key 说明",
)
