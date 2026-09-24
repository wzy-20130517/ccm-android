plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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

    buildFeatures { compose = true }

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

    packaging {
        // 不压缩 so，保证解压后可直接 exec
        jniLibs { useLegacyPackaging = true }
    }
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
}
