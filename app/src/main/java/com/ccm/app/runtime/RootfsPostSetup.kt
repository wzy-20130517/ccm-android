package com.ccm.app.runtime

import android.system.Os
import android.util.Log
import java.io.File

/**
 * rootfs 后处理 —— 解压 ubuntu-base 之后必须做的三步。
 *
 * ═══════════════════════════════════════════════════════════
 * 【为什么必须做】
 *
 * ubuntu-base 官方镜像假设自己跑在「真机 / 普通容器」里 —— 那里的
 * `/etc/passwd` 一定包含运行者的 UID。但 CCM 的 rootfs 跑在 Android
 * proot 里，宿主 UID 是 App 沙箱分配的（如 10286），而 Ubuntu 镜像里
 * 只有 root(0) 和一堆系统账号，**没有 10286**。
 *
 * 后果不是「报个错」这么简单：
 *   · `apt-get update` 会失败 —— 它要解析当前用户
 *   · 大量 postinst 脚本 exit 127（内部 exec 一个查不到用户的命令）
 *   · 症状看起来像「网络问题」「包损坏」，极难定位
 *
 * 所以必须在解压后立刻把宿主身份写进 rootfs 的账号文件。
 *
 * 【移植来源】
 * 逻辑对齐 proot-distro 5.9 的 `helpers/rootfs.py`：
 *   register_android_ids() / write_resolv_conf() / write_hosts()
 * 三步做完 `apt install` 直接可用（已在 Termux 实测验证，见 recon-c-proot.md）。
 * ═══════════════════════════════════════════════════════════
 */
object RootfsPostSetup {

    private const val TAG = "RootfsPostSetup"

    /**
     * 0644（rw-r--r--）。
     *
     * 账号文件必须是这个权限 —— passwd/shadow 相关调用在权限过宽或过窄时
     * 会拒绝读取（某些库会检查「文件不该被 group/other 写」）。
     */
    private const val MODE_0644 = 0b110100100   // 八进制 644 = 十进制 420

    /** GID 拿不到时的兜底：3003 = inet（Android 每个 App 都默认持有） */
    private const val FALLBACK_GID = 3003

    /** Android 的 uid 编码：appId 从 10000 起（FIRST_APPLICATION_UID） */
    private const val FIRST_APPLICATION_UID = 10000

    /** DNS —— 阿里 + 腾讯，国内解析快且稳定 */
    private const val DNS_PRIMARY = "223.5.5.5"
    private const val DNS_SECONDARY = "119.29.29.29"

    /**
     * shadow 的 lastchg 字段（密码最后修改日）。
     * 照抄 proot-distro 的值，写成 0 或未来日期反而可能触发「密码过期」逻辑。
     */
    private const val SHADOW_LASTCHG = "18446"

    /**
     * Android 组名映射表。
     *
     * 【为什么需要】proot-distro 用 `grp.getgrgid(g).gr_name` 查组名，
     * 那是 Termux 的 /etc/group 提供的。APK 里没有这个文件（也不该去读），
     * 所以内置一份常用映射；查不到的用 `g<数字>` 兜底。
     *
     * ⚠️ 名字本身不影响权限判定（内核只看 GID 数字），但能让 `ls -l`
     * 和 `id` 的输出可读，排查问题时省事。
     */
    private val ANDROID_GROUP_NAMES = mapOf(
        1000 to "system",
        1001 to "radio",
        1002 to "bluetooth",
        1003 to "graphics",
        1004 to "input",
        1005 to "audio",
        1006 to "camera",
        1007 to "log",
        1008 to "compass",
        1009 to "mount",
        1010 to "wifi",
        1011 to "adb",
        1012 to "install",
        1013 to "media",
        1014 to "dhcp",
        1015 to "sdcard_rw",
        1016 to "vpn",
        1017 to "keystore",
        1018 to "usb",
        1019 to "drm",
        1021 to "gps",
        1023 to "media_rw",
        1024 to "mtp",
        1025 to "drmrpc",
        1026 to "nfc",
        1027 to "sdcard_r",
        1028 to "clat",
        1031 to "package_info",
        1032 to "sdcard_pics",
        1033 to "sdcard_av",
        1034 to "sdcard_audio",
        1077 to "external_storage",
        1079 to "ext_obb_rw",
        1096 to "update_engine_log",
        3001 to "net_bt_admin",
        3002 to "net_bt",
        3003 to "inet",
        3004 to "net_raw",
        3005 to "net_admin",
        3006 to "net_bw_stats",
        3007 to "net_bw_acct",
        3008 to "readproc",
        3009 to "wake_alarm",
        3010 to "uhid",
        9997 to "everybody",
    )

