plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
}

tasks.register("checkStructure") {
    group = "verification"
    description = "Checks that reference repository directories are present."
    doLast {
        listOf("backend-core", "backend-reference", "android-reference-app", "web-reference", "contracts", "system-audio-contract", "tools", "docs", "deploy")
            .map(::file)
            .forEach { require(it.isDirectory) { "Missing module directory: ${it.path}" } }
        // 自有 Android 应用、公共核心和契约均以 Kotlin 维护。这里只检查受控 src 目录，
        // 不扫描后端、第三方依赖或 build 中自动生成的 Java/AIDL，避免扩大语言迁移范围。
        listOf("app", "meeting-client-core", "model-runtime-contract").forEach { module ->
            val sourceRoot = file("android-reference-app/$module/src")
            val remainingJava = fileTree(sourceRoot) { include("**/*.java") }.files
            require(remainingJava.isEmpty()) {
                "Android Kotlin First: remaining Java sources in $module: " +
                    remainingJava.joinToString { it.relativeTo(projectDir).path }
            }
            require(fileTree(sourceRoot) { include("**/*.kt") }.files.isNotEmpty()) {
                "Android Kotlin First: missing Kotlin sources in $module"
            }
        }
    }
}
