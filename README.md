# CCM · Claude Code Mobile 的 Android 原生版

把 [claude-code-mobile](https://github.com/wzy-20130517/Claude-Code-Mobile) 的能力
装进一个**原生 Android App**：Agent 内核全部用 Kotlin 重写（不跑 Node），
手机操作走 Shizuku + 虚拟副屏，Bash 环境可选内置 proot 或外接 Termux。

> 与 CLI 的关系：**独立实现、能力对齐**。会话 JSON 格式兼容（可跨端搬移），
> 但代码零共享 —— CLI 是 Node，这里是 Kotlin。

---

## 架构

```
┌─────────────────────────────────────────────────────────┐
│  UI 层（Jetpack Compose，全原生）                        │
│  ├─ CcmApp           对话页 / 侧栏 / 设置（对齐 Web 布局）│
│  ├─ ClawdOverlay     退后台时的悬浮吉祥物（WebView 渲染   │
│  │                   SVG，因为素材自带 CSS 动画）        │
│  └─ 页面：对话/会话列表/市场/协作/自定义/设置            │
├─────────────────────────────────────────────────────────┤
│  Agent 内核（Kotlin，对应 Node 版 core/）                │
│  ├─ core/agent/      AgentLoop（工具循环/流式/子 Agent）  │
│  ├─ core/api/        三协议客户端（OpenAI/Anthropic/     │
│  │                   Responses）                         │
│  ├─ core/session/    会话存储（格式与 Node 版兼容）       │
│  ├─ core/tools/      工具实现（53 个文件）               │
│  └─ core/*           记忆/压缩/MCP/插件/市场/追踪…       │
├─────────────────────────────────────────────────────────┤
│  桥接层                                                  │
│  ├─ bridge/ShizukuBridge    Shizuku 连接管理            │
│  ├─ bridge/PhoneUseService  shell uid 下的副屏服务       │
│  │   （VirtualDisplay + UiAutomation + 帧缓存）          │
│  └─ bridge/NativeBridge     原生能力路由                 │
├─────────────────────────────────────────────────────────┤
│  运行环境（Bash 工具的后端，可选）                        │
│  ├─ runtime/ProotRuntime    内置 proot（免装 Termux）    │
│  └─ 外接 Termux             RUN_COMMAND Intent           │
└─────────────────────────────────────────────────────────┘
```

**关于 proot**：它不是 Agent 的运行环境 —— Agent 内核本身就是 Kotlin 原生跑的。
proot 只服务一件事：Bash 工具执行命令（装了个 Ubuntu rootfs 提供完整工具链）。
不想用可以切外接 Termux，或干脆不装（Bash 工具不可用，其余功能照常）。

---

## 功能

- **完整 Agent 能力**：与 CLI 对齐的 100+ 工具（文件/搜索/网络/手机/任务/团队/Goal…）
- **手机操作（phone use）**：看屏幕（元素树快照）、点按、输入、滑动、启动应用；
  支持主屏（前台可见）/ 副屏（虚拟屏静默）两种模式
- **虚拟副屏**：Shizuku 起 shell 服务 → VirtualDisplay → UiAutomation，
  元素树以平铺文本返回，模型直接读文本操作（不用截图识图）
- **多模态**：截图 / 发图 / 看视频抽帧，原生多模态注入
- **语音**：Edge TTS 播报、正文朗读、语音输入
- **Clawd 悬浮窗**：退后台时的桌面宠物，随 Agent 状态做动画
- **市场**：从远端装 skill / MCP / DSH 插件
- **QQ 桥**：通过 QQ 私聊给 Agent 下指令

---

## 与 CLI 版的差异

| | CLI（claude-code-mobile） | 本仓库（APK） |
|---|---|---|
| 内核 | Node（.mjs） | **Kotlin 原生** |
| 界面 | 终端全屏 TUI | **Compose 原生 UI** |
| 手机操作 | rish + dumpsys 走 shell | **Shizuku 服务 + UiAutomation**（更快更稳） |
| Bash 环境 | Termux 原生（开箱即用） | 内置 proot（要装 rootfs）或外接 Termux |
| 悬浮窗 | 无 | **Clawd 吉祥物** |
| 会话数据 | `~/.claude-code-mobile/` | App 私有目录（`files/`） |
| 部署 | git clone 即用 | 下载 APK 安装 |

两边**会话 JSON 格式兼容**，可以把 CLI 的会话搬进 App（反之亦然）。

---

## 下载安装

去 [Releases](https://github.com/wzy-20130517/ccm-android/releases) 下载最新的
`CCM-<build号>.apk`，直接安装即可（每次 CI 构建都会自动发一个）。

首次启动会让你配 API Provider（地址 + Key + 模型），并可选安装 proot 环境（Bash 工具用）。

---

## 权限说明

| 权限 | 用途 |
|---|---|
| `SYSTEM_ALERT_WINDOW` | Clawd 悬浮窗（退后台时显示） |
| `FOREGROUND_SERVICE` + `_DATA_SYNC` + `_SPECIAL_USE` | 常驻服务（Agent 运行时保活 + 悬浮窗） |
| `POST_NOTIFICATIONS` | 前台服务通知 |
| `INTERNET` / `ACCESS_NETWORK_STATE` | 调模型 API + 市场下载 |
| `MANAGE_EXTERNAL_STORAGE` | 读写工作区文件（可选，不授权也能用） |
| `CAMERA` / `RECORD_AUDIO` | 拍照/录音（语音输入、相机相关工具） |
| `ACCESS_COARSE/FINE_LOCATION` | Location 工具（可选） |
| `RECEIVE_BOOT_COMPLETED` | 开机自启服务（可选） |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 申请电池白名单（防后台被杀） |
| `WAKE_LOCK` / `VIBRATE` | 保活 / 震动反馈 |
| `QUERY_ALL_PACKAGES` | 列已装应用（phone_app list） |
| `moe.shizuku.manager.permission.API_V23` | Shizuku 授权（手机操作） |
| `com.termux.permission.RUN_COMMAND` | 外接 Termux 跑 Bash（可选） |

---

## 目录结构

```
ccm-android/
├── app/src/main/java/com/ccm/app/
│   ├── core/          Agent 内核（63 个文件）
│   │   ├── agent/     AgentLoop / 子 Agent / 任务 / 团队 / Goal / 自动记忆
│   │   ├── api/       三协议 API 客户端
│   │   ├── tools/     工具实现（53 个文件）
│   │   ├── session/   会话存储与消息模型
│   │   ├── mcp/       MCP 客户端
│   │   ├── plugin/    DSH 插件对接
│   │   ├── market/    市场（skill/MCP/插件安装）
│   │   └── …          记忆/压缩/追踪/提示词/用户资料…
│   ├── bridge/        Shizuku 桥 + 副屏服务 + 原生能力路由
│   ├── runtime/       proot 环境（rootfs/PTY/解压）
│   ├── service/       前台服务 / 开机自启 / Clawd 悬浮窗
│   ├── tools/         工具宿主（权限/输出/钩子/引导）
│   └── ui/            Compose 界面（对话/设置/市场/协作…）
└── app/src/main/assets/   系统提示词 / Clawd 素材 / mermaid
```

---

## 许可

与上游 [claude-code-mobile](https://github.com/wzy-20130517/Claude-Code-Mobile) 一致：
**MIT**。

本项目是独立实现，与 Anthropic 无隶属关系，也不是官方 Claude Code 的移动端。
