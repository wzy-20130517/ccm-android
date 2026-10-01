package com.ccm.app.ui.chat

/**
 * Slash 命令公共清单（2026-10-01 对齐 CLI 大扩充）——首页
 * [com.ccm.app.ui.pages.LandingScreen] 与对话页 [ChatScreen] 的候选面板共用，
 * 避免两份表漂移。
 *
 * ## 全表来源（三处，必须同步）
 * 1. SlashCommandHandler.kt 的四个分区（会话/查询/配置/工具）—— **真值源**
 * 2. ChatScreenConnected.kt / CcmApp.kt 老 when 的基础命令
 * 3. 本表（候选面板展示）
 *
 * ## 分组（面板按此顺序展示）
 * 基础 → 会话 → 查询 → 配置 → 工具/集成
 *
 * ## 维护纪律
 * - 加命令：先在 SlashCommandHandler 里写分支，再往本表加一行
 * - 本表列了但 handler 没有分支 = 用户敲了没反应（面板骗人），
 *   所以两者必须一致。自检脚本见 `tools/check_slash_consistency.py`
 * - 纯 CLI 专属（/rewind /doctor /x11 /device /font /statusline /palette
 *   /editor /exit /quit /bg-*）不进本表
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
    "/branch" to "从当前会话分叉（/branch [名字]）",
    "/incognito" to "无痕开关（不落盘，/incognito on|off）",
    "/undo" to "撤销最近一次文件修改",
    "/rewind" to "查看可恢复的检查点",
    "/trash" to "回收站（/trash restore N · /trash clear）",
    // ── 查询 ──
    "/context" to "上下文用量（/context 200k 设上限）",
    "/cost" to "token 用量",
    "/stats" to "会话统计",
    "/status" to "当前配置状态",
    "/tasks" to "当前任务列表",
    "/todos" to "当前待办清单",
    "/files" to "工作区文件列表",
    "/trace" to "最近运行 trace",
    "/errors" to "最近错误",
    "/diff" to "工作区 git 改动",
    "/tools" to "已注册工具清单（/tools 关键词）",
    "/hooks" to "已注册的 hook",
    "/away" to "离场报告",
    "/check" to "环境自检",
    // ── 配置 ──
    "/model" to "选择模型",
    "/style" to "选择输出风格",
    "/effort" to "思考强度（/effort high）",
    "/temperature" to "采样温度（/temperature 0.7）",
    "/greeting" to "开场白开关（/greeting on|off）",
    "/me" to "用户资料（/me set 字段 值）",
    "/markdown" to "markdown 样式说明",
    "/voice" to "语音朗读说明",
    "/plan" to "计划模式（/plan on|off）",
    "/deep" to "deep 模式（/deep on|off，提高轮次上限）",
    "/watch" to "持续模式（/watch on|off）",
    "/imagegen" to "生图配置（/imagegen url|key|model 值）",
    "/web" to "Web 服务说明",
    "/backup" to "备份说明",
    // ── 工具 ──
    "/compact" to "压缩历史（截断旧工具输出）",
    "/copy" to "复制最后一条回复",
    "/permissions" to "权限规则（/permissions mode 值）",
    "/export" to "导出对话（系统分享）",
    "/skills" to "技能清单（/skills 名字 看详情）",
    "/agents" to "列出子 agent 类型",
    "/goal" to "目标模式说明",
    "/mem" to "项目记忆（/mem append 文本 · /mem show）",
    "/memory" to "项目记忆（同 /mem）",
    "/automem" to "自动记忆开关说明",
    "/btw" to "侧问一句（不进主上下文）",
    "/add-dir" to "额外可访问目录说明",
    "/plugins" to "插件说明",
    "/keepalive" to "保活说明",
    "/github" to "GitHub 配置说明",
    "/mail" to "邮箱配置说明",
    "/mcp" to "MCP 服务器说明",
    "/pexels" to "图库 key 说明",
)
