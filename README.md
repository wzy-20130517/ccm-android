# CCM · Claude Code Mobile Android 原生外壳

把 `claude-code-mobile`（Termux 上的 Node CLI + Web）封装成**原生 Android App**，
用 proot 内嵌 Ubuntu 提供执行环境，用无障碍服务替代 Shizuku 做手机操作。

---

## 架构

```
┌────────────────────────────────────────────────────────┐
│  Kotlin 原生层（App 进程）                              │
│  ├─ CcmService          前台服务 + 桥接服务器 :3457      │
│  ├─ CcmAccessibilityService   手机操作（点击/输入/读树） │
│  ├─ RootfsManager       rootfs 下载/解压/校验            │
│  ├─ ProotRuntime        proot 命令构造 + 环境处理        │
│  ├─ PtySession          PTY 会话（AI 的 Bash 后端）      │
│  ├─ NativeBridge        原生能力分发（路由）             │
│  ├─ ScreenCapture       MediaProjection 截图            │
│  ├─ NativeTts           系统 TTS                        │
│  └─ MainActivity        Compose UI + WebView            │
├────────────────────────────────────────────────────────┤
│  proot Ubuntu 24.04（内嵌执行环境）                     │
│  └─ Node.js 18.19.1                                     │
│     ├─ ccm-start.mjs     启动器                         │
│     ├─ ccm-bridge.mjs    → 调 Kotlin 桥                 │
│     ├─ ccm-adapters.mjs  工具适配（原生替换）            │
│     ├─ core/             内核（130 模块）                │
│     └─ web/              Web 服务 :3456 + React 前端     │
└────────────────────────────────────────────────────────┘
```

**通信**：
- UI：WebView → `http://127.0.0.1:3456`（React）
- 原生能力：Node → `http://127.0.0.1:3457/native/call`

---

## 目录结构

```
ccm-android/
├── app/src/main/
│   ├── java/com/ccm/app/
│   │   ├── MainActivity.kt              Compose UI + 安装流程
│   │   ├── runtime/
│   │   │   ├── RootfsManager.kt         rootfs 生命周期
│   │   │   ├── TarExtractor.kt          纯 Kotlin tar.gz 解压
│   │   │   ├── ProotRuntime.kt          ⭐ proot 参数（含 10 个实测坑）
│   │   │   └── PtySession.kt            PTY 会话
│   │   ├── service/
│   │   │   ├── CcmService.kt            前台服务 + HTTP 桥
│   │   │   ├── CcmAccessibilityService.kt  手机操作
│   │   │   └── BootReceiver.kt          开机自启
│   │   ├── bridge/NativeBridge.kt       原生能力路由
│   │   └── tools/
│   │       ├── ScreenCapture.kt         MediaProjection 截图
│   │       └── NativeTts.kt             系统 TTS
│   ├── jniLibs/arm64-v8a/               proot 二进制（patched）
│   └── res/xml/accessibility_service_config.xml
├── node/                                Node 侧桥接（打进内核包）
│   ├── ccm-bridge.mjs                   桥客户端
│   ├── ccm-env.mjs                      环境探测 + 降级
│   ├── ccm-adapters.mjs                 工具适配层
│   └── tools-native.mjs                 原生版工具定义
└── .github/workflows/build.yml          云端编译
```

---

## 编译

**本地（Termux）不推荐** —— CPU 打满，2~7 分钟，编 Compose 会卡死。

**用 GitHub Actions（推荐）**：

```bash
git push   # 自动触发
# 4 分钟后在 Release 里拿 APK
```

**workflow 关键点**（`android-actions/setup-android@v3` 已坏，必须手装）：

```yaml
- name: Setup Android SDK
  run: |
    mkdir -p $HOME/android-sdk/cmdline-tools
    cd $HOME/android-sdk
    curl -sL -o cmdline-tools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
    unzip -q cmdline-tools.zip -d cmdline-tools
    mv cmdline-tools/cmdline-tools cmdline-tools/latest
    echo "ANDROID_HOME=$HOME/android-sdk" >> $GITHUB_ENV
    echo "$HOME/android-sdk/cmdline-tools/latest/bin" >> $GITHUB_PATH
```

**本地环境**（已装好，用于小改动验证）：

