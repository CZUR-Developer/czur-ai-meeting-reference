plugins {
    id("com.android.application")
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
}

dependencies {
    implementation(project(":model-runtime-contract"))
    testImplementation("junit:junit:4.13.2")
}

// 参考应用通过 system-audio-contract 的公开接口获取音频；特权实现由
// 设备厂商以 SDK 依赖注入，不在此公开工程中携带签名二进制。
