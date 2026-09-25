package com.ccm.app.bridge

import android.content.Context
import android.content.pm.PackageManager
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import rikka.shizuku.Shizuku

/**
 * Shizuku 授权桥。
 *
 * phone use 的新实现跑在虚拟副屏上，虚拟屏创建和 UiAutomation 都需要
 * shell uid，无障碍服务（普通 app uid）做不到。Shizuku 提供这个身份，
 * 不需要 root。
 *
 * 这里只负责「能不能用」的判断和授权请求，不持有具体服务连接。
 * 服务绑定在调用方做，因为虚拟屏守护是长生命周期，应该由前台服务持有。
 */
object ShizukuBridge {

    /** 用户拒绝授权时记下，避免每次操作都弹窗。 */
    @Volatile
    private var denied = false

    /** Shizuku 是否装了且正在运行。没装、没启动都算不可用。 */
    fun available(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    /** 本应用是否已拿到授权。 */
    fun granted(): Boolean {
        if (!available()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 需要授权时发起请求。已经授过权返回 true，用户之前拒绝过返回 false，
     * 否则弹出授权并返回 false（授权结果异步回来）。
     */
    fun requestIfNeeded(context: Context): Boolean {
        if (granted()) return true
        if (!available() || denied) return false
        return try {
            Shizuku.addRequestPermissionResultListener { _, result ->
                denied = result != PackageManager.PERMISSION_GRANTED
            }
            Shizuku.requestPermission(1001)
            false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 给 phone use 调用方的状态文本。
     * 返回 null 表示 Shizuku 可用，可以走虚拟副屏。
     * 返回非 null 是原因，调用方据此回落到无障碍实现。
     */
    fun unavailableReason(): String? {
        if (granted()) return null
        if (!available()) return "Shizuku 未运行"
        if (denied) return "Shizuku 授权被拒绝"
        return "Shizuku 未授权"
    }

    private var phone: IPhoneUseService? = null

    fun phoneService(context: Context): IPhoneUseService? {
        phone?.let { if (it.asBinder().isBinderAlive) return it }
        if (!granted()) return null
        return try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(context, PhoneUseService::class.java)
            ).daemon(false).processNameSuffix("phoneuse")
            val binder: IBinder = Shizuku.bindUserService(args, object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, b: IBinder) {}
                override fun onServiceDisconnected(name: ComponentName) { phone = null }
            })
            IPhoneUseService.Stub.asInterface(binder).also { phone = it }
        } catch (_: Throwable) { null }
    }

}
