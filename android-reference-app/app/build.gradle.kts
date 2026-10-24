import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 部署覆盖：仓内只保留占位地址，本机用被忽略的 deployment.local.properties 注入。
// 干净 clone 构建出占位包，提醒伙伴必须替换地址；本机联调不受影响。
val localDeploymentFile = rootProject.file("deployment.local.properties")
val localDeployment = Properties().apply {
    if (localDeploymentFile.isFile) localDeploymentFile.inputStream().use { load(it) }
}
fun deploymentValue(key: String, fallback: String): String =
    localDeployment.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

val deploymentBackendUrl = deploymentValue("backendUrl", "https://replace-with-your-backend.example")
val deploymentResultWebUrl = deploymentValue("resultWebUrl", "https://replace-with-your-result-web.example/index.html")
// Debug 构建要信任的自签 CA 由本地文件提供；仓内不再携带任何证书。
val localDebugCa = localDeployment.getProperty("debugCaFile")?.trim()?.takeIf { it.isNotEmpty() }
    ?.let { rootProject.file(it) }?.takeIf { it.isFile }

android {
    namespace = "com.czur.aimeeting.reference"
    compileSdk = 35
    buildFeatures { buildConfig = true }

    defaultConfig {
        applicationId = "com.czur.aimeeting.reference"
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "BACKEND_URL", "\"$deploymentBackendUrl\"")
        buildConfigField("String", "RESULT_WEB_URL", "\"$deploymentResultWebUrl\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        // 普通开发/调试。
        debug { }
        // 消费端 R8 验证固定用这个构建类型，不再通过构建参数切换。
        create("minifiedDebug") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
        release { }
    }

    // 单一路径：字幕/浮窗源码只保留 basic-ui；公开 API 探针只进 Debug。
    // Debug 网络安全配置由本地 CA 是否存在决定：仓内不带 .pem，也不会引用缺失资源。
    val generatedDebugRes = layout.buildDirectory.dir("generated/deployment-res").get().asFile
    run {
        generatedDebugRes.deleteRecursively()
        val xmlDir = File(generatedDebugRes, "xml").apply { mkdirs() }
        val rawDir = File(generatedDebugRes, "raw").apply { mkdirs() }
        val overrides = if (localDebugCa != null) {
            File(rawDir, "reference_debug_ca.pem").writeBytes(localDebugCa.readBytes())
            "    <debug-overrides>\n" +
                "        <trust-anchors>\n" +
                "            <certificates src=\"@raw/reference_debug_ca\" />\n" +
                "        </trust-anchors>\n" +
                "    </debug-overrides>\n"
        } else ""
        File(xmlDir, "reference_debug_network_security.xml").writeText(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<!-- 构建时生成：仅当本地提供自签 CA 时才信任它，仓内不带证书。 -->\n" +
                "<network-security-config>\n" +
                "    <base-config cleartextTrafficPermitted=\"true\">\n" +
                "        <trust-anchors>\n" +
                "            <certificates src=\"system\" />\n" +
                "        </trust-anchors>\n" +
                "    </base-config>\n" +
                overrides +
                "</network-security-config>\n")
        logger.lifecycle("部署地址 backend=$deploymentBackendUrl result=$deploymentResultWebUrl debugCa=${localDebugCa?.name ?: "无"}")
    }

    sourceSets.getByName("main").java.srcDir("src/basic-ui/java")
    sourceSets.getByName("debug").java.srcDir("src/sdk-debug/java")
    sourceSets.getByName("debug").manifest.srcFile("src/sdk-debug/AndroidManifest.xml")
    sourceSets.getByName("debug").res.srcDir(generatedDebugRes)
    sourceSets.getByName("minifiedDebug") {
        java.srcDir("src/sdk-debug/java")
        manifest.srcFile("src/sdk-debug/AndroidManifest.xml")
        res.srcDir("src/debug/res")
        res.srcDir(generatedDebugRes)
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // Mini 悬浮窗直接复用国内版 APNG 素材；只引入独立图片显示库，不带 Starry 业务依赖。
    implementation("com.github.penfeizhou.android.animation:apng:3.0.5")
    // APNG 的父类实现 Animatable2Compat，但其 POM 只声明运行依赖；显式补齐编译可见性。
    implementation("androidx.vectordrawable:vectordrawable-animated:1.1.0")
    // 业务 AAR 以工程依赖接入；它自带 core、契约、设备能力和模型传输（含 SDK TextView）。
    implementation(project(":meeting-android"))
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
}
