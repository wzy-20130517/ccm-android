package com.ccm.app.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.ccm.app.core.provider.AppConfig
import com.ccm.app.core.provider.ProviderStore
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme
import com.ccm.app.ui.theme.CcmMono

/**
 * 模型供应商配置 —— 对齐 `ProviderSettings.tsx`（1469 行）。
 *
 * ## ★ 移动端形态（index.css:1398-1436）
 *
 * ```css
 * @media (max-width: 767px) {
 *   .provider-split { flex-direction: column !important; gap: 12px !important; }
 *   .provider-list {
 *     width: 100% !important;
 *     max-height: 34vh !important;            // 列表限高，自己滚
 *     padding-bottom: 6px !important;
 *     border-bottom: 1px solid var(--claude-border);
 *   }
 *   .provider-detail { width: 100% !important; flex: 1 1 auto !important; }
 *   .provider-detail input, select, textarea { width: 100% !important; }
 *   .provider-detail button { width: auto !important; }    // 按钮不被拉满
 *   .provider-detail [class*="p-6"] { padding: 12px !important; }
 *   .provider-detail [class*="p-5"] { padding: 11px !important; }
 * }
 * ```
 *
 * **所以移动端是**：
 * ```
 * ┌─ 供应商列表（限高 34vh，超出自己滚）
 * │    每行：色块头像(28) + 名字 + 「N models」+ 状态点
 * ├─ 分隔线
 * └─ 详情区（占满剩余）
 *      ├─ SettingGroup「连接信息」：API 密钥 / API 地址
 *      ├─ SettingGroup「模型行为」：思考强度 / API 格式
 *      ├─ SettingGroup「能力开关」：网页搜索 / Tavily key / 图片识别
 *      └─ SettingGroup「模型清单」：模型列表 + 勾选
 * ```
 *
 * ## SettingGroup 实测（Tailwind × 0.92）
 * `rounded-[12px] border p-4 space-y-3.5` →
 * 圆角 **11.04** · 内距 **14.72** · 间距 **12.88** · 边框 `border-claude-border/60`
 *
 * 标题 `text-[13px] font-semibold` → 11.21；提示 `text-[11px] opacity-70` → 9.48
 *
 * ## 与 CLI 的对应
 * 这个页面的每个字段都对应 CLI 的 slash 命令（注释里逐一标注），
 * 两边的**行为契约必须一致** —— 尤其「改 ID 会同步更新引用」这条。
 */
