plugins {
    `java-library`
    kotlin("jvm")
}

version = "0.4.0"

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

// 保留原 Java 17 字节码基线及真实 JVM 默认方法，兼容既有 Java／SDK 消费者。
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.ENABLE)
    }
}
