import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
}

// 签名配置:从项目根的 keystore.properties 读取(该文件与 release.jks 均 gitignore,
// 不入库)。没有 keystore.properties 时 release 不签名(方便别人拉下来自己配密钥)。
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.example.touchpad"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.conmarn.app"
        minSdk = 30          // Wi-Fi 方案无高版本 API 要求,30 足够(可再降低)
        targetSdk = 37
        versionCode = 32
        versionName = "3.1.1"
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    // 内嵌的 llama-server 要以 lib*.so 的名字躺在 jniLibs/arm64-v8a 里(jniLibs),
    // 装机时由 PackageManager 解到 nativeLibraryDir 才能执行 —— 那是 Android 10+
    // 唯一允许 exec 的目录(app 私有目录被 SELinux W^X 挡住,chmod +x 也没用)。
    // ⚠️ 必须 useLegacyPackaging:默认情况下 .so 是压缩着留在 APK 里的(直接 mmap 加载),
    // 磁盘上根本没有文件,也就没有东西可 exec。见 patch_jnilibs.py 的说明。
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // 沙盒那套用户态(busybox)是个**静态链接的可执行文件**,不是共享库。
            // 打包时 AGP 会用 llvm-strip 挨个处理 jniLibs —— 对一个 EXEC 型 ELF
            // 干这件事,轻则原样返回,重则把入口附近改坏,而**两种都不会让构建失败**。
            // 症状是装上去 exec 直接崩,查起来极贵。所以明确告诉它别碰。
            keepDebugSymbols += "**/libbusybox.so"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// AGP 9 内置 Kotlin:用 compilerOptions 配置 JVM 目标(替代已移除的 kotlinOptions)
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    // ZXing 内部依赖 androidx.core(ContextCompat 等),必须显式引入,否则扫码 Activity 启动即崩
    implementation("androidx.core:core-ktx:1.13.1")
    // 二维码扫描(手机扫电脑上的配对二维码,代替手动输 32 位种子)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // PDF 的**文字层**(不是渲染页面)。Apache-2.0,约 +9MB。
    // 扫描版 PDF 里没有文字层,那类走下面的 OCR。
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // 图片里的字。**内置中文模型、离线可用**,不依赖 Google 服务和网络。
    // 用 text-recognition-chinese 而不是 play-services 那一版:后者的模型要联网下载。
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // 只有 FastPathTest 用。快通道是一层「规则切错就会做错事」的东西,
    // 必须能被 eval/cases.txt 逐条盯着 —— 纯 JVM 测试,不用连手机。
    //   ./gradlew :app:testDebugUnitTest
    testImplementation("junit:junit:4.13.2")
    // ApiDraftMath 要解析 OpenAPI/curl 里的 JSON。Android 的 org.json 在纯 JVM 测试里
    // 是空壳(调用就抛 "not mocked"),所以给测试单独挂一份真正的实现 —— 只在
    // testImplementation 上,不进 APK(手机上用的仍然是 Android 系统那份)。
    testImplementation("org.json:json:20240303")
}

// 让测试里的 println 直接进控制台:FastPathTest 的覆盖报告是靠它打出来的,
// 默认只有 --info 才显示,那样等于没写。
tasks.withType<Test>().configureEach {
    testLogging { showStandardStreams = true }
    // ★ 只有 `gradle test -DmqttLive=1` 时才真的去连公共代理（见 MqttLiveTest）。
    //   默认是空串 = 那条用例自己跳过，所以平时跑测试**不联网**。
    //   参数从 Gradle 的 JVM 传进测试 JVM —— 测试进程不继承命令行上的 -D。
    systemProperty("mqttLive", System.getProperty("mqttLive") ?: "")
    // 同上：`gradle test -DliveApi=1` 才真的去访问连接器目录里那几家公开接口。
    systemProperty("liveApi", System.getProperty("liveApi") ?: "")
}