@Composable
fun ProviderSettingsScreen(modifier: Modifier = Modifier) {
    val colors = CCMTheme.colors
    // rebuild AppGraph 需要 Context（2026-10-06：加 Provider 后重建会话用）
    val ctx = androidx.compose.ui.platform.LocalContext.current

    /**
     * 改了 Provider 配置后重建会话（如果改的是**当前在用的**那个）。
     *
     * 【为什么需要】session / ApiClient 是装配期快照 —— 改完配置不重建，
     * 当前会话继续用旧值。用户报的两个症状都源于此：
     *   · 加完 Provider 顶部横幅仍显示「尚未配置 API」（session 还是 null）
     *   · 改完 key/URL 要重启才生效
     *
     * 【为什么判 id】设置页能编辑**任意** Provider（不一定是当前用的）。
     * 无脑 rebuild 会在改别的 Provider 时打断当前会话（flush + 重建 + 恢复历史，
     * 虽然保留上下文但会中断正在跑的轮次）。所以只改当前那个才重建。
     */
    fun rebuildIfCurrent(changedId: String) {
        try {
            val st = com.ccm.app.AppGraph.storage ?: return
            val cur = com.ccm.app.core.provider.ProviderStore(st).load().current
            if (cur != changedId) return
            com.ccm.app.AppGraph.appScope?.let { scope ->
                com.ccm.app.AppGraph.rebuild(ctx, scope)
            }
        } catch (_: Throwable) {}
    }

    /**
     * 防抖版重建 —— 给**逐字符触发**的输入框用（key / URL）。
     *
     * 【为什么需要】`onValueChange` 每敲一个字符就调一次 —— 直接 rebuild
     * 会每字符重建一次会话（flush + 装配 + 恢复历史），卡到没法输入。
     * 用 LaunchedEffect + delay 攒一下：停止输入 800ms 后才真正重建。
     *
     * @param trigger 变化计数（每次输入 +1，用作 effect key）
     */
    @Composable
    fun DebouncedRebuild(trigger: Int, changedId: String) {
        if (trigger <= 0) return
        androidx.compose.runtime.LaunchedEffect(trigger, changedId) {
            kotlinx.coroutines.delay(800)   // 停止输入 800ms 后生效
            rebuildIfCurrent(changedId)
        }
    }

    // ★ 接真实数据（2026-09-27）：原来这里是 selectd=0 + 硬编码三个假 Provider，
    //   所有按钮 onClick 都是空的 —— 用户点了「什么都不发生」，看起来像界面坏了。
    val store = remember { com.ccm.app.AppGraph.storage?.let { ProviderStore(it) } }
    var items by remember { mutableStateOf(store?.list() ?: emptyList()) }
    var selectedId by remember { mutableStateOf(items.firstOrNull()?.id ?: "") }
    var showAddDialog by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }
    // 防抖重建的触发计数（逐字符输入用，见 DebouncedRebuild）
    var keyChangeTick by remember { mutableStateOf(0) }
    var urlChangeTick by remember { mutableStateOf(0) }
    var fetchingModels by remember { mutableStateOf(false) }
    // 【2026-10-06 问题6】模型勾选弹窗的状态
    var showModelPicker by remember { mutableStateOf(false) }
    var modelCandidates by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelPicked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var modelFetchMessage by remember { mutableStateOf<String?>(null) }
    val settingsScope = androidx.compose.runtime.rememberCoroutineScope()
    // ★ #6：联网图标真值（remember 一次，别在 map 里每行读盘 —— 主线程 IO 教训）
    val webSearchOn = remember(refreshTick) {
        com.ccm.app.AppGraph.storage?.let {
            AppConfig.load(it.configFile).config.webSearch
        } == true
    }

    // 重新从磁盘读（写操作后调）
    fun refresh() {
        items = store?.list() ?: emptyList()
        if (items.isNotEmpty() && items.none { it.id == selectedId }) {
            selectedId = items.first().id
        }
        refreshTick++
    }

    val selected = items.firstOrNull { it.id == selectedId }

    // 防抖重建：key / URL 输入停止 800ms 后重建会话（改了当前 Provider 才重建）
    DebouncedRebuild(trigger = keyChangeTick, changedId = selectedId)
    DebouncedRebuild(trigger = urlChangeTick, changedId = selectedId)

    Column(modifier = modifier) {
        Text(
            text = "模型供应商",
            style = CCMText.body16.copy(
                fontSize = 12.29.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = colors.textMain,
        )
        Spacer(Modifier.height(14.72.dp))       // mb-4

        // ── 供应商列表（移动端限高 34vh）──────────────────────────
        Column(modifier = Modifier.heightIn(max = 300.dp)) {
            // 头部：「供应商」+「+ 添加」
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 11.04.dp),        // mb-3
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "供应商",
                    style = CCMText.body13.copy(
                        fontSize = 11.21.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = colors.textSecondary,
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.52.dp))
                        .clickable { showAddDialog = true }   // ★ 原来空的
                        .padding(horizontal = 7.36.dp, vertical = 3.68.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.68.dp),
                ) {
                    PlusIcon(color = colors.textSecondary, size = 11.96.dp)
                    Text(
                        text = "添加",
                        style = CCMText.body12.copy(fontSize = 11.04.sp),
                        color = colors.textSecondary,
                    )
                }
            }

            // 列表项
            // ★ 2026-09-29 排版修复：原来用 horizontalScroll（横向滚）装
            //   纵向列 —— 整体被压成一条**横排**、又受 heightIn(245) 截断，
            //   与 Web 的竖列表完全不同。改为纵向 wrap + 高度上限内滚动。
            Column(
                modifier = Modifier
                    .heightIn(max = 245.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(1.84.dp),   // space-y-0.5
            ) {
                // ★ 真实数据（2026-09-27）：原来这里是硬编码的
                //   WorkBuddy / sharellm / 英伟达 三个假项，点了只改 selected。
                if (items.isEmpty()) {
                    Text(
                        text = "还没有配置供应商。点上方「添加」建一个。",
                        style = CCMText.body12,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    items.forEach { item ->
                        ProviderListItem(
                            name = item.name,
                            modelCount = item.modelCount,   // ★ audit-settings #3：原用 keyCount 冒充模型数
                            webSearchOk = webSearchOn,   // ★ #6：原写死 false → 图标恒不显示
                            enabled = item.enabled,
                            selected = item.id == selectedId,
                            onClick = { selectedId = item.id },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(11.04.dp))       // gap-12 × 0.92
        SettingsDivider()
        Spacer(Modifier.height(11.04.dp))

        // ── 详情区 ────────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(11.04.dp)) {

            // ══════════════════════════════════════════════════════
            //  【2026-10-06 修】没有供应商时隐藏前四组
            //
            //  原来只有「危险区」有 `if (selected != null)` 守卫，前四组
            //  照常渲染 —— 结果是：一个 Provider 都没有时，界面显示
            //  空的连接信息表单（key/url/模型全空），用户以为要在这里填，
            //  填了也不知道存给谁（selected 是 null）。
            //  正确行为：没选中就整块不显示，只留「添加」入口。
            // ══════════════════════════════════════════════════════
            if (selected == null) {
                // 空状态：引导用户添加第一个 Provider
                ProviderSettingGroup(
                    title = "还没有供应商",
                    hint = "添加一个才能开始对话",
                ) {
                    Text(
                        "点上方「+ 添加」按钮新建 —— 需要填 API 地址、模型名和密钥" +
                            "（从你的中转站或官方后台获取）。",
                        style = CCMText.body12,
                        color = colors.textSecondary,
                    )
                }
            }

            if (selected != null) {
            // ── SettingGroup 1：连接信息 ─────────────────────────
            ProviderSettingGroup(
                title = "连接信息",
                hint = "这个供应商怎么连、用什么身份",
            ) {
                // ★ 接真实数据：key/url 从选中的 Provider 读，
                //   remember(selectedId) 保证切换 Provider 时重新初始化
                var apiKey by remember(selectedId, refreshTick) {
                    mutableStateOf(
                        selected?.let { store?.get(it.id)?.let { p -> p.apiKey ?: p.apiKeys?.firstOrNull() ?: "" } } ?: ""
                    )
                }
                var dispName by remember(selectedId, refreshTick) {
                    mutableStateOf(selected?.name ?: "")
                }
                var baseUrl by remember(selectedId, refreshTick) {
                    mutableStateOf(selected?.let { store?.get(it.id)?.url } ?: "")
                }
                var showKey by remember { mutableStateOf(false) }

                ProviderField(label = "API 密钥") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.36.dp),
                    ) {
                        // ★★ audit-settings #7（数据破坏级）：setKey 的实现是
                        //   `apiKeys = null`（单 key 语义），而池模式 provider
                        //   在这里每敲一个字符都会**静默清空整组轮换池**（11 个
                        //   key 的池打一个字母就没了），右侧「池·N」还读旧
                        //   items 不刷新 —— 用户完全看不出。
                        //   修：池模式输入框只读 + 说明；单 key 模式保持即时
                        //   落盘并刷新列表。
                        val keyPoolMode = (selected?.keyCount ?: 0) > 1
                        SettingsTextField(
                            // ★ 2026-09-29 眼睛修复：showKey 原来**只切图标**、
                            //   从没参与值渲染 → 永远明文，「点了没变化」。
                            //   现在显示层掩码：闭眼 = 只留头尾（sk-ab…3fgh），
                            //   开眼 = 明文。输入始终改真实 apiKey，掩码不写盘。
                            value = if (showKey || keyPoolMode) apiKey
                            else maskKey(apiKey),
                            onValueChange = { v ->
                                // 池模式 + 掩码态都忽略敲键
                                // （掩码态编辑会把「sk-ab…3fgh」的省略号后内容写进配置）
                                if (!keyPoolMode && showKey) {
                                    apiKey = v
                                    // 立刻落盘（对齐 CLI 的 /key 命令「立即生效」）
                                    selected?.let { sel ->
                                        store?.setKey(sel.id, v)
                                        keyChangeTick++   // 防抖重建（见 DebouncedRebuild）
                                    }
                                    refreshTick++   // 让「池·N」等派生显示跟着变
                                }
                            },
                            placeholder = if (keyPoolMode) "轮换池（只读）" else "sk-...",
                            modifier = Modifier.weight(1f),
                        )
                        // 池模式禁敲 —— 保护 apiKeys（setKeyPool 才是池的安全入口，
                        // CCM 暂无池编辑 UI，提示到 CLI 配）
                        if (keyPoolMode) {
                            LaunchedEffect(selectedId) {
                                // 进入池 provider 时把显示值换成占位说明（不写盘）
                                apiKey = "轮换池 · ${selected?.keyCount} 个 key（编辑会清空整组）"
                            }
                        }
                        Text(
                            text = if (keyPoolMode) "池 · ${selected?.keyCount} 个" else "",
                            style = CCMText.body11.copy(fontSize = 10.12.sp),
                            color = colors.textSecondary,
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.36.dp))
                                .clickable { showKey = !showKey }
                                .padding(7.36.dp),
                        ) {
                            EyeIcon(
                                open = showKey,
                                color = colors.textSecondary,
                                size = 12.88.dp,
                            )
                        }
                    }
                }

                // ★ 显示名（2026-09-29 补：CLI /name 有、APK 没有 ——
                //   用户点名「model 页功能缺失」。改完列表立刻刷新显示）。
                ProviderField(label = "显示名") {
                    SettingsTextField(
                        value = dispName,
                        onValueChange = {
                            dispName = it
                            selected?.let { sel -> store?.setDisplayName(sel.id, it) }
                            refreshTick++
                        },
                        placeholder = "Claude",
                    )
                }
                ProviderField(label = "API 地址") {
                    SettingsTextField(
                        value = baseUrl,
                        onValueChange = {
                            baseUrl = it
                            selected?.let { sel ->
                                store?.setUrl(sel.id, it)
                                urlChangeTick++   // 防抖重建
                            }
                        },
                    )
                }
            }

            // ── SettingGroup 2：模型行为 ─────────────────────────
            ProviderSettingGroup(
                title = "模型行为",
                hint = "这个供应商的默认模型怎么工作",
            ) {
                // ★ 2026-09-27：原来是写死的 "继承全局" + onClick 空转 ——
                //   点不开、选了也不存。改成读真实值 + 落盘。
                var effort by remember(selectedId, refreshTick) {
                    val st = com.ccm.app.AppGraph.storage
                    mutableStateOf(
                        store?.get(selectedId)?.effort?.takeIf { it.isNotBlank() }
                            ?: st?.let { AppConfig.load(it.configFile).config.effort }?.takeIf { it.isNotBlank() }
                            ?: "none"
                    )
                }
                var format by remember(selectedId, refreshTick) {
                    mutableStateOf(selected?.protocol ?: "openai")
                }

                ProviderField(label = "思考强度") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.36.dp),
                    ) {
                        SettingsSelectMenu(
                            value = effort,
                            options = listOf(
                                "继承全局", "none", "minimal", "low",
                                "medium", "high", "xhigh", "max",
                            ),
                            onPick = {
                                effort = it
                                com.ccm.app.AppGraph.storage?.let { st ->
                                    com.ccm.app.core.provider.ProviderStore(st).setEffort(
                                        selectedId,
                                        if (it == "继承全局") null else it,
                                    )
                                }
                            },
                            title = "思考强度",
                            modifier = Modifier.weight(1f),
                        )
                        // 状态徽章：已开启 / 已关闭
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.36.dp))
                                .border(
                                    1.dp,
                                    Color(0xFF387EE0).copy(alpha = 0.3f),
                                    RoundedCornerShape(7.36.dp),
                                )
                                .padding(horizontal = 9.2.dp, vertical = 5.52.dp),
                        ) {
                            Text(
                                // ★ audit-settings 附注：原恒 "已开启"（选 none/继承也不变）
                                text = if (effort == "继承全局" || effort == "none" || effort.isBlank()) "已关闭" else "已开启",
                                style = CCMText.body12.copy(
                                    fontSize = 11.04.sp,
                                    fontWeight = FontWeight.Medium,
                                ),
                                color = Color(0xFF387EE0),
                            )
                        }
                    }
                }

            // 【2026-10-06 问题8】说明与其它入口的关系（同一份配置）
            Text(
                "与对话页模型选择器的「扩展思考」、通用设置页的开关是同一份配置（Provider 的 effort 字段），在哪调都一样。",
                style = CCMText.body12.copy(fontSize = 10.48.sp, lineHeight = 15.4.sp),
                color = colors.textSecondary,
            )

                ProviderField(label = "API 格式") {
                    Row(horizontalArrangement = Arrangement.spacedBy(7.36.dp)) {
                        FormatChip(
                            label = "OpenAI 兼容",
                            selected = format == "openai",
                            onClick = {
                                format = "openai"
                                selected?.let { sel -> store?.setProtocol(sel.id, "openai") }
                            },
                        )
                        FormatChip(
                            label = "Anthropic",
                            selected = format == "anthropic",
                            onClick = {
                                format = "anthropic"
                                selected?.let { sel -> store?.setProtocol(sel.id, "anthropic") }
                            },
                        )
                    }
                }
            }

            // ── SettingGroup 3：能力开关 ─────────────────────────
            ProviderSettingGroup(
                title = "能力开关",
                hint = "联网搜索与图片识别，决定模型能做什么",
            ) {
                // ★ 第24批（2026-09-28）：三个控件原来是纯本地 remember ——
                //   开关拨了、key 填了，重启全丢，而且 buildSettings 根本
                //   读不到它们（AppConfig 当时没这字段，存了等于没存）。
                //   现在：初始化读 AppConfig，变更 fresh-load → copy → save。
                val cfgStore = com.ccm.app.AppGraph.storage
                fun saveCfg(block: (AppConfig) -> AppConfig) {
                    cfgStore?.let { st ->
                        val fresh = AppConfig.load(st.configFile).config
                        AppConfig.save(block(fresh), st.configFile)
                    }
                }
                val curCfg = remember(refreshTick) {
                    cfgStore?.let { AppConfig.load(it.configFile).config }
                }
                var webSearch by remember { mutableStateOf(curCfg?.webSearch ?: true) }
                var vision by remember { mutableStateOf(curCfg?.vision ?: false) }
                var tavilyKey by remember { mutableStateOf(curCfg?.tavilyKey ?: "") }

                // 网页搜索（带测试按钮）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "网页搜索",
                            style = CCMText.body13.copy(
                                fontSize = 11.21.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            color = colors.textMain,
                        )
                        Text(
                            text = "模型可调用搜索引擎查最新信息",
                            style = CCMText.body11.copy(fontSize = 9.48.sp),
                            color = colors.textSecondary.copy(alpha = 0.7f),
                        )
                    }
                    Spacer(Modifier.width(7.36.dp))
                    SettingsSwitch(
                        checked = webSearch,
                        onCheckedChange = {
                            webSearch = it
                            saveCfg { c -> c.copy(webSearch = it) }
                        },
                    )
                }

                // Tavily key
                ProviderField(label = "Tavily 密钥（可选）") {
                    // ★ 2026-09-29：这里以前**没有小眼睛**（用户点名）。
                    var showTavily by remember { mutableStateOf(false) }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.36.dp),
                    ) {
                        SettingsTextField(
                            value = if (showTavily) tavilyKey else maskKey(tavilyKey),
                            onValueChange = {
                                if (showTavily) {
                                    tavilyKey = it
                                    saveCfg { c -> c.copy(tavilyKey = it.ifBlank { null }) }
                                }
                            },
                            placeholder = "tvly-...",
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.36.dp))
                                .clickable { showTavily = !showTavily }
                                .padding(7.36.dp),
                        ) {
                            EyeIcon(
                                open = showTavily,
                                color = colors.textSecondary,
                                size = 12.88.dp,
                            )
                        }
                    }
                }

                // 图片识别
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "图片识别",
                            style = CCMText.body13.copy(
                                fontSize = 11.21.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            color = colors.textMain,
                        )
                        // 【2026-10-06 核实修正】原文案「此开关已保存、暂未接入
                        // （audit-core #4）」是**旧状态** —— 实际早就接好了。
                        //
                        // 开关语义（对齐 CLI index.mjs:785）：
                        //   开 → **当前模型自己看图**（本 Provider 有视觉能力）
                        //   关 → 用备用识图 Provider（/config vision set 指定）
                        //        转述图片后交主模型；没配备用则直接带图
                        Text(
                            text = "本模型能否直接看图；关闭时用备用识图 Provider" +
                                "（/config vision set 指定）",
                            style = CCMText.body11.copy(fontSize = 9.48.sp),
                            color = colors.textSecondary.copy(alpha = 0.7f),
                        )
                    }
                    Spacer(Modifier.width(7.36.dp))
                    SettingsSwitch(
                        checked = vision,
                        onCheckedChange = {
                            vision = it
                            saveCfg { c -> c.copy(vision = it) }
                        },
                    )
                }
            }

            // ── SettingGroup 4：模型清单 ─────────────────────────
            ProviderSettingGroup(
                title = "模型清单",
                hint = "下拉框里能选到哪些模型（不勾选的不出现）",
            ) {
                // ★ 第24批：原三行是写死的假数据（deepseek flash/pro/lite
                //   + 「日常档/主力档」装饰标签），关页即丢。改为读写
                //   ProviderConfig.models（当前 model 是基准项不可删）。
                val selProvider = items.firstOrNull { it.id == selectedId }
                    ?.let { store?.get(it.id) }
                var modelPool by remember(selectedId, refreshTick) {
                    mutableStateOf(
                        (selProvider?.models.orEmpty() +
                            listOfNotNull(selProvider?.model?.takeIf { it.isNotBlank() }))
                            .distinct(),
                    )
                }
                var newModel by remember { mutableStateOf("") }
                fun savePool(next: List<String>) {
                    modelPool = next
                    val sp = selProvider ?: return
                    val base = sp.model
                    val extra = next.filter { it != base }
                    store?.setModels(sp.id, extra.ifEmpty { null })
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.68.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(7.36.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            enabled = !fetchingModels && selProvider != null,
                            onClick = {
                                val sp = selProvider ?: return@Button
                                fetchingModels = true
                                modelFetchMessage = null
                                settingsScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    val result = fetchProviderModels(sp.url, sp.allKeys().firstOrNull().orEmpty(), sp.protocol)
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        fetchingModels = false
                                        if (result.isSuccess) {
                                            // 【2026-10-06 问题6 修复】原来直接 merged 全塞进
                                            // modelPool（= 全勾选），用户报「全部帮用户勾选了」。
                                            // 现在：候选列表交给勾选弹窗，用户自己挑。
                                            modelCandidates = result.getOrThrow()
                                            // 默认勾选**已配置过的**（对齐 Web：existing.has(id)）
                                            modelPicked = modelCandidates.filter { it in modelPool }.toSet()
                                            showModelPicker = true
                                        } else {
                                            modelFetchMessage = "获取失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                                        }
                                    }
                                }
                            },
                            // 【2026-10-06 问题11 修复】原来 containerColor = colors.hover
                            // （灰底）—— 用户报「颜色让人认为不能点击」。
                            // 对齐 Web：文字按钮（textSecondary + hover 变 text），
                            // 这里用透明底 + 主题文字色。
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = colors.accent,
                            ),
                        ) { Text(if (fetchingModels) "获取中…" else "一键获取模型列表", style = CCMText.body12) }
                        modelFetchMessage?.let { Text(it, style = CCMText.body11, color = colors.textSecondary) }
                    }
                    if (modelPool.isEmpty()) {
                        Text(
                            text = "清单为空 —— 下拉仅显示当前模型",
                            style = CCMText.body12,
                            color = colors.textSecondary,
                        )
                    }
                    modelPool.forEach { m ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(5.52.dp))
                                .background(colors.input.copy(alpha = 0.5f))
                                .padding(start = 7.36.dp, top = 3.68.dp, end = 4.dp, bottom = 3.68.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = m,
                                style = CCMText.body12,
                                color = if (m == selProvider?.model) colors.textMain
                                else colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (m != selProvider?.model) {
                                // 基准项（当前 model）不可删，其余点 ✕ 移出清单
                                Text(
                                    text = "✕",
                                    style = CCMText.body12,
                                    color = colors.textSecondary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable { savePool(modelPool - m) }
                                        .padding(6.dp),
                                )
                            }
                        }
                    }
                    // 添加行
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.36.dp),
                    ) {
                        SettingsTextField(
                            value = newModel,
                            onValueChange = { newModel = it },
                            placeholder = "添加模型，如 gpt-4o-mini",
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "添加",
                            style = CCMText.body13,
                            color = if (newModel.isNotBlank()) colors.accent else colors.textSecondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(enabled = newModel.isNotBlank() && selProvider != null) {
                                    savePool((modelPool + newModel.trim()).distinct())
                                    newModel = ""
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            }   // ← if (selected != null) 的闭合（前四组）

            // ── SettingGroup 5：危险区（2026-09-29 补 —— CLI 有 `rm` 这里没有）──
            if (selected != null) {
                ProviderSettingGroup(
                    title = "危险区",
                    hint = "删除后不可撤销（当前在用的 Provider 不能删）",
                ) {
                    val isCurrent = items.firstOrNull { it.id == selectedId }?.isCurrent == true
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(7.36.dp))
                            .clickable(enabled = !isCurrent) {
                                store?.removeProvider(selectedId)
                                selectedId = items.firstOrNull { it.id != selectedId }?.id ?: ""
                                refresh()
                            }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (isCurrent) "删除供应商（当前在用，不可删）" else "删除这个供应商",
                            style = CCMText.body13,
                            color = if (isCurrent) colors.textSecondary else Color(0xFFDC2626),
                        )
                    }
                }
            }
        }
    }

    // ══ 添加 Provider 对话框（2026-09-27 加）══════════════════════════
    //
    // 原来「添加」按钮 onClick 是空的 —— 用户配不了 Provider，
    // 也就没法开始对话。这个对话框补上「入口」这一环。
    if (showAddDialog) {
        AddProviderDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { id, name, url, model, key, protocol ->
                val created = store?.addProvider(
                    id = id, name = name, url = url,
                    model = model, key = key, protocol = protocol,
                )
                if (created != null) {
                    selectedId = created
                    refresh()
                    // ══════════════════════════════════════════════════
                    //  【2026-10-06 用户报】加完 Provider 顶部横幅仍显示
                    //  「尚未配置 API」，要重启才消失。
                    //
                    //  根因：横幅判据是 `AppGraph.session != null`（装配结果），
                    //  而添加 Provider 只写了 config.json、**没有重建 AppGraph**
                    //  —— session 保持 null。状态栏却显示得出模型名（它现读
                    //  config.json），于是出现「状态栏有模型、横幅说没配置」
                    //  的自相矛盾画面。
                    //
                    //  修：加完立刻 rebuild（重建 ApiClient/AgentLoop/session）
                    //  —— session 变非空 → 横幅自动消失（它是 Compose State）。
                    //  rebuild 会保留当前历史，代价只是几毫秒装配。
                    // ══════════════════════════════════════════════════
                    // 新加的 Provider 是否成为 current 由 addProvider 决定 ——
                    // 是就重建（横幅消失），不是也不亏（判 id 会拦掉）
                    rebuildIfCurrent(created)
                }
                showAddDialog = false
            },
        )
    }

    // 【2026-10-06 问题6】模型勾选弹窗 —— 对齐 Web 的「选择模型」对话框。
    // 用户勾选要启用的模型，确认后写入 modelPool（= 下拉框能选到的清单）。
    if (showModelPicker) {
        ModelPickerDialog(
            candidates = modelCandidates,
            picked = modelPicked,
            onToggle = { id ->
                modelPicked = if (id in modelPicked) modelPicked - id else modelPicked + id
            },
            onSelectAll = { modelPicked = modelCandidates.toSet() },
            onSelectNone = { modelPicked = emptySet() },
            onDismiss = { showModelPicker = false },
            onConfirm = {
                // ⚠️ 这里不能引用内层的 selProvider（作用域不可见），
                //    直接按 selectedId 从 store 取（与内层同源）。
                val sp = store?.get(selectedId)
                val st = com.ccm.app.AppGraph.storage
                if (sp != null && st != null) {
                    // 写入勾选的模型（排除主模型，它在 model 字段里单列）
                    val next = modelPicked.filter { it != sp.model }.toList()
                    com.ccm.app.core.provider.ProviderStore(st).setModels(sp.id, next.ifEmpty { null })
                    // 【2026-10-06 问题41 修复】计数原来是 `next.size`（排除主模型后的
                    // 数量），但用户在弹窗里看到的是**勾选总数**（含主模型）——
                    // 勾了 5 个显示「已保存 4 个」，用户报「计数错误」。
                    // 现在报总数（= 用户实际勾的数量），与弹窗的「已选 N」一致。
                    modelFetchMessage = "已保存 ${modelPicked.size} 个模型（含当前模型）"
                    refresh()
                }
                showModelPicker = false
            },
        )
    }
}

