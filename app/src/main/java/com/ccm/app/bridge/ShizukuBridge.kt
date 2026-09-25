package com.ccm.app.bridge

import android.content.Context
import android.content.pm.PackageManager
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import rikka.shizuku.Shizuku
import com.ccm.app.service.CcmService

/**
 * Shizuku 授权桥。
 *
 * phone use 跑在虚拟副屏上，虚拟屏创建和 UiAutomation 都需要 shell uid。
 * Shizuku 提供这个身份，不需要 root。
 * （2026-09-25：原「无障碍兜底」已删除 —— 普通 app uid 做不了虚屏，
 *   留着只会让能力面看起来比实际大。）
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
     * 返回非 null 是不可用的原因（Shizuku 已是唯一通道，没有兜底可回落）。
     */
    fun unavailableReason(): String? {
        if (granted()) return null
        if (!available()) return "Shizuku 未运行"
        if (denied) return "Shizuku 授权被拒绝"
        return "Shizuku 未授权"
    }

    private var phone: IPhoneUseService? = null

    /** 上次绑定失败的原因（供状态显示/排查，不吞掉信息）。 */
    @Volatile
    var lastPhoneError: String? = null
        private set

    /** 绑定进行中标记：并发调用时不要各自去 bind 一遍（Shizuku 会重复拉起服务）。 */
    private val binding = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 拿到 phone use 服务。
     *
     * 【必须在主线程 bind】Shizuku 的 bindUserService 通过 ServiceConnection 回调回来，
     * 而 ServiceConnection 的回调固定投递到主线程 Looper。桥服务器跑在工作线程上，
     * 从工作线程直接 bind 会导致回调永远到不了 —— latch 白等到超时，
     * 而且每次调用都会这么等一遍，把线程池咬死（实测：连 /ping 都不响应了）。
     *
     * 【失败要留原因】原来 catch 里直接吞掉返回 null，调用方只看到「手机操作不可用」，
     * 到底是没授权、服务起不来、还是超时完全看不出来。
     */
    fun phoneService(context: Context): IPhoneUseService? {
        phone?.let { if (it.asBinder().isBinderAlive) return it }
        if (!granted()) {
            lastPhoneError = unavailableReason() ?: "Shizuku 未授权"
            android.util.Log.w("ShizukuBridge", "phoneService: 未授权 → ${'$'}{lastPhoneError}")
            return null
        }
        android.util.Log.i("ShizukuBridge", "phoneService: 开始绑定（主线程=${'$'}{android.os.Looper.myLooper() == android.os.Looper.getMainLooper()}）")
        // 已有别的线程在绑 → 等它，不重复 bind（否则会拉起多个服务进程）
        if (!binding.compareAndSet(false, true)) {
            val deadline = System.currentTimeMillis() + 8000
            while (System.currentTimeMillis() < deadline) {
                phone?.let { if (it.asBinder().isBinderAlive) return it }
                try { Thread.sleep(50) } catch (_: InterruptedException) { break }
            }
            lastPhoneError = "等待其它线程绑定超时"
            return null
        }

        return try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(context, PhoneUseService::class.java)
            ).daemon(false).processNameSuffix("phoneuse")

            val latch = java.util.concurrent.CountDownLatch(1)
            val errHolder = arrayOfNulls<String>(1)

            // 投到主线程执行 —— 这是关键，工作线程上 bind 收不到回调
            val posted = CcmService.mainHandler.post {
                try {
                    Shizuku.bindUserService(args, object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, b: IBinder) {
                            try {
                                phone = IPhoneUseService.Stub.asInterface(b)
                                latch.countDown()
                            } catch (t: Throwable) {
                                errHolder[0] = "asInterface 失败：${t.message}"
                                latch.countDown()
                            }
                        }
                        override fun onServiceDisconnected(name: ComponentName) { phone = null }
                    })
                } catch (t: Throwable) {
                    errHolder[0] = "bindUserService 抛异常：${t.message}"
                    latch.countDown()
                }
            }
            android.util.Log.i("ShizukuBridge", "phoneService: 已投递到主线程=$posted，等待回调")
            // 超时给足：Shizuku 要 fork 出一个新进程再加载 dex，冷启动可能几秒
            val ok = latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
            android.util.Log.i("ShizukuBridge", "phoneService: 等待结束 ok=$ok err=${'$'}{errHolder[0]} phone=${'$'}{phone != null}")
            if (!ok) {
                lastPhoneError = "绑定超时（15s）—— Shizuku 可能没在运行，或服务进程起不来"
                null
            } else if (errHolder[0] != null) {
                lastPhoneError = errHolder[0]
                null
            } else if (phone == null) {
                lastPhoneError = "回调到了但 binder 为空"
                null
            } else {
                lastPhoneError = null
                phone
            }
        } catch (t: Throwable) {
            lastPhoneError = "绑定异常：${t.message}"
            null
        } finally {
            binding.set(false)
        }
    }

}
