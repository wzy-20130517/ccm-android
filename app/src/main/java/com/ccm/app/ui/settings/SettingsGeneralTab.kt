package com.ccm.app.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme

/** 下拉选项集合（职业 / 发送键）。 */
private val WORK_FUNCTION_OPTIONS = listOf(
    "工程师", "设计师", "产品经理", "学生", "教师",
    "写作 / 内容", "运营 / 市场", "科研 / 数据", "其他",
)
private val SEND_KEY_OPTIONS = listOf(
    "仅按钮（回车只换行）",
    "回车发送（Shift+Enter 换行）",
    "Ctrl+Enter 发送",
)

/**
 * 设置页 · General tab —— 对齐 `SettingsPage.tsx` 的 `renderGeneral()`。
 *
 * 区块顺序：个人资料 / 默认模型 / 自动压缩 / 工作区 / 对话 / 外观 / 关于。
 *
 * 间距：外层 Column 用 SettingsSectionGap(18.08) 统一间隔；SettingsDivider
 * 作为**子项**参与该间隔 → 上下各 18.08（对齐 Web：<hr> 本身就是 space-y-10 的子项）。
 * ⚠️ 分隔线不要再自己包 Spacer —— 会与父容器 spacedBy 叠加成三倍间距。
 */
@Composable
fun SettingsGeneralTab(modifier: Modifier = Modifier) {
    // 工作区保存的 Toast 用（2026-10-01）
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // 从用户资料读初始值（没配过就是空，让用户自己填）
    val profileStore = com.ccm.app.AppGraph.userProfileStore
    val initialProfile = remember { profileStore?.load() ?: com.ccm.app.core.user.UserProfile.EMPTY }
    var fullName by remember { mutableStateOf(initialProfile.fullName) }
    var callName by remember { mutableStateOf(initialProfile.displayName) }
    var workFunction by remember { mutableStateOf(initialProfile.workFunction) }
    var preferences by remember { mutableStateOf(initialProfile.personalPreferences) }

    // 【2026-10-08 合并】原「输出风格」下拉已撤 —— 它与「回复偏好」是同一件事
    // （都回答「希望 AI 怎么回复我」），重复造轮子。设置页只留回复偏好文本框。
    // config.outputStyle 字段保留（老配置不报错、与旧版互通），但已不再读取 ——
    // assembleSystemPrompt 现在读 profile.personalPreferences（2026-10-09 对齐 CLI）。
    // 下列三项都落 UiPrefs（SharedPreferences）—— 重启保留，
    // 且各有消费端：sendKey → InputBar 的 imeAction；theme → CcmApp 的 darkTheme
    var sendKey by remember { mutableStateOf(com.ccm.app.ui.theme.UiPrefs.sendKey.value) }
    var theme by remember {
        mutableStateOf(
            ThemeMode.entries.firstOrNull {
                it.key == com.ccm.app.ui.theme.UiPrefs.themeMode.value
            } ?: ThemeMode.AUTO
        )
    }
    var chatFont by remember {
        mutableStateOf(
            ChatFont.entries.firstOrNull {
                it.key == com.ccm.app.ui.theme.UiPrefs.chatFont.value
            } ?: ChatFont.DEFAULT
        )
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(SettingsSectionGap)) {

        // ── 1. 个人资料 ──────────────────────────────────────────
        SettingsSection(title = "个人资料") {
            Column(verticalArrangement = Arrangement.spacedBy(SettingsFormGap)) {
                // 全名 + 称呼并排（grid-cols-2；gap-6 移动端实测 10.85）
                Row(horizontalArrangement = Arrangement.spacedBy(10.85.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        SettingsLabel("全名")
                        Spacer(Modifier.height(SettingsLabelGap))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(11.04.dp),
                        ) {
                            // 名字为空时头像显示 U（Web 同款兜底）
                            CcmAvatarSmall(fullName.ifBlank { "U" }.take(1).uppercase())
                            SettingsTextField(
                                value = fullName,
                                onValueChange = {
                                    fullName = it
                                    profileStore?.setField("full_name", it)
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        SettingsLabel("称呼")
                        Spacer(Modifier.height(SettingsLabelGap))
                        SettingsTextField(
                            value = callName,
                            onValueChange = {
                                callName = it
                                profileStore?.setField("display_name", it)
                            },
                        )
                    }
                }

                SettingsField(label = "职业") {
                    SettingsSelectMenu(
                        value = workFunction,
                        options = WORK_FUNCTION_OPTIONS,
                        onPick = {
                            workFunction = it
                            profileStore?.setField("work_function", it)
                        },
                        chevronRotated = true,
                    )
                }

                SettingsField(label = "回复偏好") {
                    Column {
                        SettingsTextField(
                            value = preferences,
                            onValueChange = {
                                preferences = it
                                profileStore?.setField("personal_preferences", it)
                            },
                            singleLine = false,
                            minHeight = 86.6.dp,
                            placeholder = "例如：回答尽量简洁，使用中文，代码注释用英文",
                        )
                        Spacer(Modifier.height(SettingsLabelGap))
                        Text(
                            text = "应用于所有对话。",
                            style = CCMText.body12.copy(fontSize = 10.48.sp, lineHeight = 15.4.sp),
                            color = CCMTheme.colors.textSecondary,
                        )
                    }
                }
            }
        }

        SettingsDivider()

        // ── 2. 默认模型 ──────────────────────────────────────────
        SettingsSection(title = "默认模型") {
            val pstore = com.ccm.app.AppGraph.storage?.let {
                com.ccm.app.core.provider.ProviderStore(it)
            }
            var modelRefresh by remember { mutableStateOf(0) }
            val items = remember(modelRefresh) { pstore?.list() ?: emptyList() }
            val enabledItems = items.filter { it.enabled }
            val current = items.firstOrNull { it.isCurrent }
            var effortOn by remember(modelRefresh) {
                // 开关状态与消费端同源（provider.effort 优先，回退全局）
                val st = com.ccm.app.AppGraph.storage
                val cfg = st?.let { com.ccm.app.core.provider.AppConfig.load(it.configFile).config }
                val provEffort = cfg?.currentProvider?.effort
                val effective = provEffort?.takeIf { it.isNotBlank() } ?: cfg?.effort.orEmpty()
                mutableStateOf(effective.isNotBlank() && effective != "none")
            }

            Column(verticalArrangement = Arrangement.spacedBy(SettingsFormGap)) {
                SettingsField(label = "使用的模型") {
                    SettingsSelectMenu(
                        value = current?.let { "${it.name} · ${it.model}" } ?: "未配置",
                        options = enabledItems.map { "${it.name} · ${it.model}" },
                        onPick = { label ->
                            val target = enabledItems.firstOrNull {
                                "${it.name} · ${it.model}" == label
                            }
                            if (target != null && pstore != null) {
                                pstore.setCurrent(target.id)
                                modelRefresh++
                                // ApiClient 是会话装配期快照 —— 重建才生效
                                // （与对话页模型选择器同一机制）
                                com.ccm.app.AppGraph.openSession(com.ccm.app.AppGraph.sessionId)
                            }
                        },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        SettingsLabel("扩展思考")
                        Spacer(Modifier.height(3.68.dp))
                        Text(
                            // 与对话页的「扩展思考」是同一份配置（都写 provider.effort）
                            text = "开启后模型先深度思考再回答（与对话页联动）。",
                            style = CCMText.body12.copy(fontSize = 10.48.sp, lineHeight = 15.4.sp),
                            color = CCMTheme.colors.textSecondary,
                        )
                    }
                    SettingsSwitch(
                        checked = effortOn,
                        onCheckedChange = { on ->
                            effortOn = on
                            // 写 provider 级（不是全局）—— 与对话页同一个字段
                            val cur = pstore?.load()?.current.orEmpty()
                            if (cur.isNotBlank()) {
                                pstore?.setEffort(cur, if (on) "high" else "none")
                            } else {
                                // 没有当前供应商时退回全局（至少存下来）
                                pstore?.setGlobalEffort(if (on) "high" else "none")
                            }
                            com.ccm.app.AppGraph.openSession(com.ccm.app.AppGraph.sessionId)
                            modelRefresh++
                        },
                    )
                }
            }
        }

        SettingsDivider()

        // ── 3. 自动压缩（2026-10-07 从 /compact-threshold 命令提升为设置项）──
        // 默认关闭（用户被自动压缩搞丢过记忆，明确反感 —— 只有显式设阈值才启用）
        SettingsSection(title = "自动压缩") {
            Column(verticalArrangement = Arrangement.spacedBy(SettingsFormGap)) {
                val acState = com.ccm.app.AppGraph.container?.autoCompact
                val cfgForAc = com.ccm.app.AppGraph.storage?.let { st ->
                    com.ccm.app.core.provider.AppConfig.load(st.configFile).config
                }
                var tokLimit by remember {
                    mutableStateOf(acState?.tokenLimit ?: cfgForAc?.compactTokenLimit ?: 0)
                }
                var msgLimit by remember {
                    mutableStateOf(acState?.messageLimit ?: cfgForAc?.compactMessageLimit ?: 0)
                }
                var acSaved by remember { mutableStateOf("") }

                Text(
                    text = "自动摘要可能丢细节，默认关闭；设了阈值才启用。",
                    style = CCMText.body12.copy(fontSize = 10.48.sp, lineHeight = 15.4.sp),
                    color = CCMTheme.colors.textSecondary,
                )

                SettingsField(label = "Token 阈值（0 = 关闭）") {
                    SettingsTextField(
                        value = if (tokLimit > 0) tokLimit.toString() else "",
                        onValueChange = { v ->
                            tokLimit = v.filter { it.isDigit() }.toIntOrNull() ?: 0
                            acSaved = ""
                        },
                        placeholder = "如 600000（60 万 token）",
                    )
                }

                SettingsField(label = "消息条数阈值（0 = 不按条数）") {
                    SettingsTextField(
                        value = if (msgLimit > 0) msgLimit.toString() else "",
                        onValueChange = { v ->
                            msgLimit = v.filter { it.isDigit() }.toIntOrNull() ?: 0
                            acSaved = ""
                        },
                        placeholder = "如 500（超过 500 条消息时压）",
                    )
                }

                // 运行态提示（断路器/当前水位）
                val statusLine = buildString {
                    val en = acState?.isEnabled ?: (tokLimit > 0 || msgLimit > 0)
                    append("当前：")
                    append(if (en) "已启用" else "关闭")
                    if (acState?.isTripped == true) {
                        append("（⚠ 断路器跳闸，连续失败 ${acState.failures} 次 —— 手动 /compact 可恢复）")
                    }
                    // 当前上下文占用（给用户一个「离阈值多远」的参考）——
                    // 从 session 的 State 读（最近一次请求的 prompt_tokens）
                    com.ccm.app.AppGraph.session?.let { sess ->
                        val used = sess.state.value.inputTokens
                        if (used > 0 && tokLimit > 0) {
                            append("；当前 ${used / 1000}K / 阈值 ${tokLimit / 1000}K")
                        }
                    }
                }
                // 状态行 + 按钮 + 保存结果 成组（组内紧凑，组间由外层 spacedBy 管）
                Column(verticalArrangement = Arrangement.spacedBy(3.68.dp)) {
                    Text(
                        text = statusLine,
                        style = CCMText.body12.copy(fontSize = 10.48.sp, lineHeight = 15.4.sp),
                        color = if (acState?.isTripped == true) Color(0xFFB45309) else CCMTheme.colors.textSecondary,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.TextButton(onClick = {
                            com.ccm.app.AppGraph.storage?.let { st ->
                                val loadR = com.ccm.app.core.provider.AppConfig.load(st.configFile)
                                if (loadR.error != null) {
                                    acSaved = "配置损坏：${loadR.error}"
                                } else {
                                    com.ccm.app.core.provider.AppConfig.save(
                                        loadR.config.copy(
                                            compactTokenLimit = tokLimit,
                                            compactMessageLimit = msgLimit,
                                        ),
                                        st.configFile,
                                    )
                                    // 运行态热更新（与 /compact-threshold 同路径）
                                    com.ccm.app.AppGraph.container?.autoCompact?.let { ac ->
                                        ac.tokenLimit = tokLimit
                                        ac.messageLimit = msgLimit
                                    }
                                    acSaved = if (tokLimit > 0 || msgLimit > 0) {
                                        "✅ 已保存并启用（立即生效）"
                                    } else {
                                        "✅ 已保存（阈值为 0 = 关闭自动压缩）"
                                    }
                                }
                            }
                        }) {
                            Text("保存", style = CCMText.body13)
                        }
                        // 立即压缩一次（等价 /compact force）——
                        // 走 handleSlashCommand（send 是发消息给 agent，不是命令入口）
                        androidx.compose.material3.TextButton(onClick = {
                            val sess = com.ccm.app.AppGraph.session
                            if (sess == null) {
                                acSaved = "无活跃会话"
                            } else {
                                com.ccm.app.ui.chat.handleSlashCommand(
                                    "/compact force",
                                    com.ccm.app.ui.chat.SlashContext(
                                        session = sess,
                                        appContext = ctx,
                                        navigate = {},
                                        newChat = {},
                                        openPanel = {},
                                    ),
                                )
                                acSaved = "已发起手动压缩（结果看对话页）"
                            }
                        }) {
                            Text("立即压缩一次", style = CCMText.body13)
                        }
                    }
                    if (acSaved.isNotEmpty()) {
                        Text(
                            text = acSaved,
                            style = CCMText.body12.copy(fontSize = 10.48.sp),
                            color = CCMTheme.colors.textSecondary,
                        )
                    }
                }
            }
        }

        SettingsDivider()

        // ── 4. 工作区 ────────────────────────────────────────────
        SettingsSection(title = "工作区") {
            // 字段名与 CLI 一致（config.json 的 workspacePath），
            // 但配置文件各自独立（APK 在应用私有目录）。
            Column(verticalArrangement = Arrangement.spacedBy(SettingsFormGap)) {
                // 【2026-10-08 修：变量提到外层 Column】工作区状态变量原来定义在
                // 下面的内层 Column 里，但「保存工作区」TextButton 在外层 ——
                // 作用域隔开，CI 报 8 个 Unresolved reference（wsInput/wsError/wsRefresh）。
                // 重排结构时挪动了 TextButton 的层级，忘了变量也要跟着提。
                //
                // 输入框显示实际生效路径；currentWorkspace() 走磁盘 IO，
                // 用 remember(wsRefresh) 包住（保存后 +1 触发刷新）
                var wsRefresh by remember { mutableStateOf(0) }
                val actualWs = remember(wsRefresh) { currentWorkspace() }
                var wsInput by remember(actualWs) { mutableStateOf(actualWs) }
                var wsError by remember { mutableStateOf("") }
                Column {
                    Text(
                        // 空 = 真的没有工作区（相对路径会报错）；APK 的 config.json
                        // 在应用私有目录，与 CLI/Web 完全隔离
                        text = "工具读写文件的根目录，留空 = 不设（需用绝对路径）。" +
                            "改完对新会话生效，与 CLI / Web 配置独立。",
                        style = CCMText.body12.copy(fontSize = 10.48.sp, lineHeight = 15.4.sp),
                        color = CCMTheme.colors.textSecondary,
                    )
                    Spacer(Modifier.height(7.36.dp))
                    SettingsTextField(
                        value = wsInput,
                        onValueChange = { wsInput = it; wsError = "" },
                        placeholder = "绝对路径，如 /sdcard/Download/xxx",
                    )
                    Spacer(Modifier.height(3.68.dp))
                    Text(
                        text = if (actualWs.isNotEmpty()) "当前生效：$actualWs" else "当前：未设置工作区",
                        style = CCMText.body12.copy(fontSize = 10.48.sp, lineHeight = 15.4.sp),
                        color = CCMTheme.colors.textSecondary,
                    )
                    if (wsError.isNotEmpty()) {
                        Spacer(Modifier.height(3.68.dp))
                        Text(
                            text = wsError,
                            style = CCMText.body12.copy(fontSize = 10.48.sp),
                            color = Color(0xFFB91C1C),
                        )
                    }
                }
                androidx.compose.material3.TextButton(onClick = {
                    val v = wsInput.trim()
                    when {
                        v.isEmpty() -> {
                            // 空 = 恢复默认
                            com.ccm.app.AppGraph.storage?.let { st ->
                                val loadR = com.ccm.app.core.provider.AppConfig.load(st.configFile)
                                if (loadR.error != null) {
                                    wsError = "配置损坏：${loadR.error}"
                                } else {
                                    com.ccm.app.core.provider.AppConfig.save(
                                        loadR.config.copy(workspacePath = null), st.configFile,
                                    )
                                    wsInput = ""
                                    android.widget.Toast.makeText(
                                        ctx,
                                        "已恢复默认工作区（重启或新会话生效）",
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        }
                        !v.startsWith("/") -> wsError = "必须是绝对路径（以 / 开头）"
                        else -> {
                            com.ccm.app.AppGraph.storage?.let { st ->
                                val loadR = com.ccm.app.core.provider.AppConfig.load(st.configFile)
                                if (loadR.error != null) {
                                    wsError = "配置损坏：${loadR.error}"
                                } else {
                                    // 校验目录可建（不可建则拒绝，不写坏配置）
                                    val dir = java.io.File(v)
                                    if (!dir.isDirectory && !dir.mkdirs()) {
                                        wsError = "目录不存在且无法创建（检查权限）"
                                    } else {
                                        // 填的就是默认路径 → 存 null，保持「未配置」语义
                                        val defaultWs = java.io.File(st.root, "workspace").absolutePath
                                        val toSave = if (v == defaultWs) null else v
                                        com.ccm.app.core.provider.AppConfig.save(
                                            loadR.config.copy(workspacePath = toSave), st.configFile,
                                        )
                                        wsRefresh++   // 触发 actualWs 重读
                                        android.widget.Toast.makeText(
                                            ctx,
                                            "已保存（新会话生效）",
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            }
                        }
                    }
                }) {
                    Text("保存工作区", style = CCMText.body13)
                }
                // 「所有文件访问」入口：未授权时填 /sdcard 路径会读写失败
                val wsCtx = androidx.compose.ui.platform.LocalContext.current
                var hasAllFiles by remember {
                    mutableStateOf(
                        if (android.os.Build.VERSION.SDK_INT >= 30)
                            android.os.Environment.isExternalStorageManager()
                        else true
                    )
                }
                // 未授权时每秒轮询一次（授权后停）—— 从系统设置返回后警告条最多 1 秒消失
                if (!hasAllFiles && android.os.Build.VERSION.SDK_INT >= 30) {
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        while (true) {
                            kotlinx.coroutines.delay(1000)
                            if (android.os.Environment.isExternalStorageManager()) {
                                hasAllFiles = true
                                break
                            }
                        }
                    }
                }
                if (!hasAllFiles && android.os.Build.VERSION.SDK_INT >= 30) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.36.dp))
                            .background(Color(0x1AD97757))
                            .clickable {
                                try {
                                    wsCtx.startActivity(
                                        android.content.Intent(
                                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                            android.net.Uri.parse("package:${wsCtx.packageName}"),
                                        )
                                    )
                                } catch (_: Throwable) {
                                    try {
                                        wsCtx.startActivity(
                                            android.content.Intent(
                                                android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
                                            )
                                        )
                                    } catch (_: Throwable) {}
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "⚠ 未授权「所有文件访问」—— 填 /sdcard 路径会读写失败",
                            style = CCMText.body12.copy(fontSize = 10.48.sp),
                            color = Color(0xFFD97757),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "去授权",
                            style = CCMText.body12.copy(fontSize = 10.48.sp, fontWeight = FontWeight.Medium),
                            color = Color(0xFFD97757),
                        )
                    }
                }
            }
        }

        SettingsDivider()

        // ── 5. 对话 ──────────────────────────────────────────────
        SettingsSection(title = "对话") {
            SettingsField(label = "发送键") {
                SettingsSelectMenu(
                    value = sendKey,
                    options = SEND_KEY_OPTIONS,
                    onPick = {
                        sendKey = it
                        com.ccm.app.ui.theme.UiPrefs.setSendKey(it)
                    },
                )
            }
            // 原「换行」下拉已删：newlineKey 全项目零消费（InputBar 只读
            // sendByEnter，Android 换行由输入法自身决定）—— 留着是假设置。
        }

        SettingsDivider()

        // ── 5.5 Clawd 悬浮窗（2026-10-09）──────────────────────────
        // 用户需求：「当 ccm 退到后台的时候，会有一个 Clawd（欢迎页那个吉祥物），
        // 有各种动画，按 spinner 来（思考/打字/说话气泡）」。
        SettingsSection(title = "后台悬浮窗") {
            val overlayCtx = androidx.compose.ui.platform.LocalContext.current
            // 权限状态：从系统设置返回后要刷新（与上面「所有文件访问」同款轮询）
            var canOverlay by remember {
                mutableStateOf(com.ccm.app.service.ClawdOverlayService.canDrawOverlays(overlayCtx))
            }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                while (true) {
                    kotlinx.coroutines.delay(1000)
                    val now = com.ccm.app.service.ClawdOverlayService.canDrawOverlays(overlayCtx)
                    if (now != canOverlay) canOverlay = now
                }
            }
            if (!canOverlay) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.36.dp))
                        .background(Color(0x1AD97757))
                        .clickable {
                            try {
                                overlayCtx.startActivity(
                                    android.content.Intent(
                                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        android.net.Uri.parse("package:${overlayCtx.packageName}"),
                                    )
                                )
                            } catch (_: Throwable) {
                                try {
                                    overlayCtx.startActivity(
                                        android.content.Intent(
                                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        )
                                    )
                                } catch (_: Throwable) {}
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "⚠ 未授权「悬浮窗」—— 退到后台看不到 Clawd 动画",
                        style = CCMText.body12.copy(fontSize = 10.48.sp),
                        color = Color(0xFFD97757),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "去授权",
                        style = CCMText.body12.copy(fontSize = 10.48.sp, fontWeight = FontWeight.Medium),
                        color = Color(0xFFD97757),
                    )
                }
            } else {
                Text(
                    "已授权。App 退到后台时会显示 Clawd 吉祥物，动画跟随 Agent 状态" +
                        "（思考 / 打字 / 读文件 / 说话气泡），点击可回到 App，可拖动位置。",
                    style = CCMText.body12.copy(fontSize = 10.48.sp, lineHeight = 15.4.sp),
                    color = CCMTheme.colors.textSecondary,
                )
            }
        }

        SettingsDivider()

        // ── 5.6 手机操作（phone use 模式，2026-10-10 加）──────────
        // 用户需求：「设置页里加个 phone_use 模式选择」。
        // 与 /device mode 命令同一数据源（PhoneMode，存 device.json）。
        SettingsSection(title = "手机操作") {
            val phoneCtx = androidx.compose.ui.platform.LocalContext.current
            var phoneMode by remember {
                mutableStateOf(com.ccm.app.tools.phone.PhoneMode.preference(phoneCtx))
            }
            Column(verticalArrangement = Arrangement.spacedBy(SettingsFormGap)) {
                Text(
                    "Agent 操作手机时用哪块屏。主屏 = 你能看到它在点什么；" +
                        "副屏 = 虚拟屏静默运行，不占你屏幕；选择 = 每次用时弹框问你。",
                    style = CCMText.body12.copy(fontSize = 10.48.sp, lineHeight = 15.4.sp),
                    color = CCMTheme.colors.textSecondary,
                )
                SettingsField(label = "操作模式") {
                    SettingsSelectMenu(
                        value = when (phoneMode) {
                            com.ccm.app.tools.phone.PhoneMode.FOREGROUND -> "主屏（前台，你能看到）"
                            com.ccm.app.tools.phone.PhoneMode.BACKGROUND -> "副屏（后台，静默）"
                            com.ccm.app.tools.phone.PhoneMode.ASK -> "每次询问"
                            else -> "未设置（默认每次询问）"
                        },
                        options = listOf(
                            "主屏（前台，你能看到）",
                            "副屏（后台，静默）",
                            "每次询问",
                        ),
                        onPick = { label ->
                            val v = when (label) {
                                "主屏（前台，你能看到）" -> com.ccm.app.tools.phone.PhoneMode.FOREGROUND
                                "副屏（后台，静默）" -> com.ccm.app.tools.phone.PhoneMode.BACKGROUND
                                else -> com.ccm.app.tools.phone.PhoneMode.ASK
                            }
                            com.ccm.app.tools.phone.PhoneMode.setPreference(phoneCtx, v)
                            phoneMode = v
                        },
                    )
                }
            }
        }

        SettingsDivider()

        // ── 6. 外观 ──────────────────────────────────────────────
        SettingsSection(title = "外观") {
            Column(verticalArrangement = Arrangement.spacedBy(SettingsFormGap)) {
                // 颜色模式
                Column {
                    SettingsLabel("颜色模式")
                    Spacer(Modifier.height(7.36.dp))     // mb-2
                    Row(horizontalArrangement = Arrangement.spacedBy(11.04.dp)) {
                        ThemeMode.entries.forEach { m ->
                            ThemePreviewCard(
                                mode = m,
                                selected = theme == m,
                                onClick = {
                                    theme = m
                                    com.ccm.app.ui.theme.UiPrefs.setThemeMode(m.key)
                                },
                            )
                        }
                    }
                }

                // 聊天字体
                Column {
                    SettingsLabel("聊天字体")
                    Spacer(Modifier.height(7.36.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(11.04.dp)) {
                        ChatFont.entries.forEach { f ->
                            ChatFontCard(
                                font = f,
                                selected = chatFont == f,
                                // weight(1f) 复刻 Web 的 flex-shrink —— 4 张卡平分宽度
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    chatFont = f
                                    com.ccm.app.ui.theme.UiPrefs.setChatFont(f.key)
                                },
                            )
                        }
                    }
                    // 字体选择已落盘，但正文消费端尚未接（待对齐 Web 的字体映射）
                    Spacer(Modifier.height(5.52.dp))
                    Text(
                        text = "选择暂未应用到聊天正文。",
                        style = CCMText.body12.copy(fontSize = 10.48.sp, lineHeight = 15.4.sp),
                        color = CCMTheme.colors.textSecondary,
                    )
                }
            }
        }

        SettingsDivider()

        // ── 7. 关于 ──────────────────────────────────────────────
        // 这节的 `<h3>` 用 `mb-3`（不是 mb-5）—— 实测 marginBottom 12 → 屏幕 11.04
        SettingsSection(title = "关于", titleGap = SettingsTitleGapTight) {
            // 版本号走 PackageManager 真值（APK 是 0.1.x，不是 CLI 的版本号）
            SettingsInfoRow(label = "当前版本", value = appVersion())
        }
    }
}