/**
 * 「添加供应商」对话框（2026-09-27 加）。
 *
 * ## 为什么需要
 * `ProviderSettingsScreen` 的「添加」按钮原来 onClick 是空的 ——
 * 用户没有任何途径配置 Provider，于是 `AppGraph.session` 永远是 null，
 * 表现就是「所有按钮都点不动」（点胶囊、点发送都没反应）。
 *
 * ## 字段（对齐 CLI 的 `/config provider add` 一行式）
 * ```
 * /config provider add [ID] name=<显示名> url=<地址> model=<模型> key=<sk-...>
 * ```
 *
 * @param onConfirm 确认回调：(id, name, url, model, key, protocol)
 */
@Composable
private fun AddProviderDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, String, String, String) -> Unit,
) {
    val colors = CCMTheme.colors
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var protocol by remember { mutableStateOf("openai") }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        // 【2026-10-06 问题31 修复】原来缺 border + shadow，圆角 12dp 也不对。
        // 对齐 Web：`rounded-[16px] border border-claude-border bg-claude-bg shadow-2xl`
        // → 16px × 0.92 = 14.72dp 圆角 + 1dp 边框 + 阴影。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(8.dp, RoundedCornerShape(14.72.dp))
                .clip(RoundedCornerShape(14.72.dp))
                .background(colors.bgMain)
                .border(1.dp, colors.border, RoundedCornerShape(14.72.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.2.dp),
        ) {
            Text(
                text = "添加供应商",
                style = CCMText.body16.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textMain,
            )

            ProviderField(label = "编号（可留空，自动分配）") {
                SettingsTextField(value = id, onValueChange = { id = it }, placeholder = "1")
            }
            ProviderField(label = "显示名") {
                SettingsTextField(value = name, onValueChange = { name = it }, placeholder = "Claude")
            }
            ProviderField(label = "API 地址") {
                SettingsTextField(
                    value = url, onValueChange = { url = it },
                    placeholder = "https://api.example.com/v1",
                )
            }
            // 【2026-10-06 用户反馈修正】「API 密钥」原来排在**最后**（协议之前）——
            // 但下面的「获取模型列表」按钮 enabled 条件是 `url && key`，
            // 用户填完地址想点获取时 key 还没填 → 按钮是灰的 →
            // **看起来就是「这功能没用」**。
            // 挪到地址后面（获取按钮之前），顺序才对。
            ProviderField(label = "API 密钥") {
                SettingsTextField(value = key, onValueChange = { key = it }, placeholder = "sk-...")
            }
            ProviderField(label = "模型") {
                SettingsTextField(
                    value = model, onValueChange = { model = it },
                    placeholder = "deepseek-v4.1-flash",
                )
            }
            // 【2026-10-06 问题10 修复】用户报「需要添加完供应商才能获取
            // 那个供应商的模型列表，何意味？」—— 确实别扭：想先看有哪些
            // 模型再决定，却必须先把 Provider 存下来。
            //
            // 现在添加对话框里也能拉：填好 URL + Key 后点这个按钮，
            // 拉到的候选列表弹窗勾选，选中的填进「模型」输入框。
            // （不写入 modelPool —— Provider 还没创建，存不了。）
            val addScope = rememberCoroutineScope()
            var addFetching by remember { mutableStateOf(false) }
            var addFetchError by remember { mutableStateOf("") }
            // 【2026-10-06】拉到的候选列表 + 内联列表展开开关
            var addCandidates by remember { mutableStateOf<List<String>>(emptyList()) }
            var addListExpanded by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (addFetching) "获取中…" else "获取模型列表",
                    style = CCMText.body12,
                    color = if (url.isNotBlank() && key.isNotBlank()) colors.accent else colors.textSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = url.isNotBlank() && key.isNotBlank() && !addFetching) {
                            addFetching = true
                            addFetchError = ""
                            addScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                val r = fetchProviderModels(url.trim(), key.trim(), protocol)
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    addFetching = false
                                    if (r.isSuccess) {
                                        val list = r.getOrThrow()
                                        if (list.isNotEmpty()) {
                                            // 【2026-10-06 用户反馈修正·第二版】
                                            // 第一版是 `model = list.first()`（默认填第一个）
                                            // → 用户骂「他妈干啥让它默认填第一个」。
                                            // 第二版想弹选择框 → 用户指出「添加供应商本来就是
                                            // 弹窗了，你又叠个弹窗」。
                                            // 现在：**内联列表** —— 直接在这个对话框里
                                            // 展开可滚动列表，点一个就选中并收起。
                                            addCandidates = list
                                            addListExpanded = true
                                            addFetchError = ""
                                        } else addFetchError = "接口没返回任何模型"
                                    } else {
                                        addFetchError = "获取失败：${r.exceptionOrNull()?.message ?: "未知错误"}"
                                    }
                                }
                            }
                        }
                        .padding(vertical = 4.dp),
                )
                if (addFetchError.isNotBlank()) {
                    Text(
                        addFetchError,
                        style = CCMText.body12,
                        color = colors.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            // ── 内联候选列表（不叠弹窗）────────────────────────────────
            // 点一个 → 填进 model 输入框 + 收起列表。
            if (addListExpanded && addCandidates.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .clip(RoundedCornerShape(7.36.dp))
                        .background(colors.hover.copy(alpha = 0.4f))
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 3.68.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 11.04.dp, vertical = 3.68.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "共 ${addCandidates.size} 个 · 点选一个",
                            style = CCMText.body12.copy(fontSize = 10.48.sp),
                            color = colors.textSecondary,
                        )
                        Text(
                            "收起",
                            style = CCMText.body12.copy(fontSize = 10.48.sp),
                            color = colors.accent,
                            modifier = Modifier.clickable { addListExpanded = false },
                        )
                    }
                    addCandidates.forEach { m ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(29.44.dp)
                                .clip(RoundedCornerShape(5.52.dp))
                                .clickable {
                                    model = m
                                    addListExpanded = false
                                }
                                .padding(horizontal = 11.04.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                m,
                                style = CCMText.body13.copy(fontSize = 12.sp),
                                color = colors.textMain,
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            ProviderField(label = "协议") {
                Row(horizontalArrangement = Arrangement.spacedBy(7.36.dp)) {
                    FormatChip(
                        label = "OpenAI 兼容",
                        selected = protocol == "openai",
                        onClick = { protocol = "openai" },
                    )
                    FormatChip(
                        label = "Anthropic",
                        selected = protocol == "anthropic",
                        onClick = { protocol = "anthropic" },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(9.2.dp, Alignment.End),
            ) {
                Text(
                    text = "取消",
                    style = CCMText.body13,
                    color = colors.textSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Text(
                    text = "添加",
                    style = CCMText.body13.copy(fontWeight = FontWeight.Medium),
                    color = if (url.isNotBlank() && key.isNotBlank()) Color(0xFFD97757)
                            else colors.textSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = url.isNotBlank() && key.isNotBlank()) {
                            onConfirm(id, name, url, model, key, protocol)
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * 供应商列表项 —— 对应源码
 * `<button className="w-full flex items-center gap-3 px-3 py-3 rounded-[12px] border
 *   ${active ? 'bg-claude-input border-claude-border shadow-sm' : 'border-transparent'}">`
 *
 * 实测（Tailwind × 0.92）：内距 **11.04** · 圆角 **11.04** · 间距 **11.04** ·
 * 头像 28 → **25.76** · 名字 `text-[13px]` → 11.21 · 副行 `text-[10px]` → 9.2。
 *
 * 未启用时右侧显示一个 `w-1.5 h-1.5 rounded-full bg-claude-textSecondary/30` 小点。
 */
@Composable
private fun ProviderListItem(
    name: String,
    modelCount: Int,
    webSearchOk: Boolean,
    enabled: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = CCMTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.04.dp))
            .background(if (selected) colors.input else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) colors.border else Color.Transparent,
                shape = RoundedCornerShape(11.04.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.04.dp, vertical = 11.04.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.04.dp),      // gap-3
    ) {
        ProviderAvatar(name = name, size = 25.76.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = CCMText.body13.copy(
                    fontSize = 11.21.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                ),
                color = if (selected) colors.textMain else colors.textSecondary,
                maxLines = 1,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.52.dp),
            ) {
                Text(
                    text = "$modelCount models",
                    style = CCMText.body10.copy(fontSize = 9.2.sp),
                    color = colors.textSecondary.copy(alpha = 0.5f),
                )
                if (webSearchOk) {
                    GlobeIcon(color = Color(0xFF387EE0), size = 8.28.dp)
                }
            }
        }
        if (!enabled) {
            Box(
                modifier = Modifier
                    .size(1.38.dp)
                    .clip(CircleShape)
                    .background(colors.textSecondary.copy(alpha = 0.3f)),
            )
        }
    }
}

/**
 * 供应商头像 —— 源码 `ProviderIcon`：圆形色块 + 首字母。
 *
 * 源码里每个供应商有专属色（`PROVIDER_LOGOS`），未知供应商走
 * `getProviderMeta()` 生成的哈希色。这里用首字母 + 稳定哈希色。
 */
@Composable
private fun ProviderAvatar(name: String, size: androidx.compose.ui.unit.Dp) {
    val palette = listOf(
        Color(0xFFD97757), Color(0xFF387EE0), Color(0xFF22C55E),
        Color(0xFFF59E0B), Color(0xFF8B5CF6), Color(0xFFEC4899),
    )
    val idx = (name.hashCode().let { if (it < 0) -it else it }) % palette.size
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(palette[idx]),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.take(1).uppercase(),
            style = CCMText.body13.copy(
                fontSize = 12.88.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = Color.White,
        )
    }
}

/**
 * SettingGroup —— 对应源码
 * `<div className="rounded-[12px] border border-claude-border/60
 *   bg-black/[0.012] dark:bg-white/[0.015] p-4 space-y-3.5">`
 *
 * 实测（Tailwind × 0.92）：圆角 **11.04** · 内距 **14.72** · 间距 **12.88**。
 * 背景是极淡的黑（亮色 `rgba(0,0,0,0.012)`），暗色是极淡的白 —— 近乎不可见，
 * 但确实存在，别省掉。
 */
@Composable
private fun ProviderSettingGroup(
    title: String,
    hint: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = CCMTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.04.dp))
            .background(colors.textMain.copy(alpha = 0.012f))
            .border(
                width = 1.dp,
                color = colors.border.copy(alpha = 0.6f),
                shape = RoundedCornerShape(11.04.dp),
            )
            .padding(14.72.dp),
        verticalArrangement = Arrangement.spacedBy(12.88.dp),        // space-y-3.5
    ) {
        Column {
            Text(
                text = title,
                style = CCMText.body13.copy(
                    fontSize = 11.21.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = colors.textMain,
            )
            if (hint != null) {
                Spacer(Modifier.height(1.84.dp))     // mt-0.5
                Text(
                    text = hint,
                    style = CCMText.body11.copy(fontSize = 9.48.sp),
                    color = colors.textSecondary.copy(alpha = 0.7f),
                )
            }
        }
        content()
    }
}

