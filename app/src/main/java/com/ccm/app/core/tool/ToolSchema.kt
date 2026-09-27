package com.ccm.app.core.tool

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 工具 JSON Schema 的构造与规范化。
 *
 * ## 为什么需要 normalizeToolSchema（这是血泪教训）
 *
 * 有个真实的 400 报错：
 * ```
 * HTTP 400 {"code":"invalid-argument","error":"Invalid request content:
 *   Schema validation failed: [standard_violation]
 *   /properties: null is not of type \"object\""}
 * ```
 *
 * **根因**：工具 schema 写了 `type: "object"` 但**没有 `properties` 字段**。
 * OpenAI / Anthropic 对无参工具的这种写法都照收，所以这种写法能长期存在、从不报错；
 * 一旦请求落到 **Gemini 后端**（很多中转站后端其实是 Gemini，哪怕 model 名写着别的）
 * 就直接判违规。
 *
 * **致命特征**：一个工具坏 → **整个请求被拒**，且必然发生在 **turn 1**
 * （首轮就带全量工具列表），与用户问什么完全无关
 * → 表现为「这个配置什么都干不了」。
 *
 * **所以**：跨 provider 的兼容问题要在**序列化出口处统一兜底**，
 * 而不是指望每个工具定义都写对 —— 定义点会不断新增（尤其第三方 MCP server
 * 返回什么 schema 完全不可控），出口只有一个。
 *
 * Node 版对应实现：`core/api.mjs` 的 `normalizeToolSchema()`。
 */
object ToolSchema {

    /**
     * 规范化单个工具的 schema，保证是合法 object schema。
     *
     * 修三件事：
     * 1. 缺 `type` → 补 `"object"`
     * 2. `type == "object"` 但 `properties` 缺失/为 null/是数组或标量 → 补空对象
     * 3. `required` 引用 `properties` 里不存在的键 → 剔除（Gemini 同样报违规）；
     *    剔完为空则整个删掉 `required`
     *
     * 非 object 类型的 schema 原样返回（如某些 MCP 工具用 `type: "string"`）。
     *
     * @return 规范化后的 schema。**保证非 null 且 `properties` 合法**。
     */
    fun normalizeToolSchema(schema: JsonElement?): JsonObject {
        // 整体缺失 / 不是对象 / 是数组 → 给最小合法 object schema
        if (schema == null || schema is JsonNull) return emptyObjectSchema()
        if (schema !is JsonObject) return emptyObjectSchema()

        val out = LinkedHashMap<String, JsonElement>(schema)

        // 1. 补 type
        val typePrim = out["type"] as? JsonPrimitive
        val type = if (typePrim != null && typePrim.isString) typePrim.content else null
        if (type == null) out["type"] = JsonPrimitive("object")

        val effectiveType = type ?: "object"
        if (effectiveType == "object") {
            // 2. 补 properties（关键！就是这一步防住 Gemini 400）
            val props = out["properties"]
            val propsOk = props is JsonObject
            if (!propsOk) out["properties"] = JsonObject(emptyMap())

            // 3. 清理 required
            val required = out["required"]
            if (required != null) {
                val keys = (out["properties"] as? JsonObject)?.keys ?: emptySet()
                val filtered = if (required is JsonArray) {
                    required.filterIsInstance<JsonPrimitive>()
                        .filter { it.isString && keys.contains(it.content) }
                } else {
                    emptyList()
                }
                if (filtered.isEmpty()) out.remove("required")
                else out["required"] = JsonArray(filtered)
            }
        }
        return JsonObject(out)
    }

    /** 规范化整个工具列表的 schema。出口兜底用。 */
    fun normalizeAll(schemas: List<JsonObject>): List<JsonObject> =
        schemas.map { normalizeToolSchema(it) }

    /** 最小合法 object schema：`{"type":"object","properties":{}}` */
    fun emptyObjectSchema(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", JsonObject(emptyMap()))
    }

    // ───────────────────────── 构造助手 ─────────────────────────
    //
    // 用这些构造 schema 就不会踩「漏写 properties」的坑。
    // 手写 JsonObject 也行（出口有兜底），但助手更不容易忘。

    /**
     * 构造 object schema。
     *
     * ```
     * val schema = ToolSchema.objectSchema(
     *     "path" to ToolSchema.string("要读取的文件路径"),
     *     "offset" to ToolSchema.integer("起始行号"),
     * ) { listOf("path") }   // required
     * ```
     */
    fun objectSchema(
        vararg properties: Pair<String, JsonElement>,
        required: List<String> = emptyList(),
    ): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", JsonObject(properties.toMap()))
        if (required.isNotEmpty()) put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }

    /** 无参工具的 schema（`properties: {}` 不能省）。 */
    fun noArgsSchema(): JsonObject = emptyObjectSchema()

    /** 字符串参数。 */
    fun string(description: String, enum: List<String>? = null): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("description", JsonPrimitive(description))
        if (!enum.isNullOrEmpty()) put("enum", JsonArray(enum.map { JsonPrimitive(it) }))
    }

    /** 整数参数。 */
    fun integer(description: String, minimum: Int? = null, maximum: Int? = null): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("integer"))
            put("description", JsonPrimitive(description))
            if (minimum != null) put("minimum", JsonPrimitive(minimum))
            if (maximum != null) put("maximum", JsonPrimitive(maximum))
        }

    /** 布尔参数。 */
    fun boolean(description: String, default: Boolean? = null): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("boolean"))
        put("description", JsonPrimitive(description))
        if (default != null) put("default", JsonPrimitive(default))
    }

    /** 字符串数组参数。 */
    fun stringArray(description: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("array"))
        put("description", JsonPrimitive(description))
        put("items", string("元素"))
    }

    // ───────────────────────── 取值助手 ─────────────────────────
    //
    // 模型给的参数经常缺字段/类型不对，用这些取值不会抛异常。

    /** 取字符串参数，缺失或类型不对返回 null。 */
    fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** 取字符串参数，缺失时用 [default]。 */
    fun JsonObject.str(key: String, default: String): String = str(key) ?: default

    /** 取整数参数（容忍模型给字符串数字，如 `"10"`）。 */
    fun JsonObject.int(key: String): Int? {
        val p = this[key] as? JsonPrimitive ?: return null
        if (!p.isString) return null
        return p.content.trim().toIntOrNull()
    }

    /** 取布尔参数（容忍字符串 `"true"`）。 */
    fun JsonObject.bool(key: String): Boolean? {
        val p = this[key] as? JsonPrimitive ?: return null
        if (!p.isString) return null
        return when (p.content.trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    /** 取字符串数组参数（单元素也接受裸字符串）。 */
    fun JsonObject.strList(key: String): List<String>? {
        val el = this[key] ?: return null
        return when (el) {
            is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            is JsonPrimitive -> if (el.isString) listOf(el.content) else null
            else -> null
        }
    }

    /** 取嵌套对象参数。 */
    fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    /** 安全取值：任意 JsonElement → 字符串（用于 debug 输出）。 */
    fun JsonElement?.asTextOrNull(): String? = when (this) {
        null, is JsonNull -> null
        is JsonPrimitive -> if (isString) content else content
        else -> toString()
    }
}