    // ═══════════════════════════════════════════════════
    //  对外入口
    // ═══════════════════════════════════════════════════

    /**
     * 依次执行三步后处理。
     *
     * 单个步骤内部已各自兜住异常（失败只记日志，不中断安装），
     * 返回值只反映「rootfs 结构是否正常」——若连 etc/passwd 都没有，
     * 说明解压出来的不是预期镜像，早报错比让用户后面撞一堆怪错误强。
     *
     * @param rootfs rootfs 根目录（安装流程里传的是临时目录）
     * @return true = 三步已执行
     */
    fun applyAll(rootfs: File): Boolean {
        val passwd = File(rootfs, "etc/passwd")
        if (!passwd.exists()) {
            Log.e(TAG, "rootfs 结构异常：${passwd.path} 不存在，跳过后续配置")
            return false
        }
        registerAndroidIds(rootfs)
        writeResolvConf(rootfs)
        writeHosts(rootfs)
        return true
    }

    /**
     * 把宿主（Android App）的 UID/GID 注册进 rootfs 的账号文件。
     *
     * 【写出来的内容】以本机 uid=10286 为例：
     *   /etc/passwd   ← aid_u0_a286:x:10286:10286:Termux:/:/sbin/nologin
     *   /etc/shadow   ← aid_u0_a286:*:18446:0:99999:7:::
     *   /etc/group    ← aid_u0_a286:x:10286:root,aid_u0_a286
     *   /etc/gshadow  ← aid_u0_a286:*::root,aid_u0_a286
     *
     * 【为什么用户名长这样】见 [androidUserName] 的说明。
     *
     * 【为什么要带 `aid_` 前缀】对齐 proot-distro 的写法（`aid_` + 用户名）。
     * 好处是绝不会和镜像里已有的账号重名，重装/追加都安全。
     */
    fun registerAndroidIds(rootfs: File) {
        val passwd = File(rootfs, "etc/passwd")
        val shadow = File(rootfs, "etc/shadow")
        val group = File(rootfs, "etc/group")
        val gshadow = File(rootfs, "etc/gshadow")

        // ① 先修权限 —— python 版同样把这步放在最前，因为后面要 append
        listOf(passwd, shadow, group, gshadow).forEach { chmod644(it) }

        val uid = android.os.Process.myUid()
        val gid = currentGid()
        val userName = androidUserName(uid)

        // ② passwd / shadow —— 只登记主 UID
        //
        // shadow 的字段布局是 name:passwd:lastchg:min:max:warn:inactive:expire:reserved，
        // 所以判重必须用「名字」而不是「第 3 列」（那列是日期，不是 uid）。
        if (passwd.exists() && !hasEntry(passwd, "aid_$userName")) {
            appendLine(passwd, "aid_$userName:x:$uid:$gid:Termux:/:/sbin/nologin")
        }
        if (shadow.exists() && !hasEntry(shadow, "aid_$userName")) {
            appendLine(shadow, "aid_$userName:*:$SHADOW_LASTCHG:0:99999:7:::")
        }

        // ③ group / gshadow —— 主 GID + 全部附加组
        //
        // 【为什么要登记附加组】宿主进程实际属于 inet / external_storage 等组，
        // rootfs 里的程序若按 GID 反查组名会失败。对齐 python 版的
        // `for g in [gid] + os.getgroups()`。
        for (g in allGids(gid)) {
            val gname = "aid_${groupName(g, uid, userName)}"
            if (group.exists() && !hasEntry(group, gname)) {
                appendLine(group, "$gname:x:$g:root,aid_$userName")
            }
            if (gshadow.exists() && !hasEntry(gshadow, gname)) {
                appendLine(gshadow, "$gname:*::root,aid_$userName")
            }
        }

        Log.i(TAG, "已登记宿主身份：aid_$userName (uid=$uid gid=$gid, 组 ${allGids(gid).size} 个)")
    }

