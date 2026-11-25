// Android Studio 工程根配置。实际 APK 位于 app 子模块。
plugins {
    id("com.android.application") version "9.4.0" apply false
    // Android 层统一 Kotlin First，并与交付 SDK 使用同版编译器，避免消费 AAR 时元数据版本不兼容。
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
}