```
~/android-sdk          604MB   Android SDK 35
gradle 9.7.1                   pkg install gradle
kotlin 2.4.20                  pkg install kotlin
aapt2                          pkg install aapt2（AGP 自带的跑不了）
patchelf                       pkg install patchelf
```

`gradle.properties` 必须加（AGP 自带的 aapt2 是 x86-64，Termux 跑不了）：
```properties
android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2
```

---

## ⚠️ proot 的 10 个坑（真机实测，改前必读）

全部在 `ProotRuntime.kt` 的注释里，这里列摘要：

| # | 坑 | 现象 | 解法 |
|---|---|---|---|
| 1 | **LD_PRELOAD** | `execve: Function not implemented` | 子进程环境 `remove("LD_PRELOAD")` |
| 2 | **绝对路径 rootfs** | `can't chmod/chdir: Function not implemented` | 用 `--rootfs=.` + `directory(rootfs)` |
| 3 | **PROOT_L2S_DIR** | 并发会话冲突 | 设成 `<rootfs>/.l2s`，预先 mkdir |
| 4 | **硬链接** | 解压/运行失败 | 必须 `--link2symlink` |
| 5 | **lstat 语义** | dpkg 报 symlink 警告 | 必须 `-L` |
| 6 | **残留进程** | 退出后阻塞 | 必须 `--kill-on-exit` |
| 7 | **kernel-release 格式** | `can't find hwcap field` | 用完整格式（见源码常量） |
| 8 | **执行权限** | apt 报 `Permission denied` | 解压后恢复可执行位 |
| 9 | **apt https** | `Method https did not start` | 源用 `http://` 而非 `https://` |
| 10 | **Android 路径绑定** | 动态链接失败 | 绑 `/linkerconfig/ld.config.txt` 等 |

**另外**：proot 二进制的 `RUNPATH` 原本硬编码 Termux 路径，必须 patchelf：
```bash
patchelf --set-rpath '$ORIGIN' libproot.so
patchelf --set-rpath '$ORIGIN' libtalloc.so.2
patchelf --set-rpath '$ORIGIN' libandroid-shmem.so
```

---

## 资源

**GitHub Release `rootfs-v1`**：
- `ubuntu-base-24.04-arm64.tar.gz`（28MB）— Ubuntu 24.04 base rootfs
- `ccm-node-kernel.tar.gz`（14MB）— Node 内核（core + web + 前端）

**APK**：Release `build-N` 里，约 16MB

---

## 验证状态

| 环节 | 状态 |
|---|---|
| 云端编译 | ✅ 4 分钟 |
| APK 含 proot | ✅ 16MB |
| Ubuntu proot 启动 | ✅ 24.04.3 LTS |
| apt update | ✅ 清华 http 源 |
| Node.js 安装 | ✅ 18.19.1 |
| PTY | ✅ /dev/pts/2 |
| CCM 内核启动 | ✅ |
| Web 服务 :3456 | ✅ HTTP 200 |
| React 前端 | ✅ HTML + 874KB JS |
| Node → 桥 | ✅ 桥可用 |
| phone.snapshot | ✅ 返回元素 |
| phone.click | ✅ 执行成功 |

---

## 已知缺口

1. **rootfs 首次安装要联网**（28MB + Node 约 50MB）
2. **无障碍服务需用户手动开启**（系统设置 → 无障碍 → CCM）
3. **MediaProjection 需用户授权一次**（弹「开始录制」确认框）
4. **首次安装约 3~8 分钟**（取决于网速）
5. **`RootfsManager` 的 tar 解压没保留原始 mode** —— 靠 `fixPermissions()` 事后修复，
   更稳妥的做法是解压时直接按 tar 的 mode 设置（`TarExtractor` 已读到 mode，待接）

---

## 与 Termux 模式的关系

**同一份内核，两种运行环境**：

```
Termux 模式（现状）          CCM 模式（新增）
─────────────────           ─────────────────
rish + dumpsys              AccessibilityService
termux-notification         NotificationManager
termux-clipboard-*          ClipboardManager
静音音频保活                 前台服务
```

**Node 侧用适配层自动切换**（`ccm-adapters.mjs`）：
- 检测到桥（3457 可达）→ 用原生实现
- 检测不到 → 保持原实现（Termux 兼容）

所以 `claude-code-mobile` 的代码**不需要 fork**，两边共用。
