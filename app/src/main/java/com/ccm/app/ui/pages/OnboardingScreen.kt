package com.ccm.app.ui.pages

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccm.app.R
import com.ccm.app.bridge.ShizukuBridge
import com.ccm.app.runtime.ProotRuntime
import com.ccm.app.runtime.RootfsManager
import com.ccm.app.ui.common.PainterIcon
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme
import com.ccm.app.ui.theme.CcmMono
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首次启动引导页 —— **三步分页向导**。
 *
 * ══════════════════════════════════════════════════════════════
 *  2026-10-11：从单页堆叠改成分页
 * ══════════════════════════════════════════════════════════════
 *
 * 用户原话：
 * > 「这引导页太乱了，分几页，但 UI 差得不多，右下角有一个翻页按钮，
 * >   每做完一项后继续下一页。」
 *
 * 原来是**单页堆叠**：说明卡 + 环境选择 + 工具链选择 + 开始按钮全挤在
 * 一屏里。现在拆成三页 —— UI 组件不变（仍是 InfoCard / RadioButton /
 * Button 那套），只换组织方式：
 *
 * | 页 | 内容 | 进入下一页的条件 |
 * |---|---|---|
 * | 1 | 授予「所有文件访问」 | 已授权 |
 * | 2 | 环境选择（内置 proot / 外接 Termux） | 选完 |
 * | 3 | 工具链选择 + 安装（**仅内置 proot 时出现**） | 安装完成 |
 *
 * **选了外接 Termux → 跳过第 3 页**：第 2 页点「使用 Termux」写盘后
 * 直接 `onReady()`（工具装在 Termux 里，不归我们管）。
 *
 * 翻页按钮固定在**右下角**（左下角显示当前步数），文案随页变：
 * 未授权时「去授权」→ 授权后「继续」→ 第 3 页「开始安装」/「重试」。
 *
 * ## 第 1 页（2026-10-11 新增）
 *
 * 数据根目录改成了 `Download/Claude/`（外部存储：用户可见可备份，
 * proot 与 Termux 两边都能访问）。代价是 Android 11+ 必须拿到
 * `MANAGE_EXTERNAL_STORAGE`（「所有文件访问」）—— 否则：
 *   · 会话存不了
 *   · 终端环境装不了
 *   · 配置读不了
 *
 * **不做回落** —— 没授权就卡在第 1 页。回落只会让用户以为能用、
 * 实际到处报错，那种失败方式比拦住他更糟。
 *
 * `phase` 状态机（0 未开始 / 1 安装中 / 2 完成 / 3 失败）**保留**，
 * 但只在第 3 页用；失败时仍**停住不跳走**，让用户看完日志再决定。
 *
 * ══════════════════════════════════════════════════════════════
 *  这个页面在 2026-09-27 重写过，因为用户报了三个问题
 * ══════════════════════════════════════════════════════════════
 *
 * 用户原话：
 * > 「软件里刚进去就是过时的，什么node内核啥的。点击授权截图，
 * >   其实软件请求的根本不是截图权限。配色也是operit ai的，和claude不沾边」
 *
 * 三处都改了：
 *
 * **① 文案过时** —— 原文案列了三条「需要安装」：
 * ```
 * • Ubuntu 24.04 base（约 28MB）
 * • Node.js 运行时（约 50MB，apt 下载）
 * • Claude Code Mobile 内核（约 14MB）
 * ```
 * 后两条是**老架构的遗留**：内核逻辑已经全部移植到 Kotlin，
 * 不再需要下载 Node 内核；而 Node.js 现在是**可选项**（勾了才装）。
 * 把可选说成必需，用户会以为不装就用不了。
 *
 * **② 截图授权没用** —— 原来有个「授权截屏」按钮，调的是
 * `MediaProjectionManager.createScreenCaptureIntent()`，
 * 弹出来的是**系统录屏对话框**。但 `phone_screenshot` 工具走的是
 * Shizuku 的 `latestFrame()`，跟 MediaProjection 毫无关系。
 * 用户点「授权截图」，授权了个用不上的东西 —— 这是纯粹的误导。
 * 现在改成一句说明：截屏需要 Shizuku，装好就能用，不用单独授权。
 *
 * **③ 配色是 Operit 的** —— 原来整个页面用 `MaterialTheme.colorScheme`，
 * 那是 Material3 默认的紫色（`#6750A4`），跟 Claude 一点关系没有。
 * 现在全部走 [CCMTheme]（Claude 橙 `#D97757` + 米白 `#F8F8F6`）。
 *
 * ══════════════════════════════════════════════════════════════
 *  这个页面负责什么
 * ══════════════════════════════════════════════════════════════
 * - 第 1 页：拿到「所有文件访问」（数据根在 Download/Claude/ 的前提）
 * - 第 2 页：选运行环境（内置 proot / 外接 Termux）
 * - 第 3 页：选工具链并触发 [RootfsManager.install]
 *   （复制/下载 → 解压 → 配置 → 工具链 → 自检）
 * - 显示真实进度（**不是假进度条**）
 * - 失败时**停住**并显示日志（用户反馈过「失败后自动跳走，想截图都来不及」）
 * - 环境自检结果（proot 能否启动）—— 提前暴露问题，别等用户点工具才报错
 *
 * @param onReady 环境就绪回调（父组件据此切到主界面）
 */

