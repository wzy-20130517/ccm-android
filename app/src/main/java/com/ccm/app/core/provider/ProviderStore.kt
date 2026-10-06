package com.ccm.app.core.provider

import com.ccm.app.core.AppStorage
import java.io.File

/**
 * Provider 配置的读写门面（UI ↔ config.json 的桥）。
 *
 * ## 为什么单独一层
 * `AppConfig.load/save` 是纯数据层（kotlinx.serialization），
 * 但 UI 需要一个**可变、可保存、能刷新**的接口：
 * - 读：把 `Map<String, ProviderConfig>` 拍平成 UI 能直接渲染的列表
 * - 写：改单个字段后立刻落盘（对齐 CLI 的 `/model`、`/key`、`/url` 命令）
 * - 加/删：对应 CLI 的 `/config provider add` / `rm`
 *
 * ## 与 CLI 的行为契约（必须一致）
 * | CLI 命令 | 本类方法 |
 * |---|---|
 * | `/model <id> <模型>` | [setModel] |
 * | `/url <id> <地址>` | [setUrl] |
 * | `/key <id> <sk-...>` | [setKey] |
 * | `/protocol <id> <协议>` | [setProtocol] |
 * | `/config <id>`（切换） | [setCurrent] |
 * | `/config provider add` | [addProvider] |
 * | `/config provider rm <id>` | [removeProvider] |
 *
 * ⚠️ **改 ID 要同步更新引用**（`current` 指向的 id）—— CLI 那边也这么做。
 * 不更新的话当前 Provider 会瞬间「消失」，表现为「对话突然不能用了」。
 */
class ProviderStore(private val storage: AppStorage) {

    private val file: File get() = storage.configFile

    /** 当前配置（每次读都从磁盘拿，保证与 CLI 写的一致） */
    fun load(): AppConfig = AppConfig.load(file).config

    /** 加载失败时的错误信息（null = 正常） */
    fun loadError(): String? = AppConfig.load(file).error

    /**
     * UI 渲染用的列表项（拍平后的视图模型）。
     *
     * @param id Provider 编号（CLI 的 `/config <id>` 用它）
     * @param name 显示名
     * @param model 当前模型
     * @param keyCount key 数量（>1 表示轮换池）
     * @param enabled 是否有效（有 key 且 url 非空）
     * @param isCurrent 是否是当前选中的 Provider
     */
    data class Item(
        val id: String,
        val name: String,
        val model: String,
        val url: String,
        val keyCount: Int,
        val protocol: String,
        val enabled: Boolean,
        val isCurrent: Boolean,
        /** 模型池（不含基准 model —— count 时合并去重）。 */
        val models: List<String> = emptyList(),
    ) {
        /** 模型数（原 UI 用 keyCount 冒充 —— audit-settings #3）。 */
        val modelCount: Int
            get() = (listOf(model) + models).filter { it.isNotBlank() }.distinct().size
    }

    /** 列出所有 Provider（按 id 排序，当前项移到最前） */
    fun list(): List<Item> {
        val cfg = load()
        return cfg.providers.entries
            .map { (id, p) ->
                Item(
                    id = id,
                    name = p.displayName,
                    model = p.model,
                    url = p.url,
                    keyCount = p.allKeys().size,
                    protocol = p.protocol,
                    enabled = p.allKeys().isNotEmpty() && p.url.isNotBlank(),
                    isCurrent = id == cfg.current,
                    models = p.models.orEmpty(),
                )
            }
            .sortedWith(compareByDescending<Item> { it.isCurrent }.thenBy { it.id })
    }

    /** 取单个 Provider 的原始配置（详情页用） */
    fun get(id: String): ProviderConfig? = load().providers[id]

    // ══════════════════════════════════════════════════════════════════
    //  写操作（每个都立刻落盘，对齐 CLI 的「立即生效」）
    // ══════════════════════════════════════════════════════════════════

    /** 内部：改某个 Provider 后保存 */
    private fun update(id: String, block: (ProviderConfig) -> ProviderConfig): Boolean {
        val cfg = load()
        val p = cfg.providers[id] ?: return false
        val next = cfg.copy(providers = cfg.providers + (id to block(p)))
        return AppConfig.save(next, file)
    }

    /** 切换当前 Provider（对应 `/config <id>`） */
    fun setCurrent(id: String): Boolean {
        val cfg = load()
        if (!cfg.providers.containsKey(id)) return false
        return AppConfig.save(cfg.copy(current = id), file)
    }

