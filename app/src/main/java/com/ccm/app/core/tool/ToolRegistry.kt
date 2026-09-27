package com.ccm.app.core.tool

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 工具注册表。
 *
 * ══════════════════════════════════════════════════════════════
 * 【设计要点 1：列表必须惰性取，绝不能是构造时的快照】
 * ══════════════════════════════════════════════════════════════
 * CCM 上踩过一个坑，现象是「子 Agent 调 Agent 工具全部返回 Tool not found」，
 * 三层编排直接退化成两层。
 *
 * 根因：`index.mjs` 用 `registry.list()` 取**数组快照**传给 SubAgentTool，
 * 而 SubAgentTool 自己是在那之后才注册进 registry 的 ——
 * 快照里永远没有 'Agent' 这个工具。
 *
 * 教训：**「注册顺序 + 快照」是隐性耦合**。凡是把注册表内容传给别人的地方，
 * 优先传「取值函数」而不是「取到的值」。
 *
 * 本类对应做法：
 * - [list] 每次调用都返回当前全量（内部是 CopyOnWriteArrayList，线程安全）
 * - [toolProvider] 返回一个 `() -> List<Tool>` 闭包，专门给需要「稍后再取」的场景
 *
 * 【设计要点 2：注册顺序不影响可见性】
 * 所有工具注册完再 start；运行期也能动态注册（MCP server 热加载）。
 * CopyOnWriteArrayList 保证「遍历中注册」不会 ConcurrentModificationException。
 */
class ToolRegistry {

    private val tools = CopyOnWriteArrayList<Tool>()
    private val byName = LinkedHashMap<String, Tool>()

    /** 当前全量工具（**实时视图**，不是快照）。 */
    val list: List<Tool> get() = tools.toList()

    /**
     * 取值函数 —— 给需要「延迟到真正用的时候才取」的场景。
     *
     * ```kotlin
     * // ❌ 错误：构造时快照，之后注册的工具看不见
     * val agent = Agent(tools = registry.list)
     *
     * // ✅ 正确：每次用到时现取
     * val agent = Agent(toolsProvider = registry.toolProvider)
     * ```
     */
    val toolProvider: () -> List<Tool> get() = { tools.toList() }

    /** 工具数量。 */
    val size: Int get() = tools.size

    /**
     * 注册一个工具。
     *
     * @return 注册成功返回 true；**重名返回 false 且不覆盖**（静默覆盖会让
     *         「两个同名工具」的 bug 极难发现 —— 后注册的悄悄顶掉先注册的，
     *         调用方还以为用的是原来那个）。
     */
    @Synchronized
    fun register(tool: Tool): Boolean {
        if (byName.containsKey(tool.name)) return false
        tools.add(tool)
        byName[tool.name] = tool
        return true
    }

    /** 批量注册。返回被拒绝的工具名（重名）。 */
    @Synchronized
    fun registerAll(vararg tools: Tool): List<String> =
        tools.mapNotNull { if (!register(it)) it.name else null }

    /**
     * 注销。主要给 MCP server 热卸载用。
     * @return 是否真的移除了
     */
    @Synchronized
    fun unregister(name: String): Boolean {
        val t = byName.remove(name) ?: return false
        tools.remove(t)
        return true
    }

    /** 按名查找。找不到返回 null。 */
    fun find(name: String): Tool? = byName[name]

    /** 按名查找，找不到抛异常（用于「这个工具必须存在」的断言场景）。 */
    fun require(name: String): Tool =
        byName[name] ?: throw IllegalStateException("Tool not found: $name")

    fun contains(name: String): Boolean = byName.containsKey(name)

    /** 清空（测试用）。 */
    @Synchronized
    fun clear() {
        tools.clear()
        byName.clear()
    }

    /**
     * 按 [Tool.isReadOnly] 过滤。
     * Agent 循环用它判断「能不能在流式响应收完前提前启动」。
     */
    fun readOnlyTools(): List<Tool> = tools.filter { it.isReadOnly }

    /**
     * 工具名清单（给提示词里列举可用工具用）。
     * 排序保证提示词稳定 —— 顺序抖动会让 prompt 缓存失效。
     */
    fun names(): List<String> = tools.map { it.name }.sorted()
}
