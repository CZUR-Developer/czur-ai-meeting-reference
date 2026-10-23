plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

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
    sourceSets.getByName("main").java.srcDir("src/basic-ui/java")
    sourceSets.getByName("debug").java.srcDir("src/sdk-debug/java")
    sourceSets.getByName("debug").manifest.srcFile("src/sdk-debug/AndroidManifest.xml")
    sourceSets.getByName("minifiedDebug") {
        java.srcDir("src/sdk-debug/java")
        manifest.srcFile("src/sdk-debug/AndroidManifest.xml")
        res.srcDir("src/debug/res")
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