/**
 * 详情区字段 —— 对应源码
 * `<div><label text-[12px] text-claude-textSecondary mb-1.5 block font-medium>…</label>控件</div>`
 *
 * 实测：标签 `text-[12px]` 移动端 clamp(11,2.9vw,12) → 11.397 → **10.48**。
 */
@Composable
private fun ProviderField(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = CCMText.body12.copy(
                fontSize = 10.48.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = CCMTheme.colors.textSecondary,
        )
        Spacer(Modifier.height(5.52.dp))            // mb-1.5
        content()
    }
}

/**
 * 格式切换 chip —— 对应源码
 * `<button className="px-3.5 py-1.5 rounded-lg text-[12px] font-medium border
 *   ${active ? 'bg-black/[0.05] border-claude-textSecondary/50 text-claude-text'
 *            : 'border-claude-border/40 text-claude-textSecondary'}">`
 *
 * 实测（Tailwind × 0.92）：内距 **12.88 × 5.52** · 圆角 **7.36** · 字号 **11.04**。
 * ⚠️ 移动端 `.provider-detail button { width: auto !important }` —— 按钮**不拉满**。
 */
@Composable
private fun FormatChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = CCMTheme.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(7.36.dp))
            .background(if (selected) colors.textMain.copy(alpha = 0.05f) else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) colors.textSecondary.copy(alpha = 0.5f)
                else colors.border.copy(alpha = 0.4f),
                shape = RoundedCornerShape(7.36.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.88.dp, vertical = 5.52.dp),
    ) {
        Text(
            text = label,
            style = CCMText.body12.copy(
                fontSize = 11.04.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = if (selected) colors.textMain else colors.textSecondary,
        )
    }
}