/** 全名左侧的小头像 —— `w-10 h-10 rounded-full bg-claude-avatar text-[16px]`。
 *  实测（2026-10-01）：36.8×36.8 · bg `rgb(51,51,51)` · 文字 12.29 / fw500 / lh18.44。
 *  字号按 `text-[16px]` → clamp(12.5,3.4vw,15.5)=13.362 → 屏幕 **12.29**
 *  （原先写 14.72 是照搬 input 的 16px 下限规则，头像不是 input，不适用）。
 */
@Composable
private fun CcmAvatarSmall(initial: String) {
    val colors = CCMTheme.colors
    Box(
        modifier = Modifier
            .size(36.8.dp)
            .clip(CircleShape)
            .background(colors.avatarBg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = CCMText.body16.copy(
                fontSize = 12.29.sp,
                lineHeight = 18.44.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = colors.avatarText,
        )
    }
}

/** 颜色模式 —— 对应源码 `(['light','auto','dark'] as const)` */
enum class ThemeMode(val key: String, val label: String) {
    LIGHT("light", "Light"),
    AUTO("auto", "Auto"),
    DARK("dark", "Dark"),
}

/** 聊天字体 —— 对应源码 4 个选项 */
enum class ChatFont(val key: String, val label: String) {
    DEFAULT("default", "默认"),
    SANS("sans", "Sans"),
    SYSTEM("system", "系统"),
    DYSLEXIC("dyslexic", "阅读障碍"),
}

/**
 * 主题预览卡 —— 对应源码
 * `<div className="w-32 h-20 rounded-lg border flex flex-col shadow-sm
 *   ${selected ? 'border-[#3b82f6]/80 scale-[1.02]' : 'border-claude-border'}">`
 *
 * 实测：`w-32 h-20` = 128×80 → 屏幕 **117.76 × 73.6**（源码未提供实测，
 * 按 Tailwind × 0.92 换算）。选中时 `scale-[1.02]` 放大 2%。
 *
 * 卡片内部是三段迷你界面缩略图（源码里逐元素硬编码），三种模式各不相同：
 * - light：浅底 #F5F4F1 + 灰条 #E3E3E0 + 白输入框 + 橙点 #D97757
 * - dark：深底 #1F1F1E + 灰条 #404040 + 深输入框 #30302E + 橙点
 * - auto：左半深 #555 / 右半浅，白输入框横跨
 */
@Composable
private fun ThemePreviewCard(
    mode: ThemeMode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = CCMTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(11.04.dp),   // gap-3
    ) {
        Box(
            modifier = Modifier
                .size(width = 117.76.dp, height = 73.6.dp)
                .scale(if (selected) 1.02f else 1f)
                .clip(RoundedCornerShape(7.36.dp))              // rounded-lg
                .background(
                    when (mode) {
                        ThemeMode.LIGHT -> Color(0xFFF5F4F1)
                        ThemeMode.DARK -> Color(0xFF1F1F1E)
                        ThemeMode.AUTO -> Color(0xFFF5F4F1)
                    },
                )
                .border(
                    // Web 选中态只改**颜色**（`border-[#3b82f6]/80`）+ `scale-[1.02]`，
                    // 边框宽度恒为 1px（实测三张卡 borderWidth 全是 1.08696 → 屏幕 1.0）。
                    // 原来写 1.38 是错的：会让选中卡比邻居胖一圈。
                    width = 1.dp,
                    color = if (selected) Color(0xCC3B82F6) else colors.border,
                    shape = RoundedCornerShape(7.36.dp),
                )
                .clickable(onClick = onClick),
        ) {
            when (mode) {
                ThemeMode.LIGHT -> ThemePreviewContent(
                    pageBg = Color(0xFFF5F4F1),
                    barColor = Color(0xFFE3E3E0),
                    inputBg = Color.White,
                    inputBorder = Color(0xFFE3E3E0),
                )
                ThemeMode.DARK -> ThemePreviewContent(
                    pageBg = Color(0xFF1F1F1E),
                    barColor = Color(0xFF404040),
                    inputBg = Color(0xFF30302E),
                    inputBorder = Color(0xFF323130),
                )
                ThemeMode.AUTO -> AutoThemePreviewContent()
            }
        }
        Text(
            text = mode.label,
            // `text-[15px]` → 移动端 clamp(12px,3.25vw,15px) → 12.7725 → 屏幕 **11.75**。
            // 2026-10-01 实测主题卡 label fontSize 12.7725 确认（原先写 11.96 偏小）。
            style = CCMText.body15.copy(
                fontSize = 11.75.sp,
                lineHeight = 17.63.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            ),
            color = if (selected) colors.textMain else colors.textSecondary,
        )
    }
}