@Composable
fun OnboardingScreen(
    modifier: Modifier = Modifier,
    onReady: () -> Unit = {},
) {

    val ctx = LocalContext.current
    val colors = CCMTheme.colors
    val scope = rememberCoroutineScope()

    val rootfs = remember { RootfsManager(ctx) }
    val proot = remember { ProotRuntime(ctx) }

    /** 0 = 未开始；1 = 安装中；2 = 已完成；3 = 失败 */
    var phase by remember { mutableStateOf(0) }
    var progress by remember { mutableFloatStateOf(0f) }
    var log by remember { mutableStateOf("") }
    /** 失败后**停住**，不自动跳走 —— 用户要看日志。 */
    var failed by remember { mutableStateOf(false) }

    // ══════════════════════════════════════════════════════════════
    //  分页状态（2026-10-11 新增）
    // ══════════════════════════════════════════════════════════════
    /** 当前页：1 = 权限申请；2 = 环境选择；3 = 工具链（仅内置 proot 才走到） */
    var page by remember { mutableStateOf(1) }

    // ── 第 1 页：所有文件访问（MANAGE_EXTERNAL_STORAGE）─────────────
    //
    // 数据根目录是 Download/Claude/（外部存储）。Android 11（API 30）起，
    // 读写 /sdcard 根下任意目录必须拿这个权限 —— 它是**特殊权限**，
    // 不在运行时权限体系里，只能跳系统设置页让用户开。
    //
    // API < 30 时外部存储是另一套模型（READ/WRITE_EXTERNAL_STORAGE），
    // 直接放行，不做额外申请。
    var hasAllFiles by remember {
        mutableStateOf(
            if (android.os.Build.VERSION.SDK_INT >= 30)
                android.os.Environment.isExternalStorageManager()
            else true
        )
    }

    // 悬浮窗权限（可选）—— Clawd 吉祥物用
    var hasOverlay by remember {
        mutableStateOf(com.ccm.app.service.ClawdOverlayService.canDrawOverlays(ctx))
    }

    // ── 运行环境选择（2026-10-06）────────────────────────────────────
    // 首次进入先选「内置 proot」还是「外接 Termux」，工具链选择在其后。
    // 选完写进 config.json 的 envMode，之后不再问（MainActivity 按 rootfs
    // 是否已装分流，老用户不会进这里）。
    var envMode by remember { mutableStateOf("proot") }
    // Termux 是否装了（没装时提示 + 自动回 proot，不让用户卡在死路）
    var termuxInstalled by remember { mutableStateOf<Boolean?>(null) }
    // RUN_COMMAND 权限状态（2026-10-06）：装了 Termux 还要这个权限才能跑命令。
    // 它是 dangerous 级 —— 弹系统框就能授（不是 signature，无需同签名）。
    var termuxPermGranted by remember { mutableStateOf(false) }
    val termuxPermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> termuxPermGranted = granted }
    LaunchedEffect(Unit) {
        termuxInstalled = try {
            ctx.packageManager.getPackageInfo("com.termux", 0); true
        } catch (_: Throwable) { false }
        termuxPermGranted = com.ccm.app.tools.bash.TermuxChannel
            .hasRunCommandPermission(ctx)
    }

    val toolchains = remember { com.ccm.app.runtime.ToolchainCatalog.ALL }
    var selectedToolchains by remember {
        mutableStateOf(toolchains.filter { it.defaultChecked }.map { it.id }.toSet())
    }

    // Shizuku 状态（只读展示，不请求授权）
    var shizukuOk by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        shizukuOk = try { ShizukuBridge.granted() } catch (_: Throwable) { false }
    }

    // 进度条跟随日志自动滚到底（用户往上翻时停止跟随）
    val scrollState = rememberScrollState()
    var autoFollow by remember { mutableStateOf(true) }
    LaunchedEffect(log) {
        if (autoFollow) {
            try { scrollState.scrollTo(scrollState.maxValue) } catch (_: Throwable) {}
        }
    }
    LaunchedEffect(scrollState.value, scrollState.maxValue) {
        autoFollow = (scrollState.maxValue - scrollState.value) < 60
    }

    // ── 第 1 页：授权状态轮询 ───────────────────────────────────────
    //
    // 用户跳去系统设置开开关，回到 App 时我们要自动发现。
    // 这是本项目既有做法（SettingsGeneralTab.kt:487 同款）：每秒探一次、
    // 授权后立刻停 —— 从设置返回后状态最多 1 秒更新。
    //
    // 放在**顶层**而不是 when(page) 的第 1 分支里：状态提升到顶层后，
    // 切页不会因为分支离开组合而中断轮询。
    // 悬浮窗权限同样轮询（从设置页返回后自动更新）
    if (!hasOverlay) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(1000)
                if (com.ccm.app.service.ClawdOverlayService.canDrawOverlays(ctx)) {
                    hasOverlay = true
                    break
                }
            }
        }
    }

    if (!hasAllFiles && android.os.Build.VERSION.SDK_INT >= 30) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(1000)
                if (android.os.Environment.isExternalStorageManager()) {
                    hasAllFiles = true
                    break
                }
            }
        }
    }

    /**
     * 打开系统的「所有文件访问」设置页。
     *
     * 两级兜底（照抄 SettingsGeneralTab.kt:504 的写法）：先试**带包名**的
     * 页面（直接落在本 App 的开关上，用户少找一步），个别 ROM 不认这个
     * action 时退到**全局列表**页。两级都失败就静默放弃 —— 引导页不能
     * 因为跳设置失败而崩。
     */
    val openAllFilesSettings: () -> Unit = {
        try {
            ctx.startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:${ctx.packageName}"),
                )
            )
        } catch (_: Throwable) {
            try {
                ctx.startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
                    )
                )
            } catch (_: Throwable) {}
        }
    }

    /**
     * 落盘 envMode（AppGraph 装配时按它选 Bash 通道）。
     *
     * 写在安装**之前**：即使后面安装失败，用户的选择也保住了。
     * 原来这段内联在「开始安装」按钮里；现在第 2 页（选 Termux 直接进
     * 主界面）也要写，抽出来共用，避免两份漂移。
     */
    val saveEnvMode: () -> Unit = {
        try {
            // 【2026-10-11】加 ensureDirs（数据根在 Download/Claude/，
            // 首次进来时可能不存在）
            val storage = com.ccm.app.core.FileAppStorage(com.ccm.app.core.resolveDataRoot(ctx))
                .also { it.ensureDirs() }
            val loadR = com.ccm.app.core.provider.AppConfig.load(storage.configFile)
            com.ccm.app.core.provider.AppConfig.save(
                loadR.config.copy(envMode = envMode),
                storage.configFile,
            )
        } catch (_: Throwable) {}
    }

    /**
     * 开始安装 —— 第 3 页的「开始安装」与失败后的「重试」共用。
     *
     * 原来这两处是**复制两份**的同一段代码；抽成 lambda 后改进度文案
     * 不会漏改一处。
     *
     * 【2026-10-06 保险】权限没授予却选了 termux（理论不可达，因为
     * RadioButton 已禁用）→ 弹授权框并中止，别让用户进到「选了但用不了」
     * 的状态。注意这里**不能用 `return@Button`** —— 已经不在 Button 的
     * onClick 里了，改用 if/else。
     */
    val startInstall: () -> Unit = {
        if (envMode == "termux" && !termuxPermGranted) {
            termuxPermLauncher.launch(
                com.ccm.app.tools.bash.TermuxChannel.PERMISSION_RUN_COMMAND
            )
        } else {
            phase = 1
            progress = 0f
            log = ""
            failed = false
            // 落盘 envMode（AppGraph 装配时按它选 Bash 通道）——
            // 写在最前：即使后面安装失败，用户的选择也保住了。
            saveEnvMode()
            scope.launch { runInstall(rootfs, proot, selectedToolchains, envMode) { p, msg ->
                progress = p
                if (msg.isNotEmpty()) log = if (log.isEmpty()) msg else "$log\n$msg"
            }.also { ok ->
                if (ok) {
                    phase = 2
                    delay(400)
                    onReady()
                } else {
                    phase = 3
                    failed = true
                }
            } }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgMain),
    ) {
        // ══════════════════════════════════════════════════════════
        //  内容区（可滚动）—— 翻页栏固定在它下面，不随内容滚走
        // ══════════════════════════════════════════════════════════
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── Claude 标志 + 标题 ────────────────────────────────────────
            PainterIcon(R.drawable.ic_hero_star, size = 36.dp, tint = colors.claudeOrange)
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Claude Code Mobile",
                style = CCMText.body18.copy(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
                color = colors.textTitle,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "一个跑在手机上的 AI 编程助手",
                style = CCMText.body14,
                color = colors.textSecondary,
            )

            Spacer(Modifier.height(28.dp))


            when (page) {
                // ══════════════════════════════════════════════════
                //  第 1 页 —— 授予「所有文件访问」
                // ══════════════════════════════════════════════════
                1 -> {
                    InfoCard {
                        Text(
                            text = "授予「所有文件访问」",
                            style = CCMText.body14.copy(fontWeight = FontWeight.Medium),
                            color = colors.textMain,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "CCM 把数据存在 Download/Claude/ —— 会话、终端环境、" +
                                    "配置都在那儿。放在外部存储是为了让你能在文件管理器里" +
                                    "直接看到、备份，换机或重装 App 也不丢。",
                            style = CCMText.body14,
                            color = colors.textSecondary,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            // 【为什么说得这么直白】这不是可选优化 —— 没授权
                            // 会话写不进去、环境装不了，引导页会一直卡在这一步。
                            // 用户有权知道「为什么必须给」，而不是被一个权限框
                            // 拦住还不知道是干嘛用的。
                            text = "Android 11 起，App 默认只能读写自己的私有目录，" +
                                    "要访问 Download/Claude/ 必须单独授予这个权限。" +
                                    "没有它，会话存不了、环境装不了 —— 这一步不能跳过。",
                            style = CCMText.body12,
                            color = colors.textSecondary,
                        )
                    }

                    Spacer(Modifier.height(18.dp))

                    // ── 授权状态（轮询实时更新）────────────────────
                    InfoCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (hasAllFiles) "✓" else "·",
                                style = CCMText.body14,
                                color = if (hasAllFiles) colors.success else colors.textSecondary,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (hasAllFiles) "已授权" else "尚未授权",
                                    style = CCMText.body14,
                                    color = colors.textMain,
                                )
                                Text(
                                    text = if (hasAllFiles) {
                                        "点右下角「继续」进入下一步"
                                    } else {
                                        "点右下角「去授权」，在系统设置里打开开关 —— " +
                                                "打开后本页会自动更新"
                                    },
                                    style = CCMText.body12,
                                    color = colors.textSecondary,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // ── 悬浮窗权限（可选）──────────────────────────
                    // Clawd 吉祥物退到后台时飘在屏幕上（对标官方 Claude 的
                    // 桌面宠物）。没授权不影响对话，只是看不到动画 ——
                    // 所以**可选**，不像「所有文件访问」那样卡住流程。
                    InfoCard {
                        Text(
                            text = "悬浮窗（可选）",
                            style = CCMText.body14.copy(fontWeight = FontWeight.Medium),
                            color = colors.textMain,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "授权后，App 退到后台时会有个 Clawd 小吉祥物飘在" +
                                    "屏幕上，显示 AI 的工作状态。不影响功能，纯装饰。",
                            style = CCMText.body14,
                            color = colors.textSecondary,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (hasOverlay) "✓" else "·",
                                style = CCMText.body14,
                                color = if (hasOverlay) colors.success else colors.textSecondary,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = if (hasOverlay) "已授权" else "未授权（不影响使用）",
                                style = CCMText.body14,
                                color = colors.textMain,
                                modifier = Modifier.weight(1f),
                            )
                            if (!hasOverlay) {
                                Text(
                                    text = "去授权",
                                    style = CCMText.body12.copy(fontWeight = FontWeight.Medium),
                                    color = colors.accent,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(7.36.dp))
                                        .clickable {
                                            try {
                                                ctx.startActivity(
                                                    android.content.Intent(
                                                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                        android.net.Uri.parse("package:${ctx.packageName}"),
                                                    )
                                                )
                                            } catch (_: Throwable) {
                                                try {
                                                    ctx.startActivity(
                                                        android.content.Intent(
                                                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                        )
                                                    )
                                                } catch (_: Throwable) {}
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }

                // ══════════════════════════════════════════════════
                //  第 2 页 —— 运行环境选择
                // ══════════════════════════════════════════════════
                2 -> {
                        // ── 运行环境选择 ────────────────────────────────────────
                        // 这一页决定**要不要第 3 页**：选 Termux 则工具装在 Termux 里
                        // （不由我们管），点「使用 Termux」直接进主界面；选 proot 才需要
                        // 装工具链。
                        InfoCard {
                            Text("选择运行环境", style = CCMText.body14.copy(fontWeight = FontWeight.Medium), color = colors.textMain)
                            Spacer(Modifier.height(8.dp))

                            // 内置 proot
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { envMode = "proot" },
                                verticalAlignment = Alignment.Top,
                            ) {
                                RadioButton(selected = envMode == "proot", onClick = { envMode = "proot" })
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("内置 proot", style = CCMText.body14, color = colors.textMain)
                                    Text(
                                        "App 自带 Ubuntu 环境，点一下自动装好，不依赖其他 App。" +
                                            "适合想要开箱即用、环境干净可复现的；缺点是文件与手机系统隔一层。",
                                        style = CCMText.body12, color = colors.textSecondary,
                                    )
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            // 外接 Termux
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    // 没装 Termux 时点了自动弹回 proot —— 不让用户卡死路
                                    if (termuxInstalled == true) envMode = "termux"
                                },
                                verticalAlignment = Alignment.Top,
                            ) {
                                // 未装 Termux 或未授权时禁用 —— 授权后自动变可选
                                // （termuxPermGranted 是 remember state，launcher 回调会更新它）
                                RadioButton(
                                    selected = envMode == "termux",
                                    enabled = termuxInstalled == true && termuxPermGranted,
                                    onClick = {
                                        if (termuxInstalled == true && termuxPermGranted) envMode = "termux"
                                    },
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        if (termuxInstalled == false) "外接 Termux（未安装）" else "外接 Termux",
                                        style = CCMText.body14,
                                        color = if (termuxInstalled == false) colors.textSecondary else colors.textMain,
                                    )
                                    Text(
                                        if (termuxInstalled == false) {
                                            "未检测到 Termux。从 F-Droid 安装 Termux 后可回来选它；" +
                                                "现在选内置 proot 也能用。"
                                        } else {
                                            // 【2026-10-06 修文案】原来写「工具、配置、~/.claude 都在那边」
                                            // —— 不准确：CCM 的配置在 App 私有目录（Termux 访问不了），
                                            // ~/.claude 是 CLI 的东西，跟 Termux 无关。
                                            // 真正成立的是：命令跑在 Termux 里，能用它装的工具链。
                                            "Bash 命令跑在你已装的 Termux 里，直接用它已有的 " +
                                                "pkg/apt 工具链（git、node、python 等），无需再装一遍。" +
                                                "文件通过 /sdcard 与 App 互通。" +
                                                "选它则跳过下面的工具链安装。"
                                        },
                                        style = CCMText.body12, color = colors.textSecondary,
                                    )
                                    // ── 授权按钮（2026-10-06 重做）──────────────────
                                    // 原来是一行 body11 灰色小字「点此授权」，混在描述里 ——
                                    // 用户不会注意到那里能点。现在做成**实心按钮**：
                                    // 橙色底 + 白字 + 明确的行动文案。
                                    if (termuxInstalled == true && !termuxPermGranted) {
                                        Spacer(Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(colors.claudeOrange)
                                                .clickable {
                                                    termuxPermLauncher.launch(
                                                        com.ccm.app.tools.bash.TermuxChannel.PERMISSION_RUN_COMMAND
                                                    )
                                                }
                                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    "① 先点这里授权",
                                                    style = CCMText.body13.copy(fontWeight = FontWeight.SemiBold),
                                                    color = androidx.compose.ui.graphics.Color.White,
                                                )
                                                Text(
                                                    "系统会弹框问「允许在 Termux 中运行命令」—— 必须允许，否则选它也用不了",
                                                    style = CCMText.body11,
                                                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f),
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            "授权后，左边的圆点才会变成可选状态",
                                            style = CCMText.body11, color = colors.textSecondary,
                                        )
                                    }
                                }
                            }
                        }


                }

                // ══════════════════════════════════════════════════
                //  第 3 页 —— 工具链选择 + 安装（**仅内置 proot 才走到**）
                //
                //  `phase` 状态机只在这一页用：0 未开始 / 1 安装中 /
                //  2 完成 / 3 失败。安装中与失败的界面都留在本页内，
                //  不再回到「单页大杂烩」那种堆叠。
                // ══════════════════════════════════════════════════
                else -> when (phase) {
                    // ── 未开始：说明 + 工具链清单 ───────────────
                    0 -> {
                        InfoCard {
                            Text(
                                text = "安装内置 Linux 环境（Ubuntu 24.04，已内置）",
                                style = CCMText.body14,
                                color = colors.textMain,
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                // 【为什么这么写】原文案列了「Node.js 运行时（约 50MB，apt 下载）」
                                // 「Claude Code Mobile 内核（约 14MB）」—— 两条都是老架构遗留。
                                // 内核已移植进 Kotlin；Node 现在是可选工具链。
                                // 把可选说成必需，用户会以为不装就用不了。
                                text = "它用来跑 AI 执行命令的工具（git / curl / python 等）。" +
                                        "首次安装约 10~20 秒，之后不再需要联网。\n\n" +
                                        "手机操作（点击/输入/截图）需要 Shizuku，是可选的；" +
                                        "没装也能正常对话和读写文件。",
                                style = CCMText.body14,
                                color = colors.textSecondary,
                            )
                        }


                        Spacer(Modifier.height(18.dp))

                        InfoCard {
                            Text("选择要安装的工具链", style = CCMText.body14.copy(fontWeight = FontWeight.Medium), color = colors.textMain)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                // 环境本身是内置的（不用下载），下面这些是可选命令工具。
                                // 把可选说成必需，用户会以为不装就用不了（2026-09-27 的教训）。
                                text = "Linux 环境本身（Ubuntu 24.04）已内置，不用下载。" +
                                        "下面这些是**可选的**命令工具，勾了会在装环境时一并装好；" +
                                        "现在不勾也行，之后在设置里随时能补。",
                                style = CCMText.body12,
                                color = colors.textSecondary,
                            )
                            toolchains.forEach { chain ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable { if (chain.id != "base") selectedToolchains = if (chain.id in selectedToolchains) selectedToolchains - chain.id else selectedToolchains + chain.id },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(checked = chain.id in selectedToolchains, onCheckedChange = { checked -> if (chain.id != "base") selectedToolchains = if (checked) selectedToolchains + chain.id else selectedToolchains - chain.id })
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(chain.name, style = CCMText.body14, color = colors.textMain)
                                        Text("${chain.description} · 约 ${chain.sizeMB}MB", style = CCMText.body12, color = colors.textSecondary)
                                    }
                                }
                            }
                        }


                        Spacer(Modifier.height(20.dp))
                        CapabilityList(shizukuOk = shizukuOk)

                    }

                    // ── 安装中 ──────────────────────────────────
                    1 -> {
                        InfoCard {
                            Text(
                                text = "正在安装…",
                                style = CCMText.body14.copy(fontWeight = FontWeight.Medium),
                                color = colors.textMain,
                            )
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = colors.claudeOrange,
                                trackColor = colors.border,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "${(progress * 100).toInt()}%",
                                style = CCMText.body14,
                                color = colors.textSecondary,
                            )
                        }

                        if (log.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            LogBox(log = log, scrollState = scrollState)
                        }
                    }


                    // ── 完成（随后自动 onReady）─────────────────
                    2 -> {
                        InfoCard {
                            Text(
                                text = "环境就绪",
                                style = CCMText.body14.copy(fontWeight = FontWeight.Medium),
                                color = colors.success,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "正在进入…",
                                style = CCMText.body14,
                                color = colors.textSecondary,
                            )
                        }
                    }


                    // ── 失败 —— 停住，让用户看完日志 ────────────
                    else -> {
                        InfoCard {
                            Text(
                                text = "安装未完成",
                                style = CCMText.body14.copy(fontWeight = FontWeight.Medium),
                                color = colors.error,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                // 失败时最需要日志，所以不自动跳走（用户反馈过
                                // 「弹了『部分失败』后就清屏，想截图都来不及」）
                                text = "下面是完整日志，可以截图反馈。常见原因：存储空间不足、" +
                                        "网络中断。已下载的部分会保留，重试不用从头开始。",
                                style = CCMText.body14,
                                color = colors.textSecondary,
                            )
                        }

                        Spacer(Modifier.height(16.dp))
                        LogBox(log = log.ifEmpty { "（没有输出）" }, scrollState = scrollState)

                        Spacer(Modifier.height(16.dp))
                        // 「重试」不在这里 —— 它在**右下角的翻页按钮**上（文案随
                        // phase 变成「重试」）。页内只留次要出口：让用户先进去用起来
                        // （对话/读写文件不依赖工具链），环境回头在设置页补装。
                        Button(
                            onClick = onReady,
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.hover,
                                contentColor = colors.textMain,
                            ),
                        ) {
                            Text("跳过（稍后再装）")
                        }
                    }

                }
            }

            Spacer(Modifier.height(32.dp))
        }

        // ══════════════════════════════════════════════════════════
        //  底部：步骤指示 + 翻页按钮（右下角）
        // ══════════════════════════════════════════════════════════
        //
        // 文案 / 可用性 / 动作都随当前页变：
        //   第 1 页  未授权 → 「去授权」（跳系统设置）；已授权 → 「继续」
        //   第 2 页  选 Termux → 「使用 Termux」（跳过第 3 页，直接进主界面）
        //           选 proot  → 「继续」
        //   第 3 页  未开始 → 「开始安装」；安装中 → 禁用；失败 → 「重试」
        //
        // 步数为什么是动态的：选了 Termux 就只有 2 步，写死「/ 3」会让
        // 用户以为漏了一步。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when (page) {
                    1 -> "步骤 1 / 3"
                    2 -> if (envMode == "termux") "步骤 2 / 2" else "步骤 2 / 3"
                    else -> "步骤 3 / 3"
                },
                style = CCMText.body12,
                color = colors.textSecondary,
            )
            Button(
                onClick = {
                    when (page) {
                        // 第 1 页：未授权 → 跳系统设置；已授权 → 下一页
                        1 -> if (!hasAllFiles) {
                            openAllFilesSettings()
                        } else {
                            page = 2
                        }
                        // 第 2 页：Termux → 写配置后直接进主界面（跳过第 3 页）；
                        //         proot  → 进第 3 页选工具链
                        2 -> if (envMode == "termux") {
                            if (!termuxPermGranted) {
                                termuxPermLauncher.launch(
                                    com.ccm.app.tools.bash.TermuxChannel.PERMISSION_RUN_COMMAND
                                )
                            } else {
                                saveEnvMode()
                                onReady()
                            }
                        } else {
                            page = 3
                        }
                        // 第 3 页：开始安装 / 失败后重试
                        else -> if (phase == 0 || phase == 3) startInstall()
                    }
                },
                // 第 3 页安装中/刚完成时禁用，避免重复触发
                enabled = when (page) {
                    1 -> true              // 未授权时点了是「去授权」，不是死路
                    2 -> true
                    else -> phase == 0 || phase == 3
                },
                modifier = Modifier.width(170.dp).height(46.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.claudeOrange,
                    // 用 contentColor（不是 Text(color = ...)）：禁用态下
                    // ButtonDefaults 会自动换成灰字，白字写在灰底上看不清。
                    contentColor = androidx.compose.ui.graphics.Color.White,
                ),
            ) {
                Text(
                    when (page) {
                        1 -> if (hasAllFiles) "继续" else "去授权"
                        2 -> if (envMode == "termux") "使用 Termux" else "继续"
                        else -> when (phase) {
                            0 -> "开始安装"
                            1 -> "安装中…"
                            2 -> "环境就绪"
                            else -> "重试"
                        }
                    }
                )
            }
        }
    }
}

