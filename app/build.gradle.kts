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
        applicationId = "com.example.touchpad"
        minSdk = 30          // Wi-Fi 方案无高版本 API 要求,30 足够(可再降低)
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
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
}
