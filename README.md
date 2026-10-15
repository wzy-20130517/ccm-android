<div align="center">

# CCM · Claude Code Mobile for Android

**把 AI 编程助手装进手机 —— 原生 Android 应用，Kotlin + Jetpack Compose 重写**

[![Release](https://img.shields.io/github/v/release/wzy-20130517/ccm-android?style=flat-square&label=release)](https://github.com/wzy-20130517/ccm-android/releases)
[![Build](https://img.shields.io/github/actions/workflow/status/wzy-20130517/ccm-android/build.yml?style=flat-square&label=build)](https://github.com/wzy-20130517/ccm-android/actions)
[![License](https://img.shields.io/badge/license-MIT-blue?style=flat-square)](./LICENSE)

</div>

---

## 这是什么

CCM Android 是 [claude-code-mobile](https://github.com/wzy-20130517/Claude-Code-Mobile) 的**原生 Android 版本**：把终端里的 AI 编程 Agent 装进一个 App，全程不用敲命令。

与 CLI 的关系是**独立实现、能力对齐** —— Agent 内核全部用 Kotlin 重写（不跑 Node.js），会话 JSON 格式与 CLI 兼容，可以互相搬移；但代码零共享。

> ⚠️ 本项目是开源复刻实现，**与 Anthropic 无隶属关系**，也不是官方 Claude Code 的移动端。

---

## 特性

### Agent 能力
- **100+ 工具**：文件读写、搜索、Shell、Git、网络、手机操作、任务管理、子 Agent、团队协作、Goal 自动推进
- **多协议支持**：OpenAI / Anthropic / Responses 三种 API 协议，可接任意兼容网关
- **流式输出**：实时渲染正文与思考过程，工具调用可视化
- **上下文管理**：自动压缩、会话恢复、跨会话记忆

### 手机专属能力
- **手机操作（phone use）**：Agent 能看屏幕、点按、输入、滑动、启动应用
  - 元素树驱动（不是截图识图），比视觉方案快一个数量级
  - 支持**前台模式**（操作主屏，用户可见）与**后台模式**（虚拟副屏静默执行）
- **虚拟副屏**：Shizuku + VirtualDisplay + UiAutomation，不占用用户屏幕
- **Clawd 悬浮窗**：退后台时的桌面吉祥物，随 Agent 状态做动画、显示实时台词
- **多模态**：截图、发图、视频抽帧，原生多模态注入
- **语音**：Edge TTS 播报、正文朗读、语音输入
- **自动化集成**：QQ 桥（私聊下指令）、定时任务、GitHub 工具

### 应用特性
- **自更新**：应用内检查新版本 → 自动下载 → 拉起安装器，无需手动找 Release
- **市场**：从远端安装 skill / MCP / DSH 插件
- **Bash 环境**：内置 proot（免装 Termux）或外接 Termux，二选一
- **深色/浅色主题**、聊天字体自定义

---

## 安装

### 方式一：应用内更新（推荐）

首次安装后，之后的版本都在 App 内完成：

```
设置 → 关于 → 检查更新
```

或在对话里发送 `/update`。

应用会从 GitHub Releases 拉取最新版，下载后自动拉起系统安装器。**覆盖安装不丢数据**（会话、配置都在应用私有目录，不在 APK 里）。

### 方式二：手动下载

去 [Releases](https://github.com/wzy-20130517/ccm-android/releases) 下载最新的 `CCM-<build号>.apk`，直接安装。

> 每次 CI 构建都会自动发一个 Release，tag 形如 `build-252`，数字就是构建号 —— 与 App 内显示的 `versionCode` 一致。

### 首次启动

1. 配置 API Provider（地址 + Key + 模型）
2. 按需授予权限（Shizuku 用于手机操作，存储用于工作区）
3. 可选：安装 proot 环境（Bash 工具用）

---

## 与 CLI 版的差异

| | CLI（claude-code-mobile） | 本仓库（APK） |
|---|---|---|
| 内核 | Node.js（`.mjs`） | **Kotlin 原生** |
| 界面 | 终端全屏 TUI | **Compose 原生 UI** |
| 手机操作 | rish + dumpsys 走 shell | **Shizuku 服务 + UiAutomation**（更快更稳） |
| Bash 环境 | Termux 原生（开箱即用） | 内置 proot（需装 rootfs）或外接 Termux |
| 悬浮窗 | 无 | **Clawd 吉祥物** |
| 更新方式 | 下载源码包就地覆盖 | **下载 APK 覆盖安装** |
| 会话数据 | `~/.claude-code-mobile/` | App 私有目录（`files/`） |
| 部署 | `git clone` 即用 | 下载 APK 安装 |

两边**会话 JSON 格式兼容**，可以把 CLI 的会话搬进 App（反之亦然）。

---

## 架构

```
┌─────────────────────────────────────────────────────────┐
│  UI 层（Jetpack Compose，全原生）                        │
│  ├─ CcmApp           对话页 / 侧栏 / 设置                │
│  ├─ ClawdOverlay     退后台时的悬浮吉祥物（WebView 渲染   │
│  │                   SVG，因为素材自带 CSS 动画）        │
│  └─ 页面：对话/会话列表/市场/协作/自定义/设置            │
├─────────────────────────────────────────────────────────┤
│  Agent 内核（Kotlin，对应 Node 版 core/）                │
│  ├─ core/agent/      AgentLoop（工具循环/流式/子 Agent）  │
│  ├─ core/api/        三协议客户端（OpenAI/Anthropic/     │
│  │                   Responses）                         │
│  ├─ core/session/    会话存储（格式与 Node 版兼容）       │
│  ├─ core/tools/      工具实现                            │
│  ├─ core/update/     自更新（版本检查/下载/安装）         │
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

**关于 proot**：它不是 Agent 的运行环境 —— Agent 内核本身就是 Kotlin 原生跑的。proot 只服务一件事：Bash 工具执行命令（装了个 Ubuntu rootfs 提供完整工具链）。不想用可以切外接 Termux，或干脆不装（Bash 工具不可用，其余功能照常）。

---

## 权限说明

| 权限 | 用途 |
|---|---|
| `INTERNET` / `ACCESS_NETWORK_STATE` | 调模型 API + 市场下载 + 检查更新 |
| `SYSTEM_ALERT_WINDOW` | Clawd 悬浮窗（退后台时显示） |
| `FOREGROUND_SERVICE` + `_DATA_SYNC` + `_SPECIAL_USE` | 常驻服务（Agent 运行时保活 + 悬浮窗） |
| `POST_NOTIFICATIONS` | 前台服务通知 |
| `REQUEST_INSTALL_PACKAGES` | **自更新安装新版本 APK** |
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
│   ├── core/          Agent 内核
│   │   ├── agent/     AgentLoop / 子 Agent / 任务 / 团队 / Goal / 自动记忆
│   │   ├── api/       三协议 API 客户端
│   │   ├── update/    自更新（Updater）
│   │   ├── tools/     工具实现
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

## 构建

需要 JDK 17 + Android SDK（compileSdk 35）。

```bash
# 本地构建（需要 Android SDK）
./gradlew assembleDebug

# 指定版本号（CI 用这种方式注入 build 号）
CCM_VERSION_CODE=253 CCM_VERSION_NAME=0.1.0 ./gradlew assembleRelease
```

> `versionCode` 由 `CCM_VERSION_CODE` 环境变量决定，每次 CI 构建递增。
> 覆盖安装时 Android 要求新包的 `versionCode` >= 旧的。

CI（GitHub Actions）在每次推送到 `main` 时自动构建并发布 Release。

---

## 许可

与上游 [claude-code-mobile](https://github.com/wzy-20130517/Claude-Code-Mobile) 一致：**MIT**。

本项目是独立实现，与 Anthropic 无隶属关系，也不是官方 Claude Code 的移动端。
