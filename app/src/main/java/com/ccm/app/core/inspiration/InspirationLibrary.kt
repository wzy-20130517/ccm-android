package com.ccm.app.core.inspiration

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 首页灵感库 —— 数据 = Web 的 `web/src/data/inspirations.json` 原样拷贝
 * （`app/src/main/assets/inspirations.json`，2026-09-28 第17批）。
 *
 * ## 为什么整份拷贝而不是手写子集
 * Web 端 `pickInspirations(names)` 是按 name 从 42 条库里查（MainContent.tsx:76），
 * starting_prompt 最长 1700+ 字。手抄 25 条必然抄漏/抄错，
 * 整份拷贝保证两端 prompt 一字不差（以后同步也只用覆盖一个文件）。
 *
 * ## 分组名单为什么不放这
 * 「哪 5 个名字属于哪个分类」是 Web `LANDING_PROMPT_SECTIONS` 的**硬编码**，
 * 不在 JSON 里（JSON 的 category 是另一套分类法）。名单在 PromptSection 枚举。
 */
@Serializable
data class InspirationItem(
    val name: String = "",
    val description: String = "",
    val starting_prompt: String = "",
)

@Serializable
private data class InspirationsFile(val items: List<InspirationItem> = emptyList())

object InspirationLibrary {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile
    private var cache: Map<String, InspirationItem>? = null

    /** 进程内懒加载（LandingScreen 首次组合时调；重复调走缓存）。 */
    fun ensure(context: Context): Map<String, InspirationItem> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val parsed = try {
                context.assets.open("inspirations.json")
                    .bufferedReader(Charsets.UTF_8)
                    .use { json.decodeFromStream(InspirationsFile.serializer(), it) }
                    .items
                    .associateBy { it.name }
            } catch (_: Throwable) {
                emptyMap()
            }
            cache = parsed
            return parsed
        }
    }

    /** 按名字取（Web pickInspirations 的等价物）。 */
    fun get(context: Context, name: String): InspirationItem? = ensure(context)[name]
}
