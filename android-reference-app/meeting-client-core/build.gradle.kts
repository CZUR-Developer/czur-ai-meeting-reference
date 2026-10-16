plugins {
    `java-library`
    kotlin("jvm")
}

version = "0.1.0"

// 保持原 Java 17 字节码基线，由 Kotlin 编写；D8/APK 构建验证实际消费者兼容。
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.ENABLE)
    }
}

dependencies {
    api(project(":model-runtime-contract"))
    // Android 已提供 org.json。普通 JAR 不打包 JSON 或公共模型契约，防止 AAR/APK 重复类。
    compileOnly("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
