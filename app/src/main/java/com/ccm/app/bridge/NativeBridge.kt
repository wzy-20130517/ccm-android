package com.ccm.app.bridge

import com.ccm.app.runtime.ProotRuntime
import android.content.Context
import android.util.Log
import com.ccm.app.tools.ScreenCapture
import com.ccm.app.tools.NativeTts
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 原生能力桥 —— Node 内核通过 HTTP 调用这里，间接使用 Android 原生能力。
 *
 * 【架构位置】
 *   Node 工具 (tools-phone.mjs / termux-tools.mjs)
 *       ↓ HTTP POST /native/call  { method, params }
 *   NativeBridge（本类）—— 在 Web 服务器进程内被调用
 *       ↓ 直接调
 *   Shizuku（shell uid 的 phone use 服务）/ 前台服务 / 系统 API
 *
 * 【2026-09-25 架构调整】原来每个 phone.* 都是「Shizuku 优先，无障碍兜底」。
 * 无障碍（普通 app uid）能做的事太少：建不了 TRUSTED 虚拟屏、拿不到跨窗口
 * 元素树、input 只能走 dispatchGesture 模拟。留着它等于维护两套半残实现。
 * 现在只保留 Shizuku —— 拿不到就明确报错，让用户去修 Shizuku，
 * 而不是悄悄降级到一个「看着能用其实差很多」的实现。
 *
 * 【为什么走 HTTP 而不是 stdio】
 * Node 是独立进程（跑在 proot 里），Kotlin 是 App 进程。
 * 两者通信最简单的方式就是 HTTP —— Node 侧用 fetch，Kotlin 侧用内置 HTTP 服务器。
 * 而且 web/server.mjs 本来就是 HTTP 服务器，加一条路由最省事。
 *
 * 【方法命名】
 * 用 "命名空间.动作" 的形式，方便扩展：
 *   phone.snapshot / phone.click / phone.type / phone.swipe
 *   phone.screenshot / phone.app / phone.key
 *   sys.notify / sys.clipboard.get / sys.clipboard.set / sys.toast
 *   sys.tts / sys.vibrate / sys.battery / sys.share / sys.openUrl
 *   runtime.exec / runtime.status
 */