    /**
     * 写 /etc/resolv.conf（DNS）。
     *
     * 用国内 DNS：Android 上拿不到系统 DNS（没权限读 net.dns* 属性），
     * 而默认走 8.8.8.8 在国内经常被污染/超时。
     */
    fun writeResolvConf(rootfs: File) {
        replaceFile(
            File(rootfs, "etc/resolv.conf"),
            "nameserver $DNS_PRIMARY\nnameserver $DNS_SECONDARY\n"
        )
    }

    /**
     * 写 /etc/hosts。
     *
     * 内容对齐 proot-distro 的 write_hosts()：localhost + IPv6 组播段。
     * 少了它，很多程序解析 localhost 会绕去查 DNS —— 慢，而且可能失败。
     */
    fun writeHosts(rootfs: File) {
        replaceFile(
            File(rootfs, "etc/hosts"),
            """
            # IPv4.
            127.0.0.1   localhost.localdomain localhost

            # IPv6.
            ::1         localhost.localdomain localhost ip6-localhost ip6-loopback
            fe00::0     ip6-localnet
            ff00::0     ip6-mcastprefix
            ff02::1     ip6-allnodes
            ff02::2     ip6-allrouters
            ff02::3     ip6-allhosts
            """.trimIndent() + "\n"
        )
    }

    // ═══════════════════════════════════════════════════
    //  内部工具
    // ═══════════════════════════════════════════════════

    /**
     * 推导 Android 用户名（形如 `u0_a286`）。
     *
     * 【编码规则】`uid = userId * 100000 + appId`，且 appId 从 10000 起。
     * 本机实测：uid=10286 → userId=0, appId=10286 → **u0_a286**
     * （与 `id` 命令输出的 `uid=10286(u0_a286)` 完全一致）。
     * 算法同 Android 官方 `UserHandle.getUserName()`。
     *
     * ⚠️ 不要写成 `u0_a${uid % 100000}` —— 那会得到 `u0_a10286`，
     * 和系统真实用户名差 10000，`id` 反查不到、日志也对不上。
     */
    private fun androidUserName(uid: Int): String {
        val userId = uid / 100000
        return "u${userId}_a${appIdSuffix(uid)}"
    }

    /** 当前进程的 GID（拿不到时兜底 inet，保证后续流程不因它中断） */
    private fun currentGid(): Int = try {
        Os.getgid()
    } catch (t: Throwable) {
        Log.w(TAG, "Os.getgid() 失败，兜底 $FALLBACK_GID", t)
        FALLBACK_GID
    }

    /**
     * 主 GID + 全部附加组（去重，保持顺序）。
     *
     * 【附加组怎么拿】Android 没有 `os.getgroups()` 的 Kotlin 对应。
     * 但内核把进程的全部组列在 `/proc/self/status` 的 `Groups:` 行里，
     * 直接读它即可 —— 比反射调 libc 稳，也不需要任何权限。
     */
    private fun allGids(primary: Int): List<Int> {
        val out = LinkedHashSet<Int>()
        out += primary
        out += supplementaryGids()
        return out.toList()
    }

    /** 解析 /proc/self/status 的 Groups 行（空格/tab 分隔的 GID 列表） */
    private fun supplementaryGids(): List<Int> {
        return try {
            val line = File("/proc/self/status").readLines()
                .firstOrNull { it.startsWith("Groups:") }
                ?: return emptyList()
            line.removePrefix("Groups:")
                .trim()
                .split(Regex("\\s+"))
                .mapNotNull { it.toIntOrNull() }
        } catch (t: Throwable) {
            Log.w(TAG, "读 /proc/self/status 失败，附加组按空处理", t)
            emptyList()
        }
    }