/**
 * 安装过程的进度中转站。
 *
 * 【为什么需要这个类】`RootfsManager.install` 的 `onProgress` 在**工作线程
 * 同步调用**，而进度要写进 Compose state（必须主线程）。直接用
 * `withContext(Dispatchers.Main)` 会在回调里阻塞工作线程 —— 安装本身是后台
 * 任务，这样写会让「解压」和「刷 UI」互相等，进度条一顿一顿的。
 *
 * 所以：回调只**记录**，由独立的推送协程节流刷 UI。
 *
 * ⚠️ 字段必须 `@Volatile` —— 工作线程写、主线程读。
 * 注意 **`@Volatile` 不能修饰局部变量**（Kotlin 的 `@Volatile` 目标是 FIELD），
 * 所以这里用一个 private class 承载，而不是在函数里声明几个 `@Volatile var`。
 * 后者编译期直接报「This annotation is not applicable to target
 * 'local variable'」。
 */
/**
 * 【2026-10-07 private → internal】设置 → 环境 的「安装 Linux 环境」按钮
 * 也用它 —— envMode=termux 的用户引导页会被跳过，rootfs 一直没有安装入口，
 * 插件宿主/MCP 的 Node 又必须跑在里面。
 */
internal class ProgressSink {
    @Volatile var progress: Float = 0f
    @Volatile var log: String = ""
    @Volatile var finished: Boolean = false
}