/**
 * 模型清单行 —— 对应源码里的模型列表项：
 * 勾选框 + 模型 id + 档位标签 + `Thinking` 徽章（可选）。
 *
 * 源码里 Thinking 徽章：
 * `<span className="text-[9px] px-1.5 py-0.5 rounded bg-amber-500/10
 *   text-amber-600 dark:text-amber-400">Thinking</span>`
 * 实测（Tailwind × 0.92）：字号 **8.28** · 内距 **5.52 × 1.84** · 圆角 **3.68**。
 */
@Composable
private fun ProviderModelRow(
    id: String,
    tierLabel: String,
    thinking: Boolean,
    checked: Boolean,
) {
    val colors = CCMTheme.colors
    var isChecked by remember { mutableStateOf(checked) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.52.dp))
            .clickable { isChecked = !isChecked }
            .padding(vertical = 3.68.dp, horizontal = 5.52.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.36.dp),
    ) {
        Checkbox(checked = isChecked, size = 12.88.dp)
        Text(
            text = id,
            style = CCMText.body12.copy(
                fontSize = 10.48.sp,
                fontFamily = CcmMono,
            ),
            color = colors.textMain,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        if (thinking) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(3.68.dp))
                    .background(Color(0xFFF59E0B).copy(alpha = 0.1f))
                    .padding(horizontal = 5.52.dp, vertical = 1.84.dp),
            ) {
                Text(
                    text = "Thinking",
                    style = CCMText.body10.copy(fontSize = 8.28.sp),
                    color = Color(0xFFD97706),
                )
            }
        }
        Text(
            text = tierLabel,
            style = CCMText.body11.copy(fontSize = 9.2.sp),
            color = colors.textSecondary,
        )
    }
}

