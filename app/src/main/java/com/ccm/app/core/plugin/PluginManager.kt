package com.ccm.app.core.plugin

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 插件管理 HTTP 客户端 —— 调 dsh-host 的控制 API。
 *
 * 宿主由 [DshHostManager] 部署/启动，本类只管消费
 * `http://127.0.0.1:8790` 的 `/control/...` 这组接口。
 *
 * 所有方法都要求宿主已启动（先调 DshHostManager.start()），
 * 否则 IOException —— 调用方负责把「宿主没起」翻译成用户能看懂的提示。
 */
object PluginManager {

    private const val TAG = "PluginManager"

    // ═══ 数据类 ═══

    /** 插件运行状态（/control/status 的 pluginStates 条目） */
    data class PluginState(val name: String, val state: Int) {
        val active: Boolean get() = state == 2
    }

    /** 宿主状态总览 */
    data class HostStatus(
        val loaded: List<String>,
        val states: List<PluginState>,
        val serviceCount: Int,
        val failedServices: List<String>,
        val providerCount: Int,
    )

    /** Provider 条目（APK 可接入的 LLM 通道） */
    data class Provider(
        val id: String,
        val name: String,
        val baseUrl: String,
        val apiKey: String,
        val ready: Boolean,
        val models: List<String>,
    )

    /** 可安装的插件包 */
    data class Bundle(
        val name: String,
        val description: String = "",
        val installed: Boolean = false,
    )

    /** 操作结果 */
    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        data class Err(val message: String) : Result<Nothing>
    }

    // ═══ 基础请求 ═══

    private fun call(
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
        readTimeoutMs: Int = 30_000,
    ): JSONObject {
        val conn = URL("${DshHostManager.BASE_URL}$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 5_000
            conn.readTimeout = readTimeoutMs
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (text.isBlank()) throw IOException("HTTP $code（空响应）")
            val json = JSONObject(text)
            // 服务端约定：{ ok: false, error: ... } 走 200/500 两种形式都可能
            if (json.optBoolean("ok", true).not()) {
                throw IOException(json.optString("error", "操作失败"))
            }
            return json
        } finally {
            conn.disconnect()
        }
    }

    private inline fun <T> runApi(
        what: String,
        crossinline block: () -> T,
    ): Result<T> = try {
        Result.Ok(block())
    } catch (e: IOException) {
        Log.w(TAG, "$what 失败", e)
        Result.Err(e.message ?: "网络错误")
    } catch (e: Exception) {
        Log.e(TAG, "$what 异常", e)
        Result.Err("${e.javaClass.simpleName}: ${e.message}")
    }

    // ═══ 状态 ═══

    /** 宿主状态（插件列表 + 服务 + provider 数） */
    suspend fun status(): Result<HostStatus> = withContext(Dispatchers.IO) {
        runApi("status") {
            val json = call("/control/status")
            val states = json.optJSONObject("pluginStates")
            HostStatus(
                loaded = json.optJSONArray("plugins").toStringList(),
                states = states?.let { obj ->
                    obj.keys().asSequence().map { PluginState(it, obj.optInt(it)) }.toList()
                } ?: emptyList(),
                serviceCount = json.optJSONObject("services")?.optInt("count") ?: 0,
                failedServices = json.optJSONObject("services")
                    ?.optJSONArray("failed").toStringList(),
                providerCount = json.optJSONArray("providers").length(),
            )
        }
    }

    /** Provider 清单（含 CCM 接入地址） */
    suspend fun providers(): Result<List<Provider>> = withContext(Dispatchers.IO) {
        runApi("providers") {
            val arr = call("/control/providers").optJSONArray("providers")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Provider(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    baseUrl = o.optString("ccmBaseUrl"),
                    apiKey = o.optString("ccmApiKey"),
                    ready = o.optBoolean("ready"),
                    models = o.optJSONArray("models").toStringList(),
                )
            }
        }
    }

    // ═══ 插件包管理 ═══

    /** 可安装包列表 */
    suspend fun bundles(): Result<List<Bundle>> = withContext(Dispatchers.IO) {
        runApi("bundles") {
            val arr = call("/control/bundles").optJSONArray("bundles")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Bundle(
                    name = o.optString("name"),
                    description = o.optString("description"),
                    installed = o.optBoolean("installed"),
                )
            }
        }
    }

    /**
     * 安装插件包。
     *
     * @param spec 包名（dsh-find-plugin 认的规格，如 "dsh-goal"）
     * @param config 可选的初始配置（JSON）
     */
    suspend fun install(spec: String, config: JSONObject? = null): Result<String> =
        withContext(Dispatchers.IO) {
            runApi("install $spec") {
                val body = JSONObject().put("spec", spec)
                if (config != null) body.put("config", config)
                // 180s：宿主内部要跑 npm install（对齐 CLI 工具的 timeout）
                val json = call("/control/install", "POST", body, readTimeoutMs = 180_000)
                json.optString("installed", spec)
            }
        }

    /** 卸载插件包 */
    suspend fun remove(name: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runApi("remove $name") {
            val body = JSONObject().put("name", name)
            call("/control/remove", "POST", body).optBoolean("ok")
        }
    }

    /** 启/停一个已安装插件 */
    suspend fun setPlugin(name: String, enabled: Boolean): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runApi("setPlugin $name") {
                val body = JSONObject().put("name", name).put("enabled", enabled)
                call("/control/set-plugin", "POST", body).optBoolean("ok")
            }
        }

    /** 加载插件实例 */
    suspend fun load(spec: String, config: JSONObject? = null): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runApi("load $spec") {
                val body = JSONObject().put("spec", spec)
                if (config != null) body.put("config", config)
                call("/control/load", "POST", body).optBoolean("ok")
            }
        }

    /** 卸载运行中的插件实例 */
    suspend fun unload(spec: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runApi("unload $spec") {
            val body = JSONObject().put("spec", spec)
            call("/control/unload", "POST", body).optBoolean("ok")
        }
    }

    // ═══ 工具 ═══

    private fun org.json.JSONArray?.toStringList(): List<String> =
        this?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList()
}