/**
 * 执行安装流程，把 [RootfsManager.install] 的回调翻译成「进度 + 日志」。
 *
 * 抽出来是因为「开始安装」和「重试」两个按钮要走完全一样的流程 ——
 * 复制粘贴两份的话，将来改进度文案必然漏改一处。
 *
 * @param emit (进度 0~1, 日志行) —— 日志为空串时表示只更新进度
 * @return 是否成功
 */
/**
 * 【2026-10-07 private → internal】同 [ProgressSink]：设置页入口复用。
 * 设置页调用时传 `envMode = "proot"`（强制装 rootfs，与命令环境的
 * envMode 解耦）+ `selectedToolchains = emptySet()`（工具链随后按需装）。
 */
internal suspend fun runInstall(
    rootfs: RootfsManager,
    proot: ProotRuntime,
    selectedToolchains: Set<String>,
    /** 运行环境模式（"proot" / "termux"）。termux 时整段 rootfs 安装跳过。 */
    envMode: String = "proot",
    emit: suspend (Float, String) -> Unit,
): Boolean = coroutineScope {
    val sink = ProgressSink()

    // 推送协程：每 120ms 把最新进度刷进 UI。
    // 为什么要节流：install 的回调在复制 28MB 时会调几百次，
    // 每次都推 UI 会让主线程忙于重组，反而更卡。
    val pump = launch(Dispatchers.Main) {
        while (!sink.finished) {
            val line = sink.log
            sink.log = ""
            emit(sink.progress, line)
            delay(120)
        }
        val tail = sink.log
        sink.log = ""
        emit(sink.progress, tail)   // 收尾再推一次，别漏最后一行
    }

    // ══════════════════════════════════════════════════════════════
    //  Termux 模式：不装 rootfs（2026-10-06）
    // ══════════════════════════════════════════════════════════════
    //
    // 用户已有 Termux 环境，命令通过 RUN_COMMAND Intent 跑在那边。
    // 这里只提示一次 allow-external-apps 配置（我们探测不到 Termux 私有
    // 目录里的 termux.properties），**不阻断**。
    //
    // ⚠️ 必须放在 pump 定义**之后** —— 里面要 pump.join() 收尾，
    // 放前面编译报 Unresolved reference 'pump'（CI 实测抓到的）。
    if (envMode == "termux") {
        sink.progress = 0.5f
        sink.log = "已选择外接 Termux —— 跳过内置环境安装。"
        sink.log = "提示：需在 Termux 里执行 echo 'allow-external-apps=true' >> ~/.termux/termux.properties 并重启 Termux，否则命令会静默失败。"
        sink.finished = true
        pump.join()
        emit(1f, "外接 Termux 模式：无需安装内置环境。")
        return@coroutineScope true
    }

    val result = withContext(Dispatchers.IO) {
        try {
            // 【2026-10-05 改：阶段文案与进度】
            //
            // 问题一：解压阶段显示「0MB / 0MB」。
            //   根因：安装改走 shell 脚本后，脚本的进度是**文本行**（照搬 Operit），
            //   上报时 total 传 0/1，除以 1048576 就是 0。这个数字没有意义。
            //   照搬 Operit 的做法：**不显示字节数**，只显示当前在做什么。
            //
            // 问题二：各阶段的百分比区间要重新分配。
            //   新流程里「复制」占大头（64MB 本地复制），解压用 busybox（快），
            //   所以给复制更多区间，避免进度条长时间不动。
            rootfs.install { stage, done, total ->
                val pct = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                sink.progress = when (stage) {
                    "copy", "download" -> pct * 0.5f
                    "extract" -> 0.5f + pct * 0.4f
                    else -> 0.9f + pct * 0.1f
                }
                sink.log = when (stage) {
                    "copy" -> if (total > 0) {
                        "复制内置环境包 ${done / 1048576}MB / ${total / 1048576}MB"
                    } else {
                        "复制内置环境包…"
                    }
                    "download" -> if (total > 0) {
                        "下载环境包 ${done / 1048576}MB / ${total / 1048576}MB"
                    } else {
                        "下载环境包…"
                    }
                    // 解压/配置阶段：显示脚本输出的文本行号，不显示字节数
                    // （脚本用 progress_echo 报进度，Kotlin 侧按行计数）
                    "extract" -> if (done > 0) "解压中（第 $done 步）" else "解压中…"
                    else -> "配置中…"
                }
            }
        } catch (t: Throwable) {
            Log.e("Onboarding", "安装异常", t)
            sink.log = "安装异常：${t.message}"
            false
        }
    }

    if (!result) {
        sink.finished = true
        pump.join()
        return@coroutineScope false
    }

    if (selectedToolchains.isNotEmpty()) {
        sink.progress = 0.92f
        sink.log = "正在安装所选工具链…"
        val toolchainsOk = withContext(Dispatchers.IO) {
            // 【2026-10-06 改：日志改为「滚动窗口」而不是单行覆盖】
            //
            // 原来是 `onLine = { line -> sink.log = line }` —— 每行都**覆盖**，
            // 所以 apt 装 110 个包的过程中，用户只会看到最后一行。
            // 加上命令原来还套了 `| tail -30`（tail 会缓冲），
            // 实际表现是「卡住不动，最后突然跳一行」—— 用户完全看不出在干活。
            //
            // 现在：去掉 tail（见 RootfsManager）+ 这里保留最后 8 行。
            // 既有实时感，又不会因为刷太快看不清。
            val logWindow = ArrayDeque<String>()
            fun pushLog(line: String) {
                logWindow.addLast(line)
                while (logWindow.size > 8) logWindow.removeFirst()
                sink.log = logWindow.joinToString("\n")
            }
            rootfs.installToolchains(
                selectedToolchains,
                exec = { command, onLine -> proot.exec(command, onLine = onLine) },
                onLine = { line -> pushLog(line) },
            )
        }
        if (!toolchainsOk) {
            sink.log = "工具链安装失败；检查日志后点重试，当前选择会保留。"
            sink.progress = 0.98f
            sink.finished = true
            pump.join()
            return@coroutineScope false
        }
    }

    sink.progress = 1f
    sink.log = "正在自检…"
    sink.finished = true
    pump.join()   // 等推送协程收尾，避免工具链阶段日志被吞

    // 装完跑一次自检 —— proot 起不来的报错极具误导性
    //（"Function not implemented" 看着像 rootfs 里的二进制坏了，
    //  实际是 proot 自己的 loader 找不到）。提前暴露，别等用户点工具才报错。
    emit(1f, "正在自检…")
    val diag = try { proot.selfCheck() } catch (t: Throwable) { "自检异常：${t.message}" }
    if (diag != null) {
        emit(1f, "环境自检未通过：$diag")
        // 自检失败**不算安装失败** —— 文件都在，可能只是这次探测超时。
        // 让它进去，真有问题在对话里报错更清楚（有上下文）。
    }

    true
}