/** 勾选框 —— 对应源码 `<input type="checkbox">`，14×14 → 12.88，选中 `#387EE0` */
@Composable
private fun Checkbox(checked: Boolean, size: androidx.compose.ui.unit.Dp) {
    val colors = CCMTheme.colors
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(2.76.dp))
            .background(if (checked) Color(0xFF387EE0) else colors.input)
            .border(
                width = 1.dp,
                color = if (checked) Color(0xFF387EE0) else colors.border,
                shape = RoundedCornerShape(2.76.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Canvas(modifier = Modifier.size(size)) {
                val w = this.size.width
                val h = this.size.height
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.24f, h * 0.5f)
                    lineTo(w * 0.43f, h * 0.7f)
                    lineTo(w * 0.76f, h * 0.31f)
                }
                drawPath(
                    path = p,
                    color = Color.White,
                    style = Stroke(width = 1.38.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
    }
}

/** 加号图标 —— 源码 lucide `Plus size={13}` → 11.96 */
@Composable
private fun PlusIcon(color: Color, size: androidx.compose.ui.unit.Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val stroke = 1.38.dp.toPx()
        val c = this.size.width / 2f
        val r = this.size.width / 2f - stroke / 2
        drawLine(color, Offset(c - r, c), Offset(c + r, c), stroke, StrokeCap.Round)
        drawLine(color, Offset(c, c - r), Offset(c, c + r), stroke, StrokeCap.Round)
    }
}

