import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 部署参数只在一处：app/src/main/java/.../meeting/ReferenceDeployment.kt（代码即配置）。
// 这里只做两件构建期的事：占位值时提醒、Debug 自签 CA 存在时生成网络安全配置。
val deploymentSourceFile = file("src/main/java/com/czur/aimeeting/reference/meeting/ReferenceDeployment.kt")
val deploymentSource = if (deploymentSourceFile.isFile) deploymentSourceFile.readText() else ""
// Debug 信任的自签 CA 走固定路径，存在即生效，不再需要额外配置项。
val localDebugCa = file("src/debug/res/raw/reference_debug_ca.pem").takeIf { it.isFile }

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
        val overrides = if (localDebugCa != null) {
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
        logger.lifecycle("部署配置来源=ReferenceDeployment.kt debugCa=${localDebugCa?.name ?: "无"}")
        if (deploymentSource.contains("replace-with-your-backend")) {
            logger.warn(
                "部署地址仍是占位值：修改 " +
                    "app/src/main/java/com/czur/aimeeting/reference/meeting/ReferenceDeployment.kt " +
                    "里的 BACKEND_URL 与 RESULT_WEB_URL 后重新构建；本次产物会提示未配置，不能直接联调。"
            )
        }
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
