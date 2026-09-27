plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    // 【2026-09-27 新增】kotlinx.serialization 编译器插件。
    // 用途：core/ 层的 JSON 序列化（Provider 配置、会话存储、消息模型）。
    // 为什么不用 Gson：Gson 靠反射，Kotlin data class 的默认值/非空类型在反序列化时
    // 会被绕过（字段缺失就塞 null 进非空属性，运行时才炸）。kotlinx.serialization
    // 是编译期生成序列化器，类型安全 + 零反射（APK 体积也小）。
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0" apply false
}