/**
 * 单色主题缩略图内容 —— 对应源码里的 4 层结构：
 * 右上一条 `w-10 h-2.5` 圆条 → 两条 `w-12/w-16 h-1` 细条 → 底部 `h-6` 输入框 + 橙点。
 *
 * 实测尺寸按 Tailwind × 0.92 换算：
 * - 内边距 p-2 = 7.36
 * - 右上条 40×10 → 36.8×9.2，圆角 full
 * - 细条 48×4 → 44.16×3.68 / 64×4 → 58.88×3.68
 * - 底部框 h-6 = 22.08，圆角 3.68，边框 0.92
 * - 橙点 w-2 h-2 = 7.36
 */
@Composable
private fun ThemePreviewContent(
    pageBg: Color,
    barColor: Color,
    inputBg: Color,
    inputBorder: Color,
) {
    Column(modifier = Modifier.padding(7.36.dp)) {
        // 右上角圆条（flex justify-end）
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                modifier = Modifier
                    .size(width = 36.8.dp, height = 9.2.dp)
                    .clip(CircleShape)
                    .background(barColor),
            )
        }
        Spacer(Modifier.height(3.68.dp))
        // 两条细条
        Box(
            modifier = Modifier
                .size(width = 44.16.dp, height = 3.68.dp)
                .clip(CircleShape)
                .background(barColor),
        )
        Spacer(Modifier.height(3.68.dp))
        Box(
            modifier = Modifier
                .size(width = 58.88.dp, height = 3.68.dp)
                .clip(CircleShape)
                .background(barColor),
        )
        Spacer(Modifier.weight(1f))
        // 底部输入框 + 橙点
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.08.dp)
                .clip(RoundedCornerShape(3.68.dp))
                .background(inputBg)
                .border(0.92.dp, inputBorder, RoundedCornerShape(3.68.dp))
                .padding(horizontal = 3.68.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            Box(
                modifier = Modifier
                    .size(7.36.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFD97757)),
            )
        }
    }
}

