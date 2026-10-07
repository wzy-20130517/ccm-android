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
 * - 纯 CLI 专属（/rewind /doctor /x11 /font /statusline /palette
 *   /editor /exit /quit /bg-*）不进本表
 *   （/device 2026-10-06 移出：APK 现在支持模式切换，见下方）
 */
val COMMON_SLASH_COMMANDS: List<Pair<String, String>> = listOf(
    // ── 设备（2026-10-06 加，对齐 CLI /device mode）──
    "/device" to "手机操作模式与设备状态（/device mode 主屏|后台|选择|off）",
    // ── 生图（handler 早有分支，命令表漏了 —— 自检脚本抓到）──
    "/imagegen" to "生图配置（/imagegen setup|url|key|model|size|dir）",
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
    "/voice" to "正文朗读（/voice on|off）",
    "/plan" to "计划模式（/plan on|off）",
    "/deep" to "deep 模式（/deep on|off，提高轮次上限）",
    "/watch" to "持续模式（/watch on|off）",
    "/coordinate" to "协调者模式（/coordinate [on|off|<任务>]）",
    "/cowork" to "协调者模式（/coordinate 的别名）",
    "/workflow" to "AgentWorkflow 阶段说明",
    "/web" to "Web 服务说明",
    "/backup" to "备份说明",
    // ── 工具 ──
    "/compact" to "压缩历史（status|micro|force [N]|<N>）",
    "/copy" to "复制最后一条回复",
    "/permissions" to "权限规则（/permissions mode 值）",
    "/export" to "导出对话（系统分享）",
    "/skills" to "技能清单（/skills 名字 看详情）",
    "/agents" to "列出子 agent 类型",
    "/goal" to "目标模式（/goal <描述> 设定并推进）",
    "/mem" to "结构化记忆（/mem list|find|save|rm）",
    "/memory" to "项目记忆 CLAUDE.md（/memory append 文本）",
    "/automem" to "自动记忆开关（/automem on|off）",
    "/btw" to "侧问一句（不进主上下文）",
    "/add-dir" to "额外可访问目录",
    "/plugin" to "插件管理（DSH 宿主：status|install|remove|enable|disable）",
    "/plugins" to "插件管理（/plugin 的别名）",
    "/github" to "GitHub 配置（/github login <token>）",
    "/mail" to "邮箱说明",
    "/mcp" to "MCP 服务器（HTTP/SSE + stdio）",
    "/pexels" to "图库 key（/pexels set <key>）",
    "/tvly" to "Tavily 搜索 key（WebSearch 用，/tvly <tvly-...>）",
    // ── 2026-10-06 补齐（问题40）──
    "/config" to "Provider 列表/切换（/config <编号>）",
    "/url" to "改 API 地址（/url <地址>）",
    "/key" to "看/改密钥（/key pool k1 k2 · /key clear）",
    "/name" to "改显示名（/name <新名>）",
    "/protocol" to "API 协议（/protocol openai|anthropic|responses）",
    "/workspace" to "工作区（/workspace [路径]）",
    "/doctor" to "环境自检",
    "/compact-threshold" to "自动压缩阈值（/compact-threshold <tokens> <messages>）",
    "/replay" to "历史回放开关（/replay on|off）",
    "/cache" to "Prompt Cache 开关（/cache on|off）",
    "/context7" to "Context7 文档 MCP（setup|enable|disable|status）",
    "/review" to "工作区审查（零 API 调用）",
    "/update" to "检查更新（APK 走 Release 页）",
    "/font" to "字体（/font reset 恢复默认）",
    "/statusline" to "状态栏说明",
    "/bg-list" to "后台任务列表",
    "/bg-status" to "后台任务详情（/bg-status <id>）",
    "/team" to "团队全景",
    "/keys" to "快捷键速查",
    "/palette" to "全部命令清单",
    "/image" to "识图（/image <路径> [说明]）",
    "/compact-trash" to "压缩回收站（list|restore|clear）",
    "/clear-restore" to "压缩回收站（同 /compact-trash）",
)