/** 眼睛图标 —— 源码 lucide `Eye` / `EyeOff size={14}` → 12.88 */
@Composable
private fun EyeIcon(open: Boolean, color: Color, size: androidx.compose.ui.unit.Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = 1.2.dp.toPx()
        if (open) {
            // 眼形：上下两段弧
            drawArc(
                color = color,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                style = Stroke(width = stroke),
                topLeft = Offset(stroke / 2, h * 0.28f),
                size = androidx.compose.ui.geometry.Size(w - stroke, h * 0.44f),
            )
            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                style = Stroke(width = stroke),
                topLeft = Offset(stroke / 2, h * 0.28f),
                size = androidx.compose.ui.geometry.Size(w - stroke, h * 0.44f),
            )
            drawCircle(color, radius = h * 0.13f, center = Offset(w / 2, h / 2))
        } else {
            // 眼形 + 斜杠
            drawArc(
                color = color,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                style = Stroke(width = stroke),
                topLeft = Offset(stroke / 2, h * 0.28f),
                size = androidx.compose.ui.geometry.Size(w - stroke, h * 0.44f),
            )
            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                style = Stroke(width = stroke),
                topLeft = Offset(stroke / 2, h * 0.28f),
                size = androidx.compose.ui.geometry.Size(w - stroke, h * 0.44f),
            )
            drawLine(
                color,
                Offset(w * 0.2f, h * 0.8f),
                Offset(w * 0.8f, h * 0.2f),
                stroke,
                StrokeCap.Round,
            )
        }
    }
}

