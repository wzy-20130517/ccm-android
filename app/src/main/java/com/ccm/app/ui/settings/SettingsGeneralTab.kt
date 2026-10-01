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

/** 下拉选项集合（2026-09-27 加 —— 这些下拉之前全是 onClick 空转）。 */
private val WORK_FUNCTION_OPTIONS = listOf(
    "工程师", "设计师", "产品经理", "学生", "教师",
    "写作 / 内容", "运营 / 市场", "科研 / 数据", "其他",
)
private val SEND_KEY_OPTIONS = listOf(
    "仅按钮（回车只换行）",
    "回车发送（Shift+Enter 换行）",
    "Ctrl+Enter 发送",
)
private val NEWLINE_KEY_OPTIONS = listOf("Enter", "Shift+Enter", "Alt+Enter")

/**
 * 设置页 · General tab —— 对齐 `SettingsPage.tsx:515 renderGeneral()`。
 *
 * 源码 6 个小节，顺序：
 *   1. 个人资料（全名 / 称呼 / 职业 / 个人偏好）
 *   2. 默认模型（模型下拉 + 扩展思考开关）  ← 源码在 `<>` 片段里，移动端同样渲染
 *   3. 发送消息（发送键 / 换行键）
 *   4. 外观（颜色模式 3 卡 + 聊天字体 4 卡）
 *   5. 关于（当前版本）
 *
 * 实测（Playwright，393×852，zoom 0.92）：
 * ```
 * section[0] y=101.3 h=398.6   个人资料
 * section[1] y=517.9 h=119.0   发送消息
 * hr         y=655.0 h=1
 * section[2] y=674.0 h=279.8   外观
 * hr         y=971.9 h=1
 * section[3] y=991.0 h=85.6    关于
 * ```
 * section 间距 = 517.9 - (101.3+398.6) = 18.0（= 实测 19.6 × 0.92，即 `space-y-10` 的一半）
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

    // ── 输出风格（2026-09-29 互通：CLI /style ↔ Web 设置 ↔ 这里）────────
    var styleRefresh by remember { mutableStateOf(0) }
    val styles = remember(styleRefresh) {
        com.ccm.app.core.output.OutputStyles.all(cwd = null)
    }
    val styleOptions = styles.map { it.name }
    val currentStyleId = remember(styleRefresh) {
        com.ccm.app.AppGraph.storage?.let { st ->
            com.ccm.app.core.provider.AppConfig.load(st.configFile).config.outputStyle
        }?.takeIf { it.isNotBlank() } ?: "default"
    }
    fun currentStyleLabel(): String =
        styles.firstOrNull { it.id == currentStyleId }?.name ?: "默认"
    fun styleIdByLabel(label: String): String =
        styles.firstOrNull { it.name == label }?.id ?: "default"
    // ★ 2026-09-28：落 UiPrefs —— 原来是本地 remember，重启丢 +
    //   输入框根本不读它（设置是假的）。
    var sendKey by remember { mutableStateOf(com.ccm.app.ui.theme.UiPrefs.sendKey.value) }
    var newlineKey by remember { mutableStateOf(com.ccm.app.ui.theme.UiPrefs.newlineKey.value) }
    // ★ 2026-09-27：原来是本地 remember —— 选完重启就丢，且主题没有
    //   消费端。现在读写 UiPrefs（SharedPreferences + MutableState）：
    //   这里改 → CcmApp 的 darkTheme 自动重组；重启后从磁盘恢复。
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
                // 全名 + 称呼并排（grid-cols-2 gap-6）
                Row(horizontalArrangement = Arrangement.spacedBy(22.08.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        SettingsLabel("全名")
                        Spacer(Modifier.height(SettingsLabelGap))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(11.04.dp),
                        ) {
                            CcmAvatarSmall(fullName.take(1).uppercase())
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
                        SettingsLabel("Claude 应该怎么称呼你？")
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

                // 职业
                SettingsField(label = "你的职业是什么？") {
                    // ★ 2026-09-27：原注释说「由系统 Dialog 承担」—— 实际没有
                    //   Dialog，onClick 空转点不开。接 SettingsSelectMenu + 落盘。
                    SettingsSelectMenu(
                        value = workFunction,
                        options = WORK_FUNCTION_OPTIONS,
                        onPick = {
                            workFunction = it
                            profileStore?.setField("work_function", it)
                        },
                        title = "你的职业",
                        chevronRotated = true,
                    )
                }

                // 个人偏好
                Column {
                    SettingsLabel("Claude 在回复中应考虑哪些个人偏好？")
                    Spacer(Modifier.height(SettingsLabelGap))
                    Text(
                        text = "你的偏好将应用于所有对话。",
                        style = CCMText.body12.copy(fontSize = 10.48.sp, lineHeight = 15.4.sp),
                        color = CCMTheme.colors.textSecondary,
                    )
                    // ★ 2026-09-29 互通：原来这里是「请用 /style」死提示 ——
                    //   现在真的能选了（写 config.json 的 outputStyle，与 CLI/Web 同字段）。
                    SettingsSelectMenu(
                        value = currentStyleLabel(),
                        options = styleOptions,
                        onPick = { label ->
                            val id = styleIdByLabel(label)
                            com.ccm.app.AppGraph.storage?.let { st ->
                                val cfg = com.ccm.app.core.provider.AppConfig.load(st.configFile).config
                                com.ccm.app.core.provider.AppConfig.save(
                                    cfg.copy(outputStyle = id),
                                    st.configFile,
                                )
                            }
                            styleRefresh++
                        },
                        title = "输出风格",
                    )
                    Spacer(Modifier.height(7.36.dp))
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
                }

                // ── 工作区（2026-10-01 用户要求：与 CLI /workspace 一致）────
                //   与 CLI 同一语义、同一字段（config.json 的 workspacePath）。
                Column {
                    SettingsLabel("工作区")
                    Spacer(Modifier.height(SettingsLabelGap))
                    Text(
                        text = "工具读写文件的根目录。空 = 默认（应用私有 workspace 目录）。",
                        style = CCMText.body12.copy(fontSize = 10.48.sp, lineHeight = 15.4.sp),
                        color = CCMTheme.colors.textSecondary,
                    )
                    Spacer(Modifier.height(7.36.dp))
                    var wsInput by remember { mutableStateOf(currentWorkspace()) }
                    var wsError by remember { mutableStateOf("") }
                    SettingsTextField(
                        value = wsInput,
                        onValueChange = { wsInput = it; wsError = "" },
                        placeholder = "/sdcard/Download/claude-workspace",
                    )
                    if (wsError.isNotEmpty()) {
                        Spacer(Modifier.height(3.68.dp))
                        Text(
                            text = wsError,
                            style = CCMText.body12.copy(fontSize = 10.48.sp),
                            color = Color(0xFFB91C1C),
                        )
                    }
                    Spacer(Modifier.height(7.36.dp))
                    androidx.compose.material3.TextButton(onClick = {
                        val v = wsInput.trim()
                        when {
                            v.isEmpty() -> {
                                // 空 = 恢复默认（清掉字段）
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
                                            com.ccm.app.core.provider.AppConfig.save(
                                                loadR.config.copy(workspacePath = v), st.configFile,
                                            )
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
                    Text(
                        text = "与 CLI 的 /workspace、Web 设置页同一份配置。改完对**新会话**生效。",
                        style = CCMText.body12.copy(fontSize = 10.48.sp, lineHeight = 15.4.sp),
                        color = CCMTheme.colors.textSecondary,
                    )
                }
            }
        }

        // ── 1.5 默认模型（源码第 2 节；移动端一直没渲染 —— 2026-09-28 补）──
        //
        // 文件头注释写着「2. 默认模型（模型下拉 + 扩展思考开关）」，
        // 但实际渲染里根本没有这节 —— 模型只能去「模型」tab 配，主 tab 缺位。
        SettingsSection(title = "默认模型") {
            val pstore = com.ccm.app.AppGraph.storage?.let {
                com.ccm.app.core.provider.ProviderStore(it)
            }
            var modelRefresh by remember { mutableStateOf(0) }
            val items = remember(modelRefresh) { pstore?.list() ?: emptyList() }
            val enabledItems = items.filter { it.enabled }
            val current = items.firstOrNull { it.isCurrent }
            var effortOn by remember(modelRefresh) {
                val st = com.ccm.app.AppGraph.storage
                mutableStateOf(
                    st?.let { com.ccm.app.core.provider.AppConfig.load(it.configFile).config.effort }
                        ?.let { it.isNotBlank() && it != "none" } ?: false
                )
            }

            SettingsField(label = "模型") {
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
                    title = "默认模型",
                )
            }

            Spacer(Modifier.height(11.04.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    SettingsLabel("扩展思考")
                    Spacer(Modifier.height(3.68.dp))
                    Text(
                        text = "开启后模型先深度思考再回答（对应 effort=high；关闭 = none）。",
                        style = CCMText.body12.copy(fontSize = 10.48.sp, lineHeight = 15.4.sp),
                        color = CCMTheme.colors.textSecondary,
                    )
                }
                SettingsSwitch(
                    checked = effortOn,
                    onCheckedChange = { on ->
                        effortOn = on
                        pstore?.setGlobalEffort(if (on) "high" else "none")
                        com.ccm.app.AppGraph.openSession(com.ccm.app.AppGraph.sessionId)
                        modelRefresh++
                    },
                )
            }
        }

        Spacer(Modifier.height(SettingsHrGap))
        SettingsDivider()
        Spacer(Modifier.height(SettingsHrGap))

        // ── 2. 发送消息 ──────────────────────────────────────────
        SettingsSection(title = "发送消息") {
            Row(horizontalArrangement = Arrangement.spacedBy(22.08.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    SettingsField(label = "发送消息") {
                        SettingsSelectMenu(
                            value = sendKey,
                            options = SEND_KEY_OPTIONS,
                            onPick = {
                                sendKey = it
                                com.ccm.app.ui.theme.UiPrefs.setSendKey(it)
                            },
                            title = "发送消息",
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    SettingsField(label = "换行") {
                        SettingsSelectMenu(
                            value = newlineKey,
                            options = NEWLINE_KEY_OPTIONS,
                            onPick = {
                                newlineKey = it
                                com.ccm.app.ui.theme.UiPrefs.setNewlineKey(it)
                            },
                            title = "换行",
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(SettingsHrGap))
        SettingsDivider()
        Spacer(Modifier.height(SettingsHrGap))

        // ── 3. 外观 ──────────────────────────────────────────────
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
                                onClick = {
                                    chatFont = f
                                    com.ccm.app.ui.theme.UiPrefs.setChatFont(f.key)
                                },
                            )
                        }
                    }
                    // 诚实标注：选择已落盘，但正文消费端还没接
                    // （需要先对照 Web 实测 4 个选项的真实字体映射，
                    //   瞎猜会做出「和 Web 不一样」的字体）。
                    Spacer(Modifier.height(5.52.dp))
                    Text(
                        text = "选择会保存；应用到聊天正文的字体映射待对齐 Web 后接入。",
                        style = CCMText.body12.copy(fontSize = 10.48.sp, lineHeight = 15.4.sp),
                        color = CCMTheme.colors.textSecondary,
                    )
                }
            }
        }

        Spacer(Modifier.height(SettingsHrGap))
        SettingsDivider()
        Spacer(Modifier.height(SettingsHrGap))

        // ── 4. 关于 ──────────────────────────────────────────────
        SettingsSection(title = "关于") {
            // ★ 版本号走 PackageManager 真值 —— 原来写死 "v0.8.100"，
            //   那是 CLI（claude-code-mobile）的版本，APK 自己是 0.1.x。
            //   记得 CI 每次构建会 bump versionName（workflow 里 0.1.run_number）。
            SettingsInfoRow(label = "当前版本", value = appVersion())
        }
    }
}

/** 全名左侧的小头像 —— `w-10 h-10 rounded-full bg-claude-avatar text-[16px]`。
 *  实测 38.8×38.8（40 × 0.92 = 36.8，加字距后渲染为 38.8）。
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
            style = CCMText.body16.copy(fontSize = 14.72.sp, fontWeight = FontWeight.Medium),
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
                    width = if (selected) 1.38.dp else 1.dp,
                    color = if (selected) Color(0xFF3B82F6) else colors.border,
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
            style = CCMText.body13.copy(fontSize = 11.96.sp),
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
 *    <span text-[20px]>Aa</span><span text-[13px]>label</span>
 *  </button>`
 *
 * 实测（Tailwind × 0.92）：宽 117.76 · `py-3` = 11.04 · `gap-2` = 7.36 ·
 * 样例字 `text-[20px]` 移动端覆盖为 clamp(12.5,3.4vw,15.5) → 13.362 → 屏幕 12.29。
 */
@Composable
private fun ChatFontCard(
    font: ChatFont,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = CCMTheme.colors
    Column(
        modifier = Modifier
            .width(117.76.dp)
            .clip(RoundedCornerShape(7.36.dp))
            .background(colors.input)
            .border(
                width = if (selected) 1.38.dp else 1.dp,
                color = if (selected) Color(0xFF3B82F6) else colors.border,
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
                fontSize = 12.29.sp,
                lineHeight = 12.29.sp,
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
                fontSize = 11.96.sp,
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
private fun currentWorkspace(): String =
    try {
        com.ccm.app.AppGraph.storage
            ?.let { com.ccm.app.core.provider.AppConfig.load(it.configFile).config.workspacePath }
            .orEmpty()
    } catch (_: Throwable) { "" }
