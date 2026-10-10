plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

tasks.register("checkStructure") {
    group = "verification"
    description = "Checks that reference repository directories are present."
    doLast {
        listOf("backend-core", "backend-reference", "android-reference-app", "web-reference", "contracts", "system-audio-contract", "tools", "docs", "deploy")
            .map(::file)
            .forEach { require(it.isDirectory) { "Missing module directory: ${it.path}" } }
    }
}
