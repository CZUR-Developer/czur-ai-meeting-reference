plugins {
    id("com.android.application")
}

val meetingRuntime = providers.gradleProperty("meetingRuntime").orElse("mock").get()
require(meetingRuntime == "mock" || meetingRuntime == "sdk") {
    "meetingRuntime must be mock or sdk"
}
val sdkAar = providers.gradleProperty("meetingSdkAar").orNull
if (meetingRuntime == "sdk") {
    require(!sdkAar.isNullOrBlank() && file(sdkAar).isFile) {
        "sdk mode requires -PmeetingSdkAar=/absolute/path/ai-meeting-sdk-0.4.0-release.aar"
    }
}

android {
    namespace = "com.czur.aimeeting.reference"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.czur.aimeeting.reference"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets.getByName("main").java.srcDir("src/$meetingRuntime/java")
    sourceSets.getByName("test").java.srcDir("src/test/$meetingRuntime/java")
}

dependencies {
    implementation(project(":model-runtime-contract"))
    if (meetingRuntime == "sdk") {
        implementation(files(sdkAar!!))
        // 本地 AAR 文件不传递 POM 依赖；SDK 的 WebSocket 运行时需要 OkHttp。
        implementation("com.squareup.okhttp3:okhttp:4.12.0")
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