    /** 改全局思考强度（对应 `/effort <级别>`，存 AppConfig.effort）。 */
    fun setGlobalEffort(level: String): Boolean {
        val cfg = load()
        // "继承全局" = 清掉字段（回落到默认）
        val v = if (level.startsWith("继承")) null else level
        return AppConfig.save(cfg.copy(effort = v), file)
    }

    /**
     * 改当前 Provider 的扩展思考强度。
     *
     * ⚠️ "none" 必须显式存为字符串，不能转成 null：
     * null 的语义是「继承全局」，而全局 effort 可能是 "max" ——
     * 用户点关闭后读回来仍是开启，表现为「开关关不掉」。
     * 只有显式传 null（设置页选「继承全局」）才清空字段。
     */
    fun setEffort(id: String, level: String?): Boolean =
        update(id) { it.copy(effort = level?.takeIf { value -> value.isNotBlank() }) }

    /** 改模型池（设置页「模型清单」写回，第24批）。 */
    fun setModels(id: String, models: List<String>?): Boolean =
        update(id) { it.copy(models = models) }

    /** 改显示名（对应 CLI `/name` / `/config provider rename` 的显示名语义）。 */
    fun setDisplayName(id: String, name: String): Boolean =
        update(id) { it.copy(name = name.trim().ifBlank { id }) }

    /** 改模型（对应 `/model <id> <名称>`） */
    fun setModel(id: String, model: String): Boolean =
        update(id) { it.copy(model = model.trim()) }

    /** 改 URL（对应 `/url <id> <地址>`） */
    fun setUrl(id: String, url: String): Boolean =
        update(id) { it.copy(url = url.trim()) }

    /** 改协议（对应 `/protocol <id> <协议>`） */
    fun setProtocol(id: String, protocol: String): Boolean =
        update(id) { it.copy(protocol = protocol.trim()) }

    /**
     * 设单 key（对应 `/key <id> <sk-...>`）。
     * 会清空 apiKeys 数组 —— 两者不能同时存在（CLI 侧同样的约束）。
     */
    fun setKey(id: String, key: String): Boolean =
        update(id) {
            if (key.isBlank()) it.copy(apiKey = null, apiKeys = null)
            else it.copy(apiKey = key.trim(), apiKeys = null)
        }

    /** 设 key 轮换池（对应 `/key <id> pool <k1> <k2> ...>`） */
    fun setKeyPool(id: String, keys: List<String>): Boolean =
        update(id) {
            val clean = keys.map { k -> k.trim() }.filter { k -> k.isNotEmpty() }
            if (clean.isEmpty()) it.copy(apiKey = null, apiKeys = null)
            else it.copy(apiKeys = clean, apiKey = null)
        }

    /**
     * 新建 Provider（对应 `/config provider add`）。
     *
     * @param id 编号（空 = 自动取「最小的未占用正整数」）
     * @return 新 Provider 的 id；失败返回 null
     */
    fun addProvider(
        id: String = "",
        name: String = "",
        url: String = "",
        model: String = "",
        key: String = "",
        protocol: String = "openai",
    ): String? {
        val cfg = load()
        val newId = id.ifBlank {
            // 找最小的未占用正整数（与 CLI 的编号习惯一致）
            var n = 1
            while (cfg.providers.containsKey(n.toString())) n++
            n.toString()
        }
        if (cfg.providers.containsKey(newId)) return null   // 编号已占用

        val p = ProviderConfig(
            id = newId,
            name = name.ifBlank { newId },
            url = url.trim(),
            model = model.trim(),
            apiKey = key.trim().ifBlank { null },
            protocol = protocol,
        )
        val next = cfg.copy(
            providers = cfg.providers + (newId to p),
            // 第一个 Provider 自动设为当前（否则用户加完还得手动切一次）
            current = cfg.current.ifBlank { newId },
        )
        return if (AppConfig.save(next, file)) newId else null
    }

    /**
     * 删除 Provider（对应 `/config provider rm <id>`）。
     *
     * ⚠️ **不能删当前在用的** —— CLI 侧同样拒绝。
     * 删掉的话 `currentProvider` 变 null，界面会突然「没有可用 Provider」。
     */
    fun removeProvider(id: String): Boolean {
        val cfg = load()
        if (cfg.current == id) return false       // 当前在用的不许删
        if (!cfg.providers.containsKey(id)) return false
        val next = cfg.copy(providers = cfg.providers - id)
        return AppConfig.save(next, file)
    }

    /** 是否已配置可用 Provider（UI 判断显示引导还是对话页） */
    fun hasUsable(): Boolean {
        val cfg = load()
        val p = cfg.currentProvider ?: return false
        return p.allKeys().isNotEmpty() && p.url.isNotBlank()
    }
}