/**
 * Auto 主题缩略图 —— 左右半分屏：左深右浅，白输入框横跨中线。
 * 对应源码：
 * `<div className="w-1/2 bg-[#555] p-2 border-r border-white/10">…</div>` +
 * `<div className="mt-auto bg-white/90 rounded h-6 w-[140%] -ml-1 z-10">`
 */
@Composable
private fun AutoThemePreviewContent() {
    Box(modifier = Modifier.fillMaxWidth().height(73.6.dp)) {
        Row(modifier = Modifier.fillMaxWidth().height(73.6.dp)) {
            // 左半：深色
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(73.6.dp)
                    .background(Color(0xFF555555))
                    .padding(7.36.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 29.44.dp, height = 3.68.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.2f)),
                )
                Spacer(Modifier.height(3.68.dp))
                Box(
                    modifier = Modifier
                        .size(width = 36.8.dp, height = 3.68.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.2f)),
                )
            }
            // 右半：浅色（源码只给了左半内容，右侧留白）
            Box(modifier = Modifier.weight(1f).height(73.6.dp).background(Color(0xFFF5F4F1)))
        }
        // 横跨的白输入框
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 3.68.dp, bottom = 7.36.dp)
                .fillMaxWidth(0.62f)
                .height(22.08.dp)
                .clip(RoundedCornerShape(3.68.dp))
                .background(Color.White.copy(alpha = 0.9f)),
        )
    }
}