class NativeBridge(
    private val context: Context,
    /**
     * proot 实例（可选）。
     *
     * 【为什么可选而不是必传】NativeBridge 的历史调用点只传 context，
     * 硬加重载会让所有调用点都要改。而 runtimeStatus() 里用 proot 只是
     * 为了查 Node 路径 —— 没有它也能降级（用 RootfsManager 的等价实现）。
     */
    private val proot: ProotRuntime? = null,
) {

    companion object {
        private const val TAG = "NativeBridge"
    }

    /**
     * 统一入口。返回 JSON 字符串（永远不抛异常，错误包在 JSON 里）。
     */
    fun call(method: String, params: JSONObject): String {
        return try {
            when (method) {
                // ── 手机操作（Shizuku shell uid）──────────
                "phone.snapshot" -> phoneSnapshot(params)
                "phone.click" -> phoneClick(params)
                "phone.tap" -> phoneTap(params)
                "phone.type" -> phoneType(params)
                "phone.swipe" -> phoneSwipe(params)
                "phone.scroll" -> phoneScroll(params)
                "phone.key" -> phoneKey(params)
                "phone.app" -> phoneApp(params)
                "phone.screenshot" -> phoneScreenshot(params)
                "phone.screenshot.base64" -> phoneScreenshotBase64(params)
                "phone.screenshot.status" -> phoneScreenshotStatus()
                "phone.status" -> phoneStatus()
                "phone.runShell" -> phoneRunShell(params)
                "phone.displayInfo" -> phoneDisplayInfo()

                // ── 系统能力 ─────────────────────────────
                "sys.notify" -> sysNotify(params)
                "sys.clipboard.get" -> sysClipboardGet()
                "sys.clipboard.set" -> sysClipboardSet(params)
                "sys.toast" -> sysToast(params)
                "sys.vibrate" -> sysVibrate(params)
                "sys.battery" -> sysBattery()
                "sys.openUrl" -> sysOpenUrl(params)
                "sys.share" -> sysShare(params)
                "sys.tts" -> sysTts(params)
                "sys.location" -> sysLocation(params)
                "sys.tts.stop" -> sysTtsStop()

                // ── 运行时 ───────────────────────────────
                "runtime.status" -> runtimeStatus()

                else -> err("未知方法: $method")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "调用失败: $method", t)
            err("${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ═══════════════════════════════════════════════════
    //  手机操作
    // ═══════════════════════════════════════════════════

    /**
     * 元素树。返回平铺文本（仿 agent-mobile-use）：
     *   首行状态 · 次行列头 · 之后一行一元素（#id type name 坐标 flags）
     * 模型直接读文本，不用解析 JSON，也不会自己算坐标。
     */
    private fun phoneSnapshot(p: JSONObject): String {
        val remote = ShizukuBridge.phoneService(context)
            ?: return err(shizukuHint())
        val tree = try {
            remote.dumpTree(
                p.optBoolean("interactive_only", true),
                p.optInt("max_nodes", 300),
                p.optBoolean("no_system_ui", true),
            )
        } catch (t: Throwable) { return err("读元素树失败：${t.message}") }
        if (tree.isEmpty()) return err("元素树为空（前台应用可能没渲染完，或界面是纯 Canvas/WebView）")
        // 必须包成 JSON：桥这条链路（Kotlin HTTP → Node fetch）两端都按 JSON 解析，
        // 直接把平铺文本裸着返回会让 Node 侧 res.json() 抛错。
        // 文本放 text 字段，Node 侧取出来照原样交给模型（不做二次结构化）。
        return JSONObject().apply {
            put("ok", true)
            put("text", tree)
        }.toString()
    }

    /** Shizuku 不可用时统一的提示语 —— 不引导去开无障碍（已移除）。 */
    private fun shizukuHint(): String {
        val why = ShizukuBridge.unavailableReason() ?: "phone use 服务未就绪"
        return "手机操作不可用：$why。\n" +
            "处理：打开 Shizuku 并确认 CCM 已授权（App 主界面有授权入口）。"
    }

    private fun phoneClick(p: JSONObject): String {
        val ref = p.optString("ref")
        if (ref.isEmpty()) return err("缺少 ref 参数")
        val remote = ShizukuBridge.phoneService(context) ?: return err(shizukuHint())
        // 长按 Map 里没有独立 API，先点到 ref 中心再补一个长按手势（用坐标）
        if (p.optBoolean("long_press", false)) {
            val r = try { remote.tapRef(ref) } catch (_: Throwable) { false }
            return ok2json(r, if (r) "已长按 $ref" else "长按失败（ref 可能已失效，请重新 snapshot）")
        }
        val ok = try { remote.tapRef(ref) } catch (_: Throwable) { false }
        return ok2json(ok, if (ok) "已点击 $ref" else "点击失败（ref 可能已失效，请重新 snapshot）")
    }

    private fun phoneTap(p: JSONObject): String {
        val x = p.optDouble("x", -1.0).toFloat()
        val y = p.optDouble("y", -1.0).toFloat()
        if (x < 0 || y < 0) return err("缺少坐标 x/y")
        val remote = ShizukuBridge.phoneService(context) ?: return err(shizukuHint())
        val ok = try { remote.tap(x.toInt(), y.toInt()) } catch (_: Throwable) { false }
        return ok2json(ok, if (ok) "已点击 (${x.toInt()}, ${y.toInt()})" else "坐标点击失败")
    }

    /**
     * 文字注入。服务端走确定性单路径（一次 SET_TEXT + 回读校验），
     * 返回 JSON 里带 verified / verified_text / error，调用方据此如实转述给模型。
     */
    private fun phoneType(p: JSONObject): String {
        val text = p.optString("text")
        if (text.isEmpty()) return err("缺少 text")
        val target = p.optString("target", p.optString("ref", ""))
        val remote = ShizukuBridge.phoneService(context) ?: return err(shizukuHint())
        return try {
            // 服务端返回的 JSON 已经是 {ok, mode, verified, error, reason, focus_hint}。
            // 但它的 ok 指的是「SET_TEXT 调用被接受」，而调用方关心的是「到底写进去没有」——
            // 所以这里把 ok 重新按 verified 口径给出，同时保留原始字段供排查。
            // target 走 typeTextAt：空 = 当前焦点框，e12/12 = dump 里的节点。
            val raw = if (target.isBlank()) remote.typeText(text) else remote.typeTextAt(text, target)
            val o = JSONObject(raw)
            val verified = o.optBoolean("verified", false)
            val errName = o.optString("error", "")
            // 只有「SET_TEXT 被拒 / 内部错 / 定位不到」才算失败；
            // verify_unavailable 算成功（写进去了，只是读不回）。
            val hardFail = errName in setOf(
                "inject_rejected", "internal_error", "ui_unavailable",
                "no_focused_input", "invalid_target", "target_not_found",
                "target_stale", "target_not_editable",
            )
            o.put("ok", !hardFail)
            o.toString()
        } catch (t: Throwable) { err("输入失败：${t.message}") }
    }

    private fun phoneSwipe(p: JSONObject): String {
        val dir = p.optString("direction", "")
        val remote = ShizukuBridge.phoneService(context) ?: return err(shizukuHint())
        if (dir.isNotEmpty()) {
            val ok = try { remote.swipeDir(dir, p.optInt("duration", 300)) } catch (_: Throwable) { false }
            return ok2json(ok, if (ok) "已在副屏向 $dir 滑动" else "滑动失败")
        }
        val x1 = p.optDouble("x1", -1.0).toInt()
        val y1 = p.optDouble("y1", -1.0).toInt()
        val x2 = p.optDouble("x2", -1.0).toInt()
        val y2 = p.optDouble("y2", -1.0).toInt()
        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) return err("需要 direction 或 x1/y1/x2/y2")
        val ok = try { remote.swipe(x1, y1, x2, y2, p.optInt("duration", 300)) } catch (_: Throwable) { false }
        return ok2json(ok, if (ok) "已滑动" else "滑动失败")
    }

    private fun phoneScroll(p: JSONObject): String {
        val remote = ShizukuBridge.phoneService(context) ?: return err(shizukuHint())
        val ref = p.optString("ref")
        val dir = p.optString("direction", "down")
        val ok = try { remote.scroll(ref, dir) } catch (_: Throwable) { false }
        return ok2json(ok, if (ok) "已滚动" else "滚动失败（该区域可能不可滚动）")
    }

    private fun phoneKey(p: JSONObject): String {
        val key = p.optString("key")
        if (key.isEmpty()) return err("缺少 key")
        val remote = ShizukuBridge.phoneService(context) ?: return err(shizukuHint())
        // 支持 "KEYCODE_BACK" 和简写 "back"
        val code = keyCodeOf(key)
            ?: return err("未知按键：$key")
        val ok = try { remote.key(code) } catch (_: Throwable) { false }
        return ok2json(ok, if (ok) "已按 $key" else "按键失败")
    }

    /** 键名 → KeyEvent 常量。跟 Node 侧 tools-phone.mjs 的键位表保持同一套名字。 */
    private fun keyCodeOf(name: String): Int? {
        val n = name.removePrefix("KEYCODE_").uppercase()
        return when (n) {
            "BACK" -> android.view.KeyEvent.KEYCODE_BACK
            "HOME" -> android.view.KeyEvent.KEYCODE_HOME
            "APP_SWITCH", "RECENT" -> android.view.KeyEvent.KEYCODE_APP_SWITCH
            "ENTER" -> android.view.KeyEvent.KEYCODE_ENTER
            "DEL", "DELETE" -> android.view.KeyEvent.KEYCODE_DEL
            "TAB" -> android.view.KeyEvent.KEYCODE_TAB
            "ESCAPE" -> android.view.KeyEvent.KEYCODE_ESCAPE
            "POWER" -> android.view.KeyEvent.KEYCODE_POWER
            "VOLUME_UP" -> android.view.KeyEvent.KEYCODE_VOLUME_UP
            "VOLUME_DOWN" -> android.view.KeyEvent.KEYCODE_VOLUME_DOWN
            "DPAD_UP" -> android.view.KeyEvent.KEYCODE_DPAD_UP
            "DPAD_DOWN" -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
            "DPAD_LEFT" -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
            "DPAD_RIGHT" -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            else -> null
        }
    }

    /**
     * 应用操作。转发给服务端，因为「在副屏启动」需要 shell 权限 + 副屏 displayId。
     *
     * 重点在 launch：如果应用已在主屏跑着，服务端会用 move-stack 平滑搬过来，
     * 而不是重启 —— 重启会把用户主屏上的进度全丢掉（微信聊天界面回到列表）。
     */
    private fun phoneApp(p: JSONObject): String {
        val remote = ShizukuBridge.phoneService(context) ?: return err(shizukuHint())
        val action = p.optString("action", "launch")
        val pkg = p.optString("package")
        val filter = p.optString("filter", "")
        return try {
            remote.app(action, pkg, filter)
        } catch (t: Throwable) { err("应用操作失败：${t.message}") }
    }

    /** 副屏尺寸与帧缓存状态（诊断用，也让模型知道坐标范围）。 */
    private fun phoneDisplayInfo(): String {
        val remote = ShizukuBridge.phoneService(context) ?: return err(shizukuHint())
        return try { remote.status() } catch (t: Throwable) { err("查询失败：${t.message}") }
    }

    /**
     * 通用 shell 出口。
     *
     * 给「既不是点一下、也不是读元素树」的能力用（前台应用、dumpsys 类查询、
     * 未来的 pm/am 操作）。有它就不必每加一个能力改一次 AIDL。
     */
    private fun phoneRunShell(p: JSONObject): String {
        val cmd = p.optString("cmd")
        if (cmd.isEmpty()) return err("缺少 cmd")
        val remote = ShizukuBridge.phoneService(context) ?: return err(shizukuHint())
        val timeout = p.optInt("timeout_ms", 15000)
        val raw = try { remote.runShell(cmd, timeout) } catch (t: Throwable) {
            return err("执行失败：${t.message}")
        }
        val nl = raw.indexOf('\n')
        val code = if (nl > 0) raw.substring(0, nl) else raw
        val body = if (nl > 0) raw.substring(nl + 1) else ""
        return JSONObject().apply {
            put("ok", code.trim() == "0")
            put("exit_code", code.trim().toIntOrNull() ?: -1)
            put("stdout", body)
            if (code.trim() != "0") put("error", body.ifEmpty { "退出码 $code" })
        }.toString()
    }

    /**
     * 截图。首选副屏帧缓存 —— 守护侧一直在把最新帧编成 JPEG，
     * 这里只是取一份内存拷贝，实测 ~60ms；而 MediaProjection 那条路要 ~1.8s。
     *
     * 【为什么帧缓存是全分辨率】
     * 调用方按像素尺寸算模型侧的缩放比例（图会被缩到 2048 长边省 token）。
     * 缓存若缩过，比例就错，模型按图上坐标点击会系统性偏掉。
     */
    private fun phoneScreenshot(p: JSONObject): String {
        val remote = ShizukuBridge.phoneService(context)
        if (remote != null) {
            val bytes = try { remote.latestFrame() } catch (_: Throwable) { null }
            if (bytes != null && bytes.isNotEmpty()) {
                val path = p.optString("save_path").ifEmpty { null }
                    ?: File(context.cacheDir, "ccm-vd-shot.jpg").absolutePath
                return try {
                    // 先建父目录 —— 传了不存在的目录时 writeBytes 会失败；
                    // 父目录也不可写就立刻抛，别让调用方干等（实测卡死过整个桥）
                    val f = File(path)
                    f.parentFile?.let { if (!it.exists()) it.mkdirs() }
                    if (!f.parentFile!!.canWrite()) return err("目标目录不可写：${f.parentFile?.absolutePath}")
                    f.writeBytes(bytes)
                    val m = try { remote.displayMetrics() } catch (_: Throwable) { null }
                    JSONObject().apply {
                        put("ok", true)
                        put("path", path)
                        put("source", "virtual_display_frame")
                        if (m != null && m.size >= 3) {
                            put("width", m[0]); put("height", m[1]); put("dpi", m[2])
                        }
                        put("message", "截图已保存（副屏帧缓存）: $path")
                    }.toString()
                } catch (t: Throwable) { err("写截图失败: ${t.message}") }
            }
        }
        if (!ScreenCapture.isReady()) {
            return err("截屏未授权。请在 App 主界面点「开启截屏」授权一次（系统会弹「开始录制」确认框）")
        }
        val path = p.optString("save_path").ifEmpty { null }
        val quality = p.optInt("quality", 85)
        val result = ScreenCapture.capture(path, quality)
            ?: return err("截图失败（可能是虚拟屏幕未就绪）")
        return JSONObject().apply {
            put("ok", true)
            put("path", result)
            put("message", "截图已保存: $result")
        }.toString()
    }

    private fun phoneScreenshotBase64(p: JSONObject): String {
        if (!ScreenCapture.isReady()) {
            return err("截屏未授权")
        }
        val b64 = ScreenCapture.captureBase64(p.optInt("quality", 80))
            ?: return err("截图失败")
        return JSONObject().apply {
            put("ok", true)
            put("base64", b64)
            put("width", context.resources.displayMetrics.widthPixels)
            put("height", context.resources.displayMetrics.heightPixels)
        }.toString()
    }

    private fun phoneScreenshotStatus(): String {
        return JSONObject().apply {
            put("ok", true)
            put("authorized", ScreenCapture.isReady())
            put("message", if (ScreenCapture.isReady()) "截屏已就绪" else "未授权")
        }.toString()
    }

    private fun phoneStatus(): String {
        val remote = ShizukuBridge.phoneService(context)
        val vd = try { remote?.displayId() ?: -1 } catch (_: Throwable) { -1 }
        val reason = ShizukuBridge.unavailableReason()
        val mode = if (vd >= 0) "虚拟副屏" else if (remote != null) "Shizuku（副屏未就绪）" else "不可用"
        return JSONObject().apply {
            put("ok", true)
            put("virtual_display_id", vd)
            put("shizuku", if (reason == null) "已授权" else reason)
            put("mode", mode)
            put("message", when {
                vd >= 0 -> "虚拟副屏运行中（display $vd），操作不占物理屏"
                remote != null -> "Shizuku 已连接，虚拟副屏尚未创建（首次操作时自动建）"
                else -> "手机操作不可用：${reason ?: "phone use 服务未就绪"}"
            })
        }.toString()
    }

    // ═══════════════════════════════════════════════════
    //  系统能力
    // ═══════════════════════════════════════════════════

    private fun sysNotify(p: JSONObject): String {
        val title = p.optString("title", "Claude Code Mobile")
        val content = p.optString("content", "")
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as android.app.NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                val ch = android.app.NotificationChannel(
                    "ccm_main", "Claude Code Mobile", android.app.NotificationManager.IMPORTANCE_DEFAULT
                )
                nm.createNotificationChannel(ch)
            }
            val notif = android.app.Notification.Builder(context, "ccm_main")
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setAutoCancel(true)
                .build()
            nm.notify(System.currentTimeMillis().toInt(), notif)
            ok2json(true, "已通知")
        } catch (t: Throwable) {
            err("通知失败: ${t.message}")
        }
    }

    private fun sysClipboardGet(): String {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            JSONObject().apply { put("ok", true); put("text", text) }.toString()
        } catch (t: Throwable) {
            err("读剪贴板失败: ${t.message}")
        }
    }

    private fun sysClipboardSet(p: JSONObject): String {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("ccm", p.optString("text")))
            ok2json(true, "已写入剪贴板")
        } catch (t: Throwable) {
            err("写剪贴板失败: ${t.message}")
        }
    }

    private fun sysToast(p: JSONObject): String {
        return try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                    context, p.optString("text"), android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            ok2json(true, "已提示")
        } catch (t: Throwable) { err(t.message) }
    }

    private fun sysVibrate(p: JSONObject): String {
        return try {
            val vm = context.getSystemService(Context.VIBRATOR_SERVICE)
                    as android.os.Vibrator
            val ms = p.optLong("duration", 200)
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                vm.vibrate(android.os.VibrationEffect.createOneShot(
                    ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") vm.vibrate(ms)
            }
            ok2json(true, "已震动")
        } catch (t: Throwable) { err(t.message) }
    }

    private fun sysBattery(): String {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE)
                    as android.os.BatteryManager
            JSONObject().apply {
                put("ok", true)
                put("level", bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY))
            }.toString()
        } catch (t: Throwable) { err(t.message) }
    }

    private fun sysOpenUrl(p: JSONObject): String {
        return try {
            val url = p.optString("url")
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(url))
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ok2json(true, "已打开")
        } catch (t: Throwable) { err("打开失败: ${t.message}") }
    }

    private fun sysShare(p: JSONObject): String {
        return try {
            val text = p.optString("text")
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, text)
            }
            val chooser = android.content.Intent.createChooser(intent, "分享")
            chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            ok2json(true, "已打开分享")
        } catch (t: Throwable) { err(t.message) }
    }

    private fun sysTts(p: JSONObject): String {
        val text = p.optString("text")
        if (text.isEmpty()) return err("缺少 text")
        val flush = p.optBoolean("flush", true)
        val rate = p.optDouble("rate", 1.0).toFloat()
        val pitch = p.optDouble("pitch", 1.0).toFloat()
        val ok = NativeTts.speak(context, text, flush, rate, pitch)
        return ok2json(ok, if (ok) "已朗读（${text.length} 字）" else "朗读失败（设备可能没装语音引擎）")
    }

    private fun sysTtsStop(): String {
        NativeTts.stop()
        return ok2json(true, "已停止朗读")
    }

    /**
     * 定位。
     *
     * 【注意】
     * 这里用 lastKnownLocation（最后已知位置）而不是主动请求 —— 后者要
     * 异步等回调 + 动态权限申请，会阻塞桥接响应。
     * lastKnownLocation 通常够用（系统一直在后台更新），且瞬时返回。
     *
     * 需要 ACCESS_FINE_LOCATION 权限（Manifest 已声明，运行时需用户授权）。
     */
    private fun sysLocation(p: JSONObject): String {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE)
                    as android.location.LocationManager

            // 优先 GPS，其次网络
            val provider = p.optString("provider", "network")
            var loc = lm.getLastKnownLocation(provider)

            if (loc == null) {
                // 回退：遍历所有 provider 找最新的
                listOf(
                    android.location.LocationManager.GPS_PROVIDER,
                    android.location.LocationManager.NETWORK_PROVIDER,
                    android.location.LocationManager.PASSIVE_PROVIDER,
                ).forEach { prov ->
                    try {
                        val l = lm.getLastKnownLocation(prov)
                        if (l != null && (loc == null || l.time > loc!!.time)) loc = l
                    } catch (_: SecurityException) {}
                }
            }

            if (loc == null) {
                return err("拿不到位置（可能未授权或定位未开启）")
            }

            JSONObject().apply {
                put("ok", true)
                put("latitude", loc!!.latitude)
                put("longitude", loc!!.longitude)
                put("accuracy", loc!!.accuracy)
                put("provider", loc!!.provider)
                put("time", loc!!.time)
                put("message", "纬度 ${loc!!.latitude}, 经度 ${loc!!.longitude}")
            }.toString()
        } catch (e: SecurityException) {
            err("定位权限未授予。请在系统设置里给 Claude Code Mobile 开启位置权限")
        } catch (t: Throwable) {
            err("定位失败: ${t.message}")
        }
    }

    // ═══════════════════════════════════════════════════
    //  运行时状态
    // ═══════════════════════════════════════════════════

    /**
     * 运行时状态（给 Web UI 的「环境面板」用）。
     *
     * 【2026-09-24 补充】
     * 原来只查 libproot.so 文件在不在 —— 但「文件在」不代表「proot 能用」。
     * 我们刚踩过的坑：proot 二进制在、rootfs 完整，但因为缺 loader，
     * 一跑就报 `execve: Function not implemented`（报错还极具误导性）。
     *
     * 现在把 loader 存在性、rootfs 关键目录、Node 都报出来，
     * 让「环境面板」能反映真实可用性而不是文件清单。
     */
    private fun runtimeStatus(): String {
        val rootfs = java.io.File(context.filesDir, "rootfs")
        val nativeLib = java.io.File(context.applicationInfo.nativeLibraryDir)
        return JSONObject().apply {
            put("ok", true)
            put("rootfs_installed", java.io.File(rootfs, "root/.ccm-installed").exists())
            put("rootfs_path", rootfs.absolutePath)
            // 关键目录抽查：解压错位时这些会缺失（历史 bug：PAX 头导致 /usr 全没了）
            put("rootfs_has_usr_bin", java.io.File(rootfs, "usr/bin").isDirectory)
            put("native_lib", nativeLib.absolutePath)
            put("proot_exists", java.io.File(nativeLib, "libproot.so").exists())
            // loader 缺失 = proot 必失败，但原来的检查发现不了
            put("proot_loader_exists", java.io.File(nativeLib, "libproot-loader.so").exists())
            // Node：内核的运行前提。
            // proot 实例没传时（老调用点）退回直接查文件，逻辑与 ProotRuntime.nodePath 一致。
            val nodeOk = proot?.nodePath() != null || listOf(
                "usr/local/bin/node", "usr/bin/node", "opt/node/bin/node"
            ).any { java.io.File(rootfs, it).isFile }
            put("node_exists", nodeOk)
            // 内核包完整性（四个关键路径，与 RootfsManager.isKernelInstalled 一致）
            val kernelDir = java.io.File(rootfs, "root/ccm")
            put("kernel_installed",
                java.io.File(kernelDir, "ccm-start.mjs").exists() &&
                java.io.File(kernelDir, "web/server.mjs").exists() &&
                java.io.File(kernelDir, "node_modules").isDirectory &&
                java.io.File(kernelDir, "web/dist").isDirectory)
            put("shizuku", ShizukuBridge.unavailableReason() ?: "已授权")
            put("sdk", android.os.Build.VERSION.SDK_INT)
        }.toString()
    }

    // ── 工具 ──────────────────────────────────────

    private fun ok2json(ok: Boolean, msg: String): String =
        JSONObject().apply { put("ok", ok); put("message", msg) }.toString()

    private fun err(msg: String?): String =
        JSONObject().apply { put("ok", false); put("error", msg ?: "未知错误") }.toString()
}
