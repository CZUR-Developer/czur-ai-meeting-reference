import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 用生成的 JAR 而不是工程依赖：业务 AAR 交给宿主时应自带 core/契约，
// 裸 AAR 没有 POM，不能靠传递工程依赖。
evaluationDependsOn(":meeting-client-core")
evaluationDependsOn(":model-runtime-contract")
val coreJar = project(":meeting-client-core").tasks.named("jar")
val contractJar = project(":model-runtime-contract").tasks.named("jar")

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

/**
 * 业务 AAR：开源的服务端/模型通信与会议门面。
 *
 * 设备 AAR（闭源）只提供 MIC/Broker 采集、投屏截图和 native；本模块依赖它，
 * 再结合开源的 meeting-client-core 与 model-runtime-contract，产出宿主唯一依赖的
 * 业务 AAR。宿主需要改模型传输或门面时，改本模块源码重新构建即可，不用动设备 AAR。
 */
val deviceSdkAar = providers.gradleProperty("deviceSdkAar").orNull
require(deviceSdkAar == null || file(deviceSdkAar).isFile) {
    "deviceSdkAar 必须指向已构建的 ai-meeting-device AAR"
}

// AGP 不允许 Android library 直接依赖本地 .aar 再产出 AAR；把设备 AAR 的
// classes.jar 与 jni 解包成本模块的普通文件依赖，业务 AAR 才能把设备能力一起打包。
val deviceUnpacked = layout.buildDirectory.dir("device-aar")
val unpackDeviceAar = deviceSdkAar?.let { path ->
    tasks.register<Sync>("unpackDeviceAar") {
        from(zipTree(file(path))) { include("classes.jar", "jni/**", "proguard.txt") }
        into(deviceUnpacked)
    }
}

android {
    namespace = "com.czur.aimeeting.sdk"
    compileSdk = 35

    defaultConfig {
        // 与设备 AAR 和现有 CZUR App 保持一致；参考 App 的 34 由应用自身决定。
        minSdk = 31
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
    if (unpackDeviceAar != null) {
        sourceSets.getByName("main").jniLibs.srcDir(deviceUnpacked.map { it.dir("jni") })
        tasks.matching { it.name.contains("JniLibFolders") }.configureEach {
            dependsOn(unpackDeviceAar)
        }
    }
}

dependencies {
    api(files(coreJar))
    api(files(contractJar))
    if (unpackDeviceAar != null) {
        api(files(unpackDeviceAar.map { it.destinationDir.resolve("classes.jar") }))
    }
    implementation("com.squareup.okhttp3:okhttp:5.3.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
