plugins {
    `java-library`
}

version = "0.1.0"

// core 与 Android App 共用 Java 17；SDK 本身保持 Java 11，最终 APK 的 D8 构建验证兼容。
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

dependencies {
    api(project(":model-runtime-contract"))
    // Android 已提供 org.json。普通 JAR 不打包 JSON 或公共模型契约，防止 AAR/APK 重复类。
    compileOnly("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
