plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // 【2026-09-27 新增 · 阶段2】序列化编译器插件，给 core/ 层的 @Serializable 用。
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.ccm.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ccm.app"
        minSdk = 26
        targetSdk = 35
        // 【2026-09-23】versionCode 原来固定为 1。
        // 覆盖安装时 Android 要求新包的 versionCode >= 旧的，否则报
        // 「已存在更高版本」。CI 每次构建都递增（用 run_number），本地构建回退到 1。
        versionCode = (System.getenv("CCM_VERSION_CODE") ?: "1").toIntOrNull() ?: 1
        versionName = System.getenv("CCM_VERSION_NAME") ?: "0.1.0"

        ndk {
            // 只保留 arm64（手机都是这个）
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures { compose = true; aidl = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // 【2026-09-23 加固定签名】
    //
    // 问题：原来只跑 assembleDebug，而 debug keystore 是**每台机器/每次 CI
    // 首次构建时自动生成的**（存在 ~/.android/debug.keystore）。CI runner 是
    // 全新环境，所以每次构建的签名都不一样 →
    // 用户每次装新版都被 Android 拦下「签名不一致，请先卸载旧版本」。
    //
    // 修法：仓库里带一个固定 keystore，debug 和 release 都用它签。
    // 这样同一份 keystore 签出来的包可以互相覆盖安装。
    //
    // ⚠️ 仓库是 private，keystore 是自签名（只为覆盖安装，不用于上架）。
    //    密码写在 build.gradle 里是有意的 —— 这个 key 的唯一作用是「同一把钥匙」，
    //    泄露的风险是被别人签一个能覆盖安装的同包名 APK，对自用 App 不构成威胁。
    //    真要上架 Play 必须换成不入库的 key + Secrets。
    signingConfigs {
        create("ccm") {
            storeFile = file("../keystore/ccm-release.jks")
            storePassword = "ccm2026signing"
            keyAlias = "ccm"
            keyPassword = "ccm2026signing"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("ccm")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("ccm")
        }
    }

    // proot 二进制以 .so 形式打进 jniLibs —— 只有 nativeLibraryDir 才有 exec 权限
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")

    androidResources {
        // 【2026-09-27 · 阶段5 P2 修正】后缀从 `tar.gz` 改成 `bin`。
        //
        // ═══════════════════════════════════════════════════════════════
        // ⚠️ 起因：AAPT 对 **`.gz` 结尾的 asset 会自动 gunzip 并去掉后缀**，
        //    这个行为**不受 noCompress 影响**（它压根不是「压缩」那一步做的）。
        //
        //    实测 build-158：
        //      源码 `app/src/main/assets/ubuntu-base.tar.gz`  29,865,086 字节（gzip）
        //      APK 内 `assets/ubuntu-base.tar`               106,649,600 字节（裸 tar）
        //      —— 106,649,600 正是 gzip 头里记录的原始大小，铁证。
        //
        //    后果：RootfsManager 找 `ubuntu-base.tar.gz` 必然
        //    FileNotFoundException → hasAssetArchive() 恒为 false →
        //    **静默退回网络下载**。内置包完全没生效，APK 白涨 30MB，
        //    而且失败是静默的（用户只看到「在下载」），极难发现。
        //
        //    用 `.bin` 后缀 AAPT 就原样打包，不会碰它。
        // ═══════════════════════════════════════════════════════════════
        //
        // 这里配 `bin` 让它 **STORED** 存储，两个好处：
        //   · 复制时不用边解压边写 —— 29MB 直接拷，2~5 秒
        //   · `AssetManager.openFd()` 能拿到长度（进度条要用）
        //
        // ⚠️ 改这里必须同步 RootfsManager.ASSET_ARCHIVE 的文件名，两处是一体的。
        // 【2026-10-05 加 xz】换用 Operit 的 proot-distro rootfs 包后，
        // 主包是 `ubuntu-noble-aarch64-pd.tar.xz`（64MB）。
        // 同样要 STORED：xz 本身已是高压缩（比 gz 小 20%），APK 再压一遍
        // 既省不了多少，又会让 openFd() 拿不到长度（进度条失效）。
        // 顺带说明：AAPT 的「自动 gunzip」只针对 `.gz` 后缀，
        // `.xz` 不在其列 —— 不会被改名，命名是安全的。
        noCompress += "bin"
        noCompress += "xz"
    }

    packaging {
        // 不压缩 so，保证解压后可直接 exec
        jniLibs { useLegacyPackaging = true }
    }
    // ══════════════════════════════════════════════════════════════
    // 【2026-10-05 移除 isReturnDefaultValues —— 它是 19 个测试静默失败的元凶】
    //
    // 历史：CI #242（7e8e069）加了「双保险」：
    //   ① testImplementation("org.json:json")  ← 真实现（对的）
    //   ② unitTests.isReturnDefaultValues = true ← 让存根返回默认值（有害）
    //
    // ② 的问题：它让 android.jar 的 **存根方法返回默认值而不是抛异常** ——
    // 于是 JSONObject.put() 静默变成 no-op（不写入任何数据），
    // 而 get()/optLong() 永远返回默认值。
    // 结果不是「测试跑不起来」（那还能看见），而是**测试静默失败**：
    //   GoalStore.toJson() 写进去的 startedAt 读回来是 0
    //   → budgetLine() 走错分支 → 断言失败
    // CI #241 实测：19 个测试报 AssertionError（不是 RuntimeException），
    // 每个失败耗 5 分钟（Gradle 的测试重试/超时），总共烧 23 分钟。
    //
    // 正确做法：**只保留 ①**。Gradle 的 mockable-android-jar 机制会让
    // classpath 上的真实 org.json 覆盖存根 —— 测试里跑的就是真实现，
    // 行为与生产一致。不需要 ② 兜底，② 只会把「缺真实现」这个配置错误
    // 掩盖成「数据静默丢失」。
    // ══════════════════════════════════════════════════════════════

}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    // XZ 解压（纯 Java，165KB）。
    //
    // 【为什么需要】Node.js 官方发 .tar.xz（29MB），而 .tar.gz 是 55MB ——
    // 差 26MB，在手机流量下不是小数目。但 Android 没内置 xz 解压器：
    //   · toybox 不带 xz（实测 /system/bin 下没有 xzcat/unxz）
    //   · rootfs 里的 xz-utils 要装完「基础工具」才有 —— 鸡生蛋
    // 所以用纯 Java 实现。1.12 是当前版本，无传递依赖。
    implementation("org.tukaani:xz:1.12")
    // Shizuku：在 shell uid 下跑虚拟副屏守护和元素树抓取。
    // API 负责授权和 binder，provider 提供 manifest 合并项（权限声明）。
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // ─────────── 以下为 2026-09-27 阶段2（core 层）新增 ───────────
    //
    // 【OkHttp 4.12.0】core/api 的唯一出网点。
    // 为什么不用 HttpURLConnection：
    //   1. SSE 逐行读：okio 的 BufferedSource.readUtf8Line() 天然流式，不用手写缓冲区
    //   2. 【最关键】CCM 踩过的血泪 bug「一次流超时后所有请求永久卡死」，
    //      根因是 HttpURLConnection 的连接池无法隔离坏连接，只能靠加
    //      `Connection: close` 头绕开。OkHttp 可以给流式请求单独一个 client
    //      实例（独立连接池），从根上杜绝污染。
    //   3. Call.cancel() 对应 AbortController，取消立即生效
    //   4. connectTimeout / readTimeout 分离，对齐 CCM 的
    //      STREAM_CONNECT_TIMEOUT(150s 实测阈值) 与 watchdog(300s) 双层设计
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ══════════════════════════════════════════════════════════════
    //  【2026-10-06 问题40】MCP（Model Context Protocol）客户端
    // ══════════════════════════════════════════════════════════════
    //
    // 用户报「mcp 可以接，你看 operit 的实现」—— 查了 Operit
    // （AAswordman/Operit，8416 星），它用的是**官方 Kotlin SDK**：
    //   io.modelcontextprotocol:kotlin-sdk-client:0.10.0
    //   + io.ktor:ktor-client-okhttp:3.2.3（传输层）
    //
    // 直接用官方 SDK 比自己实现 JSON-RPC + SSE 稳得多（协议细节：
    // 初始化握手、能力协商、分页、错误码，SDK 都处理了）。
    implementation("io.modelcontextprotocol:kotlin-sdk-client:0.10.0")
    implementation("io.ktor:ktor-client-okhttp:3.2.3")

    // 【kotlinx.serialization 1.7.3】JSON 序列化。
    // 为什么不用 Gson（虽然它已在缓存里，是 AGP 的传递依赖）：
    //   Gson 用反射绕过 Kotlin 的构造器，data class 的非空字段能被塞进 null、
    //   默认值会被忽略 —— 配置迁移（旧 config.json 缺字段）场景下必然踩坑。
    //   kotlinx.serialization 编译期生成，配合 ignoreUnknownKeys + 默认值，
    //   是「向后兼容旧配置文件」最稳的方案。
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // 【协程】Agent 循环 / 流式事件 / 取消，全项目异步基础。
    // 原本是 lifecycle-runtime-ktx 的传递依赖，这里显式声明版本，
    // 避免上游升级时被动漂移。
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // 【单元测试】core 层是纯 JVM 代码（零 Android 依赖），可以直接跑 JUnit。
    //
    // 【为什么要建测试基建】CCM 踩过的坑几乎全是「静默降级」类型 ——
    // 不报错、不崩溃，但功能悄悄失效（如 ToolSchema.int() 判反了 isString，
    // 导致所有工具的数值参数被丢弃，模型看到的永远是默认值）。
    // 这类 bug 只能靠单测防住，代码审查和手工测试都发现不了。
    testImplementation("junit:junit:4.13.2")
    // ★ 2026-10-01：org.json 真实现 —— android.jar 的 JSONObject 是存根，
    //   纯 JVM 测试调它会抛 "Method not mocked"（GoalStore/AutoMemory 全用它）。
    //   带上真实现后测试能真正读写 JSON，比 returnDefaultValues 更接近生产行为。
    testImplementation("org.json:json:20231013")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    // 【MockWebServer】端到端链路测试的假网关。
    //
    // 【为什么不用 JDK 自带的 com.sun.net.httpserver】Android 单元测试的
    // classpath 是 android.jar（只有 API 存根，**不含 com.sun.* 实现**），
    // 所以 `HttpServer.create()` 编译期就报 Unresolved reference。
    // 实测踩过（CI #148 共 31 处错误）。
    //
    // MockWebServer 与生产用的 okhttp 同版本线，能精确控制 SSE 分片、
    // 响应码和「挂着不发」的悬挂场景 —— 正好覆盖 watchdog / 重试 /
    // 中断这几条最难手工构造的路径。
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
