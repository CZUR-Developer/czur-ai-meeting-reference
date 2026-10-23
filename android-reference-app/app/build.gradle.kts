import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 部署地址属于合作伙伴的打包输入，不要求终端用户在设备上填写。
 *
 * 默认读取 Android 工程根目录下被忽略的 deployment.local.properties；也可用
 * -PreferenceDeploymentFile 指定仓外文件。单独的同名 -P 地址参数优先于文件，
 * 未配置时保留空值，由 App 提示联系应用提供方，不回退到历史手输地址或 CZUR 服务。
 * 文件只放应用端点；模型 Key 和账号密码不属于这个构建配置。
 */
val deploymentFileProperty = providers.gradleProperty("referenceDeploymentFile").orNull
val deploymentFile = rootProject.file(deploymentFileProperty ?: "deployment.local.properties")
require(deploymentFileProperty == null || deploymentFile.isFile) {
    "referenceDeploymentFile must point to an existing .properties file"
}
val deploymentProperties = Properties().apply {
    if (deploymentFile.isFile) deploymentFile.inputStream().use { load(it) }
}
fun referenceDeploymentValue(key: String): String =
    (providers.gradleProperty(key).orNull ?: deploymentProperties.getProperty(key).orEmpty()).trim()

/** BuildConfig 的 String 字面量由 AGP 生成 Java 源码；转义只影响源码表示，不修改 URL。 */
fun buildConfigString(value: String): String = "\"" + value
    .replace("\\", "\\\\").replace("\"", "\\\"")
    .replace("\r", "\\r").replace("\n", "\\n") + "\""

/**
 * 只为 Debug 生成可选的本机 CA 信任资源，Release 的 source set 不引用这些文件。
 *
 * CA 是构建输入，实际文件留在仓外；未传属性时仍生成系统信任配置，并移除上次
 * Debug 构建遗留的 CA 资源。保留当前明文调试语义，SDK 自身的 HTTPS 校验不变。
 */
abstract class GenerateReferenceDebugTlsResources : DefaultTask() {
    @get:InputFile
    @get:Optional
    abstract val caCertificate: RegularFileProperty

    @get:OutputDirectory
    abstract val resourceDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val output = resourceDirectory.get().asFile
        val caOutput = output.resolve("raw/reference_debug_ca.pem")
        val caInput = caCertificate.orNull?.asFile
        if (caInput != null) {
            caOutput.parentFile.mkdirs()
            caInput.copyTo(caOutput, overwrite = true)
        } else {
            caOutput.delete()
        }
        val debugTrust = if (caInput == null) "" else """
            <debug-overrides>
                <trust-anchors>
                    <certificates src="@raw/reference_debug_ca" />
                </trust-anchors>
            </debug-overrides>
        """.trimIndent().prependIndent("    ") + "\n"
        val configuration = output.resolve("xml/reference_debug_network_security.xml")
        configuration.parentFile.mkdirs()
        configuration.writeText("""
            <?xml version="1.0" encoding="utf-8"?>
            <network-security-config>
                <base-config cleartextTrafficPermitted="true">
                    <trust-anchors>
                        <certificates src="system" />
                    </trust-anchors>
                </base-config>
        """.trimIndent() + "\n" + debugTrust + "</network-security-config>\n")
    }
}

// 参考 App 只有一条构建路径：业务 AAR 工程依赖 + 交付的设备 AAR。
val deviceSdkAar = providers.gradleProperty("deviceSdkAar").orNull
require(!deviceSdkAar.isNullOrBlank() && file(deviceSdkAar).isFile) {
    "需要 -PdeviceSdkAar=/absolute/path/ai-meeting-device-0.5.0-release.aar"
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
        buildConfigField("String", "REFERENCE_BACKEND_URL", buildConfigString(referenceDeploymentValue("referenceBackendUrl")))
        buildConfigField("String", "REFERENCE_RESULT_WEB_URL", buildConfigString(referenceDeploymentValue("referenceResultWebUrl")))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        // 最小混淆构建用于验证 AAR 的消费端规则；默认关闭，验证时传 -PreferenceMinify。
        // debug 变体保留局域网调试 CA，release 的调试 CA 只用于可信部署。
        debug {
            isMinifyEnabled = providers.gradleProperty("referenceMinify").isPresent
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
        release {
            isMinifyEnabled = providers.gradleProperty("referenceMinify").isPresent
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    // 单一路径：字幕/浮窗源码只保留 basic-ui；公开 API 探针只进 Debug。
    sourceSets.getByName("main").java.srcDir("src/basic-ui/java")
    sourceSets.getByName("debug").java.srcDir("src/sdk-debug/java")
    sourceSets.getByName("debug").manifest.srcFile("src/sdk-debug/AndroidManifest.xml")
    testOptions { unitTests.isIncludeAndroidResources = true }
}

// 使用 AGP 的生成资源接缝让 Debug 资源任务自动加入构建依赖；不写 main/res。
androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        val generateTls = tasks.register<GenerateReferenceDebugTlsResources>(
            "generateReferenceDebugTlsResources"
        ) {
            providers.gradleProperty("referenceDebugCaCertificate").orNull?.let {
                val certificate = File(it)
                require(certificate.isAbsolute) { "referenceDebugCaCertificate requires an absolute CA file path" }
                caCertificate.set(certificate)
            }
            resourceDirectory.set(layout.buildDirectory.dir("generated/referenceDebugTls/res"))
        }
        variant.sources.res?.addGeneratedSourceDirectory(
            generateTls, GenerateReferenceDebugTlsResources::resourceDirectory
        )
    }
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