    /**
     * 给 GID 起名字。
     *
     * 【三类来源，按优先级】
     *  1. 主组（gid == uid）→ 直接用用户名
     *  2. App 专属组 —— 名字由 UID 模式推导，**必须现算**：
     *       20286 → u0_a286_cache   （App 的 cache 组，uid + 10000）
     *       50286 → all_a286        （该 App 的全部组，uid + 40000）
     *     这两个不在静态表里（每个 App 数值都不同），但规律固定。
     *     ⚠️ 已对照 proot-distro 的实际输出验证过（`id` 显示 aid_u0_a286_cache）。
     *  3. 系统组（log / inet / everybody...）→ 查 [ANDROID_GROUP_NAMES]
     *  4. 都没有 → `g<数字>` 兜底
     *
     * 【为什么名字重要】内核只认 GID 数字，名字不影响权限判定。
     * 但 `id` / `ls -l` 的输出会用它，排查问题时对不上名字很费劲
     * —— proot-distro 正是靠 grp 查名字，我们这里等价复刻。
     */
    private fun groupName(gid: Int, uid: Int, userName: String): String {
        if (gid == uid) return userName
        // App 专属组：从 uid 推导（不能用 gid 推，gid 的偏移量本身就是要算的东西）
        val suffix = appIdSuffix(uid)
        if (gid == uid + 10_000) return "${userName}_cache"
        if (gid == uid + 40_000) return "all_a$suffix"
        return ANDROID_GROUP_NAMES[gid] ?: "g$gid"
    }

    /** uid → appId 后缀（10286 → 286），用于拼 App 专属组名 */
    private fun appIdSuffix(uid: Int): Int {
        val appId = uid % 100000
        return if (appId >= FIRST_APPLICATION_UID) appId - FIRST_APPLICATION_UID else appId
    }

    /** 文件里是否已有该名字的条目（按第一个字段判，passwd/group/gshadow 通用） */
    private fun hasEntry(file: File, name: String): Boolean {
        return try {
            file.readLines().any { it.substringBefore(":") == name }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 追加一行，**保证不和上一行粘连**。
     *
     * 【为什么要专门处理】账号文件若末尾没有换行符，直接 append 会把新条目
     * 接到最后一行屁股上（如 `nobody:x:65534:...aid_u0_a286:x:10286`），
     * 那一行整个作废 —— 而且报错信息会指向「格式非法」而不是「少了换行」。
     * （已用真实 ubuntu-base 验证：无换行时确实会粘成一行。）
     *
     * 【实现选择】这里刻意**不用 RandomAccessFile 的 seek/write** ——
     * 那种写法依赖「seek 后写入是否自动推进位置」的细节语义，容易写错
     * （实测中同类 API 在 Node 上就有「offset 传 null 变成从头覆盖」的坑，
     *   把 passwd 第一行的 root 覆盖掉了）。改用最直白的
     * 「读出来 → 拼上 → 整体写回」，行为无歧义，代价只是这几个文件都很小（<1KB）。
     */
    private fun appendLine(file: File, line: String) {
        try {
            val existing = if (file.exists()) file.readText() else ""
            val builder = StringBuilder(existing)
            if (existing.isNotEmpty() && !existing.endsWith("\n")) builder.append('\n')
            builder.append(line).append('\n')
            file.writeText(builder.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "追加 ${file.name} 失败", t)
        }
    }

    /**
     * 用新内容替换文件（**先删再写**）。
     *
     * 【为什么不能直接 writeText】ubuntu-base 镜像里 `/etc/resolv.conf`、
     * `/etc/hosts` 有可能是**符号链接**（指向 /run/systemd/... 之类）。
     * 直接写会跟随链接写到目标位置，rootfs 里那份「看起来没变」——
     * 症状是「明明配了 DNS 却解析不了」。
     * proot-distro 的 write_resolv_conf() / write_hosts() 同样先 os.remove()。
     *
     * ⚠️ 这里不能拿 `file.exists()` 当「要不要删」的判据 ——
     * 对**断链的符号链接**它返回 false。直接 delete() 才安全
     * （删链接本身，不会动目标文件）。
     */
    private fun replaceFile(file: File, content: String) {
        try {
            file.parentFile?.mkdirs()
            file.delete()
            file.writeText(content)
        } catch (t: Throwable) {
            Log.w(TAG, "写 ${file.path} 失败", t)
        }
    }

    /** 设成 0644（失败不致命，只记日志） */
    private fun chmod644(file: File) {
        if (!file.exists()) return
        try {
            Os.chmod(file.absolutePath, MODE_0644)
        } catch (t: Throwable) {
            Log.w(TAG, "chmod ${file.name} 失败", t)
        }
    }
}
