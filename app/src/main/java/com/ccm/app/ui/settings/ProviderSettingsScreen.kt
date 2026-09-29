package com.ccm.app.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

    // ★ 接真实数据（2026-09-27）：原来这里是 selectd=0 + 硬编码三个假 Provider，
    //   所有按钮 onClick 都是空的 —— 用户点了「什么都不发生」，看起来像界面坏了。
    val store = remember { com.ccm.app.AppGraph.storage?.let { ProviderStore(it) } }
    var items by remember { mutableStateOf(store?.list() ?: emptyList()) }
    var selectedId by remember { mutableStateOf(items.firstOrNull()?.id ?: "") }
    var showAddDialog by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }
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
                                    selected?.let { sel -> store?.setKey(sel.id, v) }
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
                        placeholder = "WorkBuddy",
                    )
                }
                ProviderField(label = "API 地址") {
                    SettingsTextField(
                        value = baseUrl,
                        onValueChange = {
                            baseUrl = it
                            selected?.let { sel -> store?.setUrl(sel.id, it) }
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
                        st?.let { AppConfig.load(it.configFile).config.effort }
                            ?.takeIf { it.isNotBlank() } ?: "继承全局"
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
                                    com.ccm.app.core.provider.ProviderStore(st).setGlobalEffort(it)
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
                        Text(
                            text = "识图路由由 visionProvider 决定；此开关已保存、暂未接入（audit-core #4）",
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
                }
                showAddDialog = false
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.bgMain)
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
                SettingsTextField(value = name, onValueChange = { name = it }, placeholder = "WorkBuddy")
            }
            ProviderField(label = "API 地址") {
                SettingsTextField(
                    value = url, onValueChange = { url = it },
                    placeholder = "https://api.example.com/v1",
                )
            }
            ProviderField(label = "模型") {
                SettingsTextField(
                    value = model, onValueChange = { model = it },
                    placeholder = "deepseek-v4.1-flash",
                )
            }
            ProviderField(label = "API 密钥") {
                SettingsTextField(value = key, onValueChange = { key = it }, placeholder = "sk-...")
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
private fun maskKey(k: String): String {
    if (k.isBlank()) return k
    if (k.length <= 12) return "•".repeat(k.length)
    return k.take(5) + "…" + k.takeLast(4)
}
