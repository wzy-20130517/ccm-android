package com.ccm.app.core.integrations

import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * QQ 桥配置（APK 端）—— 对齐 CLI core/integrations/qq-config.mjs 的结构。
 *
 * ═══════════════════════════════════════════════════════════════
 * 【文件结构】files/qq-config.json
 *
 *     {
 *       "owner": "123456789",           // 共用：一个 QQ 账号
 *       "napcatApi": "http://...",     // 共用：NapCat 地址
 *       "port": 3000,                  // 共用：同一时间只允许一个端监听
 *       "openMode": false,             // 放行模式（群内任何人 @ 都能唤醒）
 *       "interrupt": false,            // 任意新消息都打断
 *       "endpoints": {
 *         "cli": { "enabled": true },
 *         "web": { "enabled": false },
 *         "ccm": { "enabled": false }   // ← APK 看这个
 *       }
 *     }
 *
 * · **开关按端分**：APK 只看 endpoints.ccm.enabled。CLI 的 on 不会让
 *   APK 也自动开（否则用户在 CLI 开的桥会被 APK 抢端口）。
 * · **端口共用**：proot 与 Termux 共享网络命名空间，只允许一个端监听。
 * · 文件与 CLI 的 ~/.claude-code-mobile/qq-config.json **不是同一份**
 *   （APK 是独立沙箱，files/ 目录）—— 想从 CLI 迁移配置需手动拷。
 */
object QqConfigStore {

    private const val TAG = "QqConfig"
    private const val FILE_NAME = "qq-config.json"

    /** 默认值（对齐 CLI 常量）。 */
    const val DEFAULT_PORT = 3000
    const val DEFAULT_OWNER = ""
    const val DEFAULT_NAPCAT_API = "http://127.0.0.1:5700"

    data class Cfg(
        val owner: String = DEFAULT_OWNER,
        val napcatApi: String = DEFAULT_NAPCAT_API,
        val port: Int = DEFAULT_PORT,
        val openMode: Boolean = false,
        val interrupt: Boolean = false,
        /** APK 端自己的开关（endpoints.ccm.enabled）。 */
        val ccmEnabled: Boolean = false,
    )

    private fun file(filesDir: File) = File(filesDir, FILE_NAME)

    fun load(filesDir: File): Cfg {
        return try {
            val f = file(filesDir)
            if (!f.exists()) return Cfg()
            val o = JSONObject(f.readText())
            Cfg(
                owner = o.optString("owner", DEFAULT_OWNER),
                napcatApi = o.optString("napcatApi", DEFAULT_NAPCAT_API),
                port = o.optInt("port", DEFAULT_PORT),
                openMode = o.optBoolean("openMode", false),
                interrupt = o.optBoolean("interrupt", false),
                ccmEnabled = o.optJSONObject("endpoints")
                    ?.optJSONObject("ccm")
                    ?.optBoolean("enabled", false) ?: false,
            )
        } catch (e: Throwable) {
            Log.w(TAG, "读取失败: ${e.message?.take(80)}")
            Cfg()
        }
    }

    /**
     * 保存（读-改-写，保留其他端的字段）。
     *
     * @param patch 只传要改的字段
     */
    fun save(
        filesDir: File,
        owner: String? = null,
        napcatApi: String? = null,
        port: Int? = null,
        openMode: Boolean? = null,
        interrupt: Boolean? = null,
        ccmEnabled: Boolean? = null,
    ): Boolean {
        return try {
            val f = file(filesDir)
            val o = if (f.exists()) {
                try { JSONObject(f.readText()) } catch (_: Throwable) { JSONObject() }
            } else JSONObject()

            owner?.let { o.put("owner", it) }
            napcatApi?.let { o.put("napcatApi", it) }
            port?.let { o.put("port", it) }
            openMode?.let { o.put("openMode", it) }
            interrupt?.let { o.put("interrupt", it) }
            ccmEnabled?.let {
                val eps = o.optJSONObject("endpoints") ?: JSONObject().also { e -> o.put("endpoints", e) }
                val ccm = eps.optJSONObject("ccm") ?: JSONObject().also { e -> eps.put("ccm", e) }
                ccm.put("enabled", it)
            }

            f.parentFile?.mkdirs()
            f.writeText(o.toString(2))
            true
        } catch (e: Throwable) {
            Log.w(TAG, "保存失败: ${e.message?.take(80)}")
            false
        }
    }
}