/** 说明卡片（米白底 + 细边框，对齐 Web 的 `bg-claude-input border rounded-xl`）。 */
@Composable
private fun InfoCard(content: @Composable () -> Unit) {
    val colors = CCMTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.input)
            .padding(16.dp),
    ) { content() }
}

/** 日志区（等宽字体，限高可滚）。 */
@Composable
private fun LogBox(log: String, scrollState: androidx.compose.foundation.ScrollState) {
    val colors = CCMTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 200.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.hover)
            .verticalScroll(scrollState)
            .padding(12.dp),
    ) {
        Text(
            text = log,
            style = CCMText.body14.copy(fontFamily = CcmMono, fontSize = 11.sp),
            color = colors.textSecondary,
        )
    }
}

/**
 * 能力清单 —— 装完能用什么。
 *
 * ⚠️ 这里**不能有「授权截图」按钮**。原来有一个，调的是
 * `MediaProjectionManager.createScreenCaptureIntent()`（系统录屏对话框），
 * 但 `phone_screenshot` 走的是 Shizuku，两者无关。
 * 用户点了授权一个用不上的东西，是纯粹的误导。
 *
 * 现在只做**状态展示**：Shizuku 装了就说「已就绪」，没装就说「可选」。
 */
@Composable
private fun CapabilityList(shizukuOk: Boolean) {
    val colors = CCMTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "装好之后可以",
            style = CCMText.body14.copy(fontWeight = FontWeight.Medium),
            color = colors.textMain,
        )
        CapabilityRow("对话、写代码、读写文件", true, "")
        CapabilityRow("跑命令（git / curl / python…）", true, "需先装对应工具链")
        CapabilityRow(
            label = "操作手机（点击/输入/截图）",
            ok = shizukuOk,
            note = if (shizukuOk) "Shizuku 已就绪" else "需装 Shizuku（可选）",
        )
    }
}

/** 一行能力。 */
@Composable
private fun CapabilityRow(label: String, ok: Boolean, note: String) {
    val colors = CCMTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (ok) "✓" else "·",
            style = CCMText.body14,
            color = if (ok) colors.success else colors.textSecondary,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = CCMText.body14,
            color = colors.textMain,
            modifier = Modifier.weight(1f),
        )
        if (note.isNotEmpty()) {
            Text(
                text = note,
                style = CCMText.body14,
                color = colors.textSecondary,
            )
        }
    }
}
