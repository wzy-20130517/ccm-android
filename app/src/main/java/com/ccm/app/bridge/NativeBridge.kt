package com.ccm.app.bridge

import android.content.Context
import android.util.Log
import com.ccm.app.service.CcmAccessibilityService
import com.ccm.app.tools.ScreenCapture
import com.ccm.app.tools.NativeTts
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
 *   无障碍服务 / 前台服务 / 系统 API
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
                // ── 手机操作（无障碍）────────────────────
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

    private fun phoneSnapshot(p: JSONObject): String {
        val svc = CcmAccessibilityService.get()
            ?: return err("无障碍服务未开启。请在系统设置 → 无障碍 → 已安装的服务 里启用 CCM")
        val interactiveOnly = p.optBoolean("interactive_only", true)
        val maxNodes = p.optInt("max_nodes", 300)
        return svc.snapshot(interactiveOnly, maxNodes)
    }

    private fun phoneClick(p: JSONObject): String {
        val svc = CcmAccessibilityService.get() ?: return err("无障碍服务未开启")
        val ref = p.optString("ref")
        if (ref.isEmpty()) return err("缺少 ref 参数")
        val longPress = p.optBoolean("long_press", false)
        val ok = svc.clickByRef(ref, longPress)
        return ok2json(ok, if (ok) "已点击 $ref" else "点击失败（节点可能已消失，请重新 snapshot）")
    }

    private fun phoneTap(p: JSONObject): String {
        val svc = CcmAccessibilityService.get() ?: return err("无障碍服务未开启")
        val x = p.optDouble("x", -1.0).toFloat()
        val y = p.optDouble("y", -1.0).toFloat()
        if (x < 0 || y < 0) return err("缺少坐标 x/y")
        val longPress = p.optBoolean("long_press", false)
        val ok = svc.tapXY(x, y, longPress)
        return ok2json(ok, if (ok) "已点击 ($x, $y)" else "坐标点击失败")
    }

    private fun phoneType(p: JSONObject): String {
        val svc = CcmAccessibilityService.get() ?: return err("无障碍服务未开启")
        val text = p.optString("text")
        if (text.isEmpty()) return err("缺少 text")
        val ref = p.optString("ref")

        // ① 有 ref → 直接往那个节点写
        if (ref.isNotEmpty()) {
            val ok = svc.typeByRef(ref, text)
            if (ok) return ok2json(true, "已输入 ${text.length} 字符")
            return err("输入失败（ref 可能已失效，请重新 snapshot）")
        }

        // ② 无 ref → 找当前有焦点的输入框
        //
        // 【为什么需要这条路径】
        // AI 的常见流程是：snapshot → click 输入框 → phone_type（不带 ref）。
        // 此时焦点已经在输入框上，但 AI 未必记得 ref（或者 ref 已因界面变化失效）。
        // 原来直接报错"需要 ref"，会打断这个自然流程。
        val focused = svc.findFocusedEditable()
        if (focused != null) {
            val ok = svc.typeInto(focused, text)
            if (ok) return ok2json(true, "已输入 ${text.length} 字符（焦点框）")
        }

        // ③ 兜底：用剪贴板 + 粘贴（需要 IME 支持，成功率取决于输入法）
        return err(
            "找不到可输入的框。请先 snapshot 拿到输入框 ref，" +
            "或先 click 输入框使其获得焦点。"
        )
    }

    private fun phoneSwipe(p: JSONObject): String {
        val svc = CcmAccessibilityService.get() ?: return err("无障碍服务未开启")
        val dir = p.optString("direction", "")
        if (dir.isNotEmpty()) {
            // 方向滑动：屏幕中心起，滑屏幕 1/3
            val dm = context.resources.displayMetrics
            val cx = dm.widthPixels / 2f
            val cy = dm.heightPixels / 2f
            val d = dm.heightPixels / 3f
            val (x1, y1, x2, y2) = when (dir.lowercase()) {
                "up" -> arrayOf(cx, cy + d / 2, cx, cy - d / 2)
                "down" -> arrayOf(cx, cy - d / 2, cx, cy + d / 2)
                "left" -> arrayOf(cx + d / 2, cy, cx - d / 2, cy)
                "right" -> arrayOf(cx - d / 2, cy, cx + d / 2, cy)
                else -> return err("direction 只能是 up/down/left/right")
            }
            val ok = svc.swipe(x1, y1, x2, y2, p.optLong("duration", 300))
            return ok2json(ok, if (ok) "已向 $dir 滑动" else "滑动失败")
        }
        // 坐标滑动
        val x1 = p.optDouble("x1", -1.0).toFloat()
        val y1 = p.optDouble("y1", -1.0).toFloat()
        val x2 = p.optDouble("x2", -1.0).toFloat()
        val y2 = p.optDouble("y2", -1.0).toFloat()
        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) return err("需要 direction 或 x1/y1/x2/y2")
        val ok = svc.swipe(x1, y1, x2, y2, p.optLong("duration", 300))
        return ok2json(ok, if (ok) "已滑动" else "滑动失败")
    }

    private fun phoneScroll(p: JSONObject): String {
        val svc = CcmAccessibilityService.get() ?: return err("无障碍服务未开启")
        val ref = p.optString("ref").ifEmpty { null }
        val dir = p.optString("direction", "down")
        val ok = svc.scrollByRef(ref, dir)
        return ok2json(ok, if (ok) "已滚动" else "滚动失败")
    }

    private fun phoneKey(p: JSONObject): String {
        val svc = CcmAccessibilityService.get() ?: return err("无障碍服务未开启")
        val key = p.optString("key")
        if (key.isEmpty()) return err("缺少 key")
        val ok = svc.globalAction(key)
        return ok2json(ok, if (ok) "已按 $key" else "按键失败（不支持的键名？）")
    }

    private fun phoneApp(p: JSONObject): String {
        val action = p.optString("action", "launch")
        val pkg = p.optString("package")
        return try {
            val pm = context.packageManager
            when (action) {
                "list" -> {
                    val filter = p.optString("filter", "")
                    val apps = pm.getInstalledApplications(0)
                        .filter { it.packageName.contains(filter, true) }
                        .map { it.packageName }
                        .sorted()
                    JSONObject().apply {
                        put("ok", true)
                        put("count", apps.size)
                        put("apps", JSONArray(apps))
                    }.toString()
                }
                "current" -> {
                    val svc = CcmAccessibilityService.get()
                    val pkgName = svc?.rootInActiveWindow?.packageName?.toString() ?: ""
                    ok2json(pkgName.isNotEmpty(), pkgName)
                }
                "launch" -> {
                    if (pkg.isEmpty()) return err("缺少 package")
                    val intent = pm.getLaunchIntentForPackage(pkg)
                        ?: return err("找不到应用: $pkg")
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    ok2json(true, "已启动 $pkg")
                }
                "stop" -> err("stop 需要 root 权限，暂不支持")
                else -> err("未知 action: $action")
            }
        } catch (t: Throwable) {
            err("应用操作失败: ${t.message}")
        }
    }

    private fun phoneScreenshot(p: JSONObject): String {
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
        val connected = CcmAccessibilityService.isConnected()
        return JSONObject().apply {
            put("ok", true)
            put("accessibility", connected)
            put("message", if (connected) "无障碍服务运行中" else "无障碍服务未开启")
        }.toString()
    }

    // ═══════════════════════════════════════════════════
    //  系统能力
    // ═══════════════════════════════════════════════════

    private fun sysNotify(p: JSONObject): String {
        val title = p.optString("title", "CCM")
        val content = p.optString("content", "")
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as android.app.NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                val ch = android.app.NotificationChannel(
                    "ccm_main", "CCM", android.app.NotificationManager.IMPORTANCE_DEFAULT
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
            err("定位权限未授予。请在系统设置里给 CCM 开启位置权限")
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
            put("accessibility", CcmAccessibilityService.isConnected())
            put("sdk", android.os.Build.VERSION.SDK_INT)
        }.toString()
    }

    // ── 工具 ──────────────────────────────────────

    private fun ok2json(ok: Boolean, msg: String): String =
        JSONObject().apply { put("ok", ok); put("message", msg) }.toString()

    private fun err(msg: String?): String =
        JSONObject().apply { put("ok", false); put("error", msg ?: "未知错误") }.toString()
}
