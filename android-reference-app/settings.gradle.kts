// Standalone settings for contributors opening this directory in Android Studio.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "ai-meeting-reference-app"

// 业务 AAR（:meeting-android）不依赖参考 App。只有存在部署配置时才纳入 :app，
// 避免只构建 AAR 的合作伙伴被 app/build.gradle.kts 的部署校验拦住。
// 参考 App 需要 android-reference-app/deployment.local.properties，
// 或通过 REFERENCE_DEPLOYMENT_FILE 指定另一份仓外配置。
val deploymentFileName = System.getenv("REFERENCE_DEPLOYMENT_FILE")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: "deployment.local.properties"
val deploymentFile = settingsDir.resolve(deploymentFileName)
if (deploymentFile.isFile) {
    include(":app")
} else {
    println(
        "未找到 $deploymentFile，跳过 :app；只构建 :meeting-android 等模块不受影响。" +
            "需要参考 App 时先复制 deployment.local.properties.example。"
    )
}
include(":model-runtime-contract")
include(":meeting-android")
include(":meeting-client-core")