/**
 * 字体卡 —— 对应源码
 * `<button className="w-32 flex flex-col items-center gap-2 py-3 px-2 rounded-lg border">
 *    <span text-[20px] leading-none mb-1>Aa</span><span text-[13px]>label</span>
 *  </button>`
 *
 * ⚠️ **宽度不是固定的 117.76**：`w-32`(=128) 是 flex item 的 width，
 * 4 张卡 + 3×gap(11.04) 在 358.44 的内容区里放不下，浏览器按 `flex-shrink:1`
 * 压到 **81.33**（实测 2026-10-01：fontCards w = 82.97 / 81.33 / 81.34 / 81.33）。
 * 所以这里**不能**写死宽度，必须交给父 Row 的 `weight(1f)` 分配
 * —— 否则 4×117.76 会溢出 145dp，第 4 张卡被挤出屏幕（这是「设置页比 Web 乱」的来源之一）。
 *
 * 内部实测：`py-3`=11.04 · `gap-2`=7.36 ·
 * 样例字 `text-[20px]` → clamp(14,3.7vw,19)=14.541 → **13.38**，行高 20.07，`mb-1`=3.68 ·
 * 标签 `text-[13px]` → **11.21**（选中态 fw500）。
 */
@Composable
private fun ChatFontCard(
    font: ChatFont,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CCMTheme.colors
    Column(
        modifier = modifier
            .scale(if (selected) 1.02f else 1f)             // 源码选中态 `scale-[1.02]`
            .clip(RoundedCornerShape(7.36.dp))
            .background(colors.input)
            .border(
                // 同上：宽度恒 1px，选中只换色（rgba(59,130,246,0.8)）
                width = 1.dp,
                color = if (selected) Color(0xCC3B82F6) else colors.border,
                shape = RoundedCornerShape(7.36.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 11.04.dp, horizontal = 7.36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.36.dp),
    ) {
        Text(
            text = "Aa",
            style = CCMText.body20.copy(
                fontSize = 13.38.sp,
                lineHeight = 20.07.sp,
                fontFamily = when (font) {
                    ChatFont.DEFAULT -> com.ccm.app.ui.theme.CcmSerif
                    ChatFont.SANS -> com.ccm.app.ui.theme.CcmSans
                    ChatFont.SYSTEM -> com.ccm.app.ui.theme.CcmSans
                    ChatFont.DYSLEXIC -> com.ccm.app.ui.theme.CcmSerif
                },
            ),
            color = colors.textMain,
        )
        Text(
            text = font.label,
            style = CCMText.body13.copy(
                fontSize = 11.21.sp,
                lineHeight = 18.27.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            ),
            color = if (selected) colors.textMain else colors.textSecondary,
        )
    }
}

/**
 * 大号「思考」指示灯（备用，用于 Provider 页的 tier 选择）。
 * 源码里是一段 SVG 圆弧，这里用 Canvas 画 3/4 圆环。
 */
@Composable
fun ThinkingRing(color: Color, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 14.dp) {
    Canvas(modifier = modifier.size(size)) {
        val stroke = 1.84.dp.toPx()
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 270f,
            useCenter = false,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = stroke,
                cap = StrokeCap.Round,
            ),
            topLeft = Offset(stroke / 2, stroke / 2),
            size = androidx.compose.ui.geometry.Size(
                this.size.width - stroke,
                this.size.height - stroke,
            ),
        )
    }
}

/** 当前 APK 版本号（versionName，如 "0.1.199"）。Composable 内读一次 context。 */
@Composable
private fun appVersion(): String {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return remember(ctx) {
        try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "unknown"
        } catch (_: Throwable) {
            "unknown"
        }
    }
}


/** 读当前工作区路径（空 = 默认私有目录）。 */
/**
 * 当前工作区 —— 返回**实际生效**的路径。
 *
 * 【2026-10-06 问题18 修复】原来返回 config.workspacePath 原始值
 * （没配置时 = 空串），但 Agent 实际用的是 resolveWorkspaceDir()
 * 解析后的路径（空配置时落回 files/workspace）。
 * 用户报「Agent 认为的工作区与用户设置的不同」—— 就是这个：
 * 输入框空着，实际却在 files/workspace 里读写文件。
 *
 * 现在：有配置返回配置值；没配置返回实际默认路径，并在 UI 标注。
 */
private fun currentWorkspace(): String =
    try {
        com.ccm.app.AppGraph.workspacePath()
    } catch (_: Throwable) { "" }
