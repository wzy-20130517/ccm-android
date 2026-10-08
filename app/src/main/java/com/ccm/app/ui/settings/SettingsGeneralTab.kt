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
                // ⚠️ gap-6 在移动端被 `.gap-6 { gap: clamp(8px,3vw,15px) }` 覆盖
                //    （index.css:1210）→ 393px 下 3vw = 11.79 → 屏幕 **10.85**。
                //    2026-10-01 实测 grid gap = 10.85 确认（原先误用桌面值 22.08）。
                Row(horizontalArrangement = Arrangement.spacedBy(10.85.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        SettingsLabel("全名")
                        Spacer(Modifier.height(SettingsLabelGap))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(11.04.dp),
                        ) {
                            // 【2026-10-06 问题5 修复】原来 fullName 空 → 传空串 →
                            // 头像是个空白圆（用户报「无默认头像」）。
                            // Web 有兜底：`(fullName || nickname || 'U').charAt(0)`
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
                            // 【2026-10-06 P1-4】换风格 → 提示词缓存失效
                            com.ccm.app.core.AppContainer.invalidateSystemPrompt()
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
                // 【2026-10-06 修】开关状态要与**消费端同源**（provider.effort
                // 优先，回退全局）—— 原来只读全局字段，于是「对话页开过
                // （写 provider）→ 本页显示关」的自相矛盾。
                val st = com.ccm.app.AppGraph.storage
                val cfg = st?.let { com.ccm.app.core.provider.AppConfig.load(it.configFile).config }
                val provEffort = cfg?.currentProvider?.effort
                val effective = provEffort?.takeIf { it.isNotBlank() } ?: cfg?.effort.orEmpty()
                mutableStateOf(effective.isNotBlank() && effective != "none")
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
                        // 【2026-10-06 修文案与行为】原来这里写「与对话页…是同一份
                        // 配置，在哪调都一样」—— 但实际写的是**两个字段**：
                        //   对话页 → provider.effort（当前供应商）
                        //   本页   → config.effort（全局）
                        // 而消费端是 `provider.effort ?: config.effort`（provider 优先），
                        // 于是「对话页开过 → 本页关掉」不生效（provider 的值还在）。
                        // 现在本页也写 provider 级（与对话页一致），文案才成立。
                        text = "开启后模型先深度思考再回答（对应 effort=high；关闭 = none）。" +
                            "作用于当前供应商（与对话页的「扩展思考」是同一份配置）。",
                        style = CCMText.body12.copy(fontSize = 10.48.sp, lineHeight = 15.4.sp),
                        color = CCMTheme.colors.textSecondary,
                    )
                }
                SettingsSwitch(
                    checked = effortOn,
                    onCheckedChange = { on ->
                        effortOn = on
                        // 写 provider 级（不是全局）—— 与对话页同一个字段，
                        // 这样「在哪调都一样」才是真的。
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

        Spacer(Modifier.height(SettingsHrGap))
        SettingsDivider()
        Spacer(Modifier.height(SettingsHrGap))

        // ── 工作区（2026-10-06 问题20：从「个人资料」里提出来独立成章）──
        // 对齐 Web 的章节顺序：个人资料 → 默认模型 → **工作区** → 发送消息。
        // 原来嵌在个人资料里，用户报「设置页杂乱不堪」。
        // ── 自动压缩（2026-10-07 从 /compact-threshold 命令提升为设置项）──
        //
        // 原来只有命令入口，用户在设置页找不到。默认关闭（用户被自动压缩
        // 搞丢过记忆，明确反感 —— 只有显式设阈值才启用），所以 UI 上
        // 要说明白「为什么默认关」。
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
                    text = "对话接近上限时自动摘要历史（可能丢细节，默认关闭）。" +
                        "设了阈值才启用；压缩前自动备份到压缩回收站。",
                    style = CCMText.body12.copy(fontSize = 10.48.sp, lineHeight = 15.4.sp),
                    color = CCMTheme.colors.textSecondary,
                )

                // Token 阈值
                SettingsLabel("Token 阈值（0 = 关闭）")
                Spacer(Modifier.height(SettingsLabelGap))
                SettingsTextField(
                    value = if (tokLimit > 0) tokLimit.toString() else "",
                    onValueChange = { v ->
                        tokLimit = v.filter { it.isDigit() }.toIntOrNull() ?: 0
                        acSaved = ""
                    },
                    placeholder = "如 600000（60 万 token）",
                )

                // 消息条数阈值
                SettingsLabel("消息条数阈值（0 = 不按条数）")
                Spacer(Modifier.height(SettingsLabelGap))
                SettingsTextField(
                    value = if (msgLimit > 0) msgLimit.toString() else "",
                    onValueChange = { v ->
                        msgLimit = v.filter { it.isDigit() }.toIntOrNull() ?: 0
                        acSaved = ""
                    },
                    placeholder = "如 500（超过 500 条消息时压）",
                )

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
                Text(
                    text = statusLine,
                    style = CCMText.body12.copy(fontSize = 10.48.sp, lineHeight = 15.4.sp),
                    color = if (acState?.isTripped == true) Color(0xFFB45309) else CCMTheme.colors.textSecondary,
                )

                Spacer(Modifier.height(3.68.dp))
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

        // ── 对话行为（2026-10-07 新增 —— 三个开关原来只有命令入口）──
        //
        // Prompt Cache / 思考回传 / 历史回放：分别对应 /cache、/effort replay、
        // /replay 三个命令。用户在设置页找不到，收拢到这里。
        SettingsSection(title = "对话行为") {
            Column(verticalArrangement = Arrangement.spacedBy(SettingsFormGap)) {
                val cfgStore = com.ccm.app.AppGraph.storage

                // Prompt Cache
                var cacheOn by remember {
                    mutableStateOf(
                        cfgStore?.let { st ->
                            com.ccm.app.core.provider.AppConfig.load(st.configFile).config.promptCache
                        } ?: false
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Prompt Cache", style = CCMText.body13, color = CCMTheme.colors.textMain)
                        Text(
                            "请求带缓存标记，重复前缀省钱省时。未知网关可能报错，遇到就关掉。",
                            style = CCMText.body12.copy(fontSize = 10.48.sp, lineHeight = 15.4.sp),
                            color = CCMTheme.colors.textSecondary,
                        )
                    }
                    SettingsSwitch(checked = cacheOn, onCheckedChange = { on ->
                        cacheOn = on
                        cfgStore?.let { st ->
                            val r = com.ccm.app.core.provider.AppConfig.load(st.configFile)
                            if (r.error == null) {
                                com.ccm.app.core.provider.AppConfig.save(
                                    r.config.copy(promptCache = on), st.configFile,
                                )
                            }
                        }
                    })
                }

                // 思考回传（provider 级 —— 当前 Provider 的字段；
                // 与 /effort replay 命令同一落点：AppConfig.providers[current]）
                val cfgNow = cfgStore?.let { st ->
                    com.ccm.app.core.provider.AppConfig.load(st.configFile).config
                }
                val curProvider = cfgNow?.providers?.get(cfgNow.current)
                var replayOn by remember {
                    mutableStateOf(curProvider?.replayReasoning == true)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("思考回传", style = CCMText.body13, color = CCMTheme.colors.textMain)
                        Text(
                            "把上轮思考发给模型（模型能看到自己怎么想的）。费 token，默认关。",
                            style = CCMText.body12.copy(fontSize = 10.48.sp, lineHeight = 15.4.sp),
                            color = CCMTheme.colors.textSecondary,
                        )
                    }
                    SettingsSwitch(checked = replayOn, onCheckedChange = { on ->
                        replayOn = on
                        val st = cfgStore ?: return@SettingsSwitch
                        val cfg = com.ccm.app.core.provider.AppConfig.load(st.configFile).config
                        val p = cfg.providers[cfg.current] ?: return@SettingsSwitch
                        com.ccm.app.core.provider.AppConfig.save(
                            cfg.copy(providers = cfg.providers + (p.id to p.copy(replayReasoning = on))),
                            st.configFile,
                        )
                    })
                }

                // 历史回放（进会话时显不显示历史正文）
                var replayHist by remember {
                    mutableStateOf(
                        cfgStore?.let { st ->
                            com.ccm.app.core.provider.AppConfig.load(st.configFile).config.replayHistory
                        } ?: false
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("历史回放", style = CCMText.body13, color = CCMTheme.colors.textMain)
                        Text(
                            "恢复会话时把历史正文铺到屏幕上。默认关（不刷屏），对话内容不受影响。",
                            style = CCMText.body12.copy(fontSize = 10.48.sp, lineHeight = 15.4.sp),
                            color = CCMTheme.colors.textSecondary,
                        )
                    }
                    SettingsSwitch(checked = replayHist, onCheckedChange = { on ->
                        replayHist = on
                        cfgStore?.let { st ->
                            val r = com.ccm.app.core.provider.AppConfig.load(st.configFile)
                            if (r.error == null) {
                                com.ccm.app.core.provider.AppConfig.save(
                                    r.config.copy(replayHistory = on), st.configFile,
                                )
                            }
                        }
                    })
                }
            }
        }

        SettingsSection(title = "工作区") {
            // ── 工作区 ────
            // 字段名与 CLI 一致（config.json 的 workspacePath），
            // 但**配置文件是各自独立的**（APK 在应用私有目录）。
            Column {
                SettingsLabel("工作区")
                Spacer(Modifier.height(SettingsLabelGap))
                Text(
                    // 【2026-10-06 改语义】空 = **没有工作区**（不再是"用默认目录"）——
                    // 相对路径会明确报错，绝对路径不受影响。
                    text = "工具读写文件的根目录。留空 = 不设工作区" +
                        "（工具用相对路径时会报错，需用绝对路径）。目录必须已存在。",
                    style = CCMText.body12.copy(fontSize = 10.48.sp, lineHeight = 15.4.sp),
                    color = CCMTheme.colors.textSecondary,
                )
                Spacer(Modifier.height(7.36.dp))
                // 输入框显示**实际生效路径**（问题18：不再让用户猜）
                // 没有工作区时为空串 —— 输入框留空即「未设置」
                //
                // 【2026-10-06 修】原来 currentWorkspace() 直接写在 composition 里
                // —— 它内部走 AppGraph.workspacePath() → 读 config.json（磁盘 IO），
                // 每次重组都读一次。包 remember，用 wsRefresh 做 key
                // （保存工作区后 +1 触发刷新）。
                var wsRefresh by remember { mutableStateOf(0) }
                val actualWs = remember(wsRefresh) { currentWorkspace() }
                var wsInput by remember(actualWs) { mutableStateOf(actualWs) }
                var wsError by remember { mutableStateOf("") }
                SettingsTextField(
                    value = wsInput,
                    onValueChange = { wsInput = it; wsError = "" },
                    placeholder = "/sdcard/Download/claude-workspace",
                )
                Spacer(Modifier.height(3.68.dp))
                // 显示 Agent 实际在用的目录（问题18：消除「设置与实际不符」）
                if (actualWs.isNotEmpty()) {
                    Text(
                        text = "当前生效：$actualWs",
                        style = CCMText.body12.copy(fontSize = 10.48.sp, lineHeight = 15.4.sp),
                        color = CCMTheme.colors.textSecondary,
                    )
                } else {
                    // 【2026-10-06】空 ≠ 加载失败，明确告知状态
                    Text(
                        text = "当前：未设置工作区",
                        style = CCMText.body12.copy(fontSize = 10.48.sp, lineHeight = 15.4.sp),
                        color = CCMTheme.colors.textSecondary,
                    )
                }
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
                                        // 【2026-10-06 问题18 配套】如果填的就是
                                        // 默认路径（files/workspace），存空串 ——
                                        // 保持「未配置」语义，避免把默认值固化进配置
                                        // （输入框现在显示实际生效路径，用户不动直接
                                        //   点保存时会走到这）。
                                        val defaultWs = java.io.File(st.root, "workspace").absolutePath
                                        val toSave = if (v == defaultWs) null else v
                                        com.ccm.app.core.provider.AppConfig.save(
                                            loadR.config.copy(workspacePath = toSave), st.configFile,
                                        )
                                        wsRefresh++   // 触发 actualWs 重读（见上面的 remember）
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
                // 【2026-10-06 问题4 修复】原文案「与 CLI 的 /workspace、
                // Web 设置页同一份配置」是**错的** —— APK 的 config.json 在
                // 应用私有目录（filesDir/config.json），与 CLI
                // （~/.claude-code-mobile/config.json）完全隔离，互不影响。
                // 误导用户以为改这里会同步到 CLI。
                Text(
                    text = "APK 独立配置（与 CLI / Web 的配置互不影响）。" +
                        "留空 = 用应用私有目录。改完对**新会话**生效。",
                    style = CCMText.body12.copy(fontSize = 10.48.sp, lineHeight = 15.4.sp),
                    color = CCMTheme.colors.textSecondary,
                )
                Spacer(Modifier.height(5.52.dp))
                // 【2026-10-06 问题44 修复】用户设 /sdcard/... 但 App 没权限
                // （实测 `ls /sdcard/...` → Permission denied）→ 只能退回
                // 私有目录 → 表现为「Agent 工作区和设置的不一样」。
                // 这里给「授权所有文件访问」的入口（Android 11+ 需手动授权）。
                val wsCtx = androidx.compose.ui.platform.LocalContext.current
                var hasAllFiles by remember {
                    mutableStateOf(
                        if (android.os.Build.VERSION.SDK_INT >= 30)
                            android.os.Environment.isExternalStorageManager()
                        else true
                    )
                }
                // 【2026-10-06 修】用户去系统设置授权后返回，警告条不消失 ——
                // 因为 hasAllFiles 是无 key 的 remember，Activity 从后台回前台
                // 不会重新求值，用户以为授权没生效。
                //
                // 实现方式：**轻量轮询**（未授权时每秒查一次，授权后停）。
                // 为什么不用 lifecycle-compose 的 LocalLifecycleOwner：
                // 项目没有 lifecycle-runtime-compose 依赖，加依赖会动 build.gradle；
                // 而轮询零依赖、行为等价（用户从系统设置回来最多 1 秒后消失）。
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

        // ── 2. 发送消息 ──────────────────────────────────────────
        SettingsSection(title = "发送消息") {
            // 同上：`grid grid-cols-2 gap-6` → 移动端 gap 10.85
            Row(horizontalArrangement = Arrangement.spacedBy(10.85.dp)) {
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
                // 【2026-10-06 删「换行」下拉】原来这里有个「换行: Enter /
                // Alt+Enter / Ctrl+Enter」下拉 —— 但 newlineKey **全项目零消费**
                // （InputBar 只读 sendByEnter；Android 的换行由输入法自身的
                // 换行键决定，物理键盘修饰键不适用）。用户改了毫无效果，
                // 属于「假设置」。与其留个骗人的控件，不如去掉 ——
                // 要换行就按输入法的换行键（或 Alt+Enter / Ctrl+J）。
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
                                // weight(1f) 复刻 Web 的 flex-shrink —— 4 张卡平分宽度
                                modifier = Modifier.weight(1f),
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
        //   ⚠️ 这节的 `<h3>` 用 `mb-3`（不是 mb-5）—— 实测 marginBottom 12 → 屏幕 11.04。
        SettingsSection(title = "关于", titleGap = SettingsTitleGapTight) {
            // ★ 版本号走 PackageManager 真值 —— 原来写死 "v0.8.100"，
            //   那是 CLI（claude-code-mobile）的版本，APK 自己是 0.1.x。
            //   记得 CI 每次构建会 bump versionName（workflow 里 0.1.run_number）。
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