/** 地球图标 —— 源码 lucide `Globe size={9}` → 8.28，表示「已验证支持网页搜索」 */
@Composable
private fun GlobeIcon(color: Color, size: androidx.compose.ui.unit.Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val stroke = 0.92.dp.toPx()
        val r = this.size.minDimension / 2f - stroke / 2
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        drawCircle(color, radius = r, center = c, style = Stroke(width = stroke))
        // 经线（椭圆）
        drawOval(
            color = color,
            topLeft = Offset(c.x - r * 0.45f, c.y - r),
            size = androidx.compose.ui.geometry.Size(r * 0.9f, r * 2f),
            style = Stroke(width = stroke),
        )
        // 纬线（横线）
        drawLine(
            color,
            Offset(c.x - r, c.y),
            Offset(c.x + r, c.y),
            stroke,
        )
    }
}

/**
 * 密钥掩码（2026-09-29）：只留头 5 尾 4，中间省略号。
 * 短 key（≤12 字符）直接整体星号化防反推。
 * 空串原样返回（placeholder 才会显示）。
 */
private fun fetchProviderModels(baseUrl: String, apiKey: String, protocol: String): Result<List<String>> = runCatching {
    require(baseUrl.isNotBlank()) { "API 地址为空" }
    require(apiKey.isNotBlank()) { "API Key 为空" }

    // 【2026-10-06 用户反馈修正】原来的 URL 拼接有 bug：
    //   `val url = if (anthropic) "$base/v1/models" else "$base/models"`
    // —— 用户填 `https://xxx/v1` 时变成 `https://xxx/v1/models` ✓
    //    但填 `https://xxx` 时变成 `https://xxx/models` ✗（缺 /v1）
    //    填 `https://xxx/v1/chat/completions` 时只 removeSuffix 了 /responses，
    //    漏了 /chat/completions → `https://xxx/v1/chat/completions/models` ✗
    //
    // 照抄 Web server.mjs:2716 的正确做法：
    //   1. 去尾部斜杠
    //   2. 去掉 /chat/completions 或 /messages 后缀
    //   3. 没有 /vN 结尾就补 /v1
    //   4. 拼 /models
    var endpoint = baseUrl.trim().trimEnd('/')
        .removeSuffix("/chat/completions")
        .removeSuffix("/messages")
        .trimEnd('/')
    if (!Regex("/v\\d+$").containsMatchIn(endpoint)) endpoint += "/v1"
    endpoint += "/models"

    val isAnthropic = protocol.equals("anthropic", ignoreCase = true)
    val builder = Request.Builder().url(endpoint).get()
    if (isAnthropic) {
        builder.header("x-api-key", apiKey)
        builder.header("anthropic-version", "2023-06-01")
    } else {
        builder.header("Authorization", "Bearer $apiKey")
    }

    OkHttpClient().newCall(builder.build()).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            // 明确的错误提示（原来只有 "HTTP 401" 看不出原因）
            val hint = when (response.code) {
                401 -> "（API Key 无效）"
                403 -> "（无权访问，可能是 IP 限制）"
                404 -> "（该地址没有 /models 接口）"
                else -> ""
            }
            error("HTTP ${response.code}$hint · $endpoint")
        }
        val root = Json.parseToJsonElement(body).jsonObject
        // data 数组（OpenAI）或 models 数组（部分中转站）都要认
        val arr = root["data"]?.jsonArray ?: root["models"]?.jsonArray
        if (arr == null) error("响应里没有 data/models 数组 · $endpoint")
        // id 或 name 字段都要认（有的站用 name）
        arr.mapNotNull {
            val o = it.jsonObject
            (o["id"]?.jsonPrimitive?.content ?: o["name"]?.jsonPrimitive?.content)
                ?.trim()?.takeIf(String::isNotBlank)
        }.distinct().sorted()
    }
}

private fun maskKey(k: String): String {
    if (k.isBlank()) return k
    if (k.length <= 12) return "•".repeat(k.length)
    return k.take(5) + "…" + k.takeLast(4)
}


/**
 * 模型勾选弹窗 —— 对齐 Web `ProviderSettings.tsx:1348` 的「选择模型」对话框。
 *
 * 【2026-10-06 问题6】用户报「获取模型列表时全部帮用户勾选了，这不行」——
 * 原实现直接把拉到的所有模型塞进 modelPool。现在给用户自己挑。
 *
 * 交互（对齐 Web）：
 * - 顶部：标题 + 「已选 N / 共 M」
 * - 搜索框（模型多时过滤）
 * - 全选 / 反选
 * - 列表：每行 checkbox + 模型名
 * - 底部：取消 / 保存
 */
@Composable
private fun ModelPickerDialog(
    candidates: List<String>,
    picked: Set<String>,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = CCMTheme.colors
    var search by remember { mutableStateOf("") }
    val filtered = remember(candidates, search) {
        if (search.isBlank()) candidates
        else candidates.filter { it.contains(search, ignoreCase = true) }
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        // 【2026-10-06 问题31】同上：加 shadow + border + 圆角对齐
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(8.dp, RoundedCornerShape(14.72.dp))
                .clip(RoundedCornerShape(14.72.dp))
                .background(colors.bgMain)
                .border(1.dp, colors.border, RoundedCornerShape(14.72.dp))
                .padding(16.dp),
        ) {
            // 标题行
            Text("选择模型", style = CCMText.body16, color = colors.textMain)
            Spacer(Modifier.height(4.dp))
            Text(
                "已选 ${picked.size} / 共 ${candidates.size}",
                style = CCMText.body12,
                color = colors.textSecondary,
            )
            Spacer(Modifier.height(12.dp))

            // 搜索框
            SettingsTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = "搜索模型…",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            // 全选 / 反选
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "全选",
                    style = CCMText.body12,
                    color = colors.accent,
                    modifier = Modifier.clickable { onSelectAll() },
                )
                Text(
                    "清空",
                    style = CCMText.body12,
                    color = colors.accent,
                    modifier = Modifier.clickable { onSelectNone() },
                )
            }
            Spacer(Modifier.height(8.dp))

            // 候选列表（可滚动）
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
            ) {
                // ⚠️ 用 items(count) 重载而不是 items(list) —— 后者要
                //    `import androidx.compose.foundation.lazy.items`，
                //    全限定名写法下编译器找不到扩展函数。
                items(count = filtered.size) { idx ->
                    val id = filtered[idx]
                    val checked = id in picked
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(5.52.dp))
                            .clickable { onToggle(id) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // 简单 checkbox（不引 Material3 Checkbox，样式更可控）
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (checked) colors.accent else Color.Transparent)
                                .border(1.dp, if (checked) colors.accent else colors.border, RoundedCornerShape(3.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (checked) {
                                Text("✓", style = CCMText.body12, color = Color.White)
                            }
                        }
                        Text(
                            id,
                            style = CCMText.body13,
                            color = colors.textMain,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            // 底部按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    "取消",
                    style = CCMText.body13,
                    color = colors.textSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.36.dp))
                        .clickable { onDismiss() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "保存",
                    style = CCMText.body13,
                    color = colors.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.36.dp))
                        .clickable { onConfirm() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}
