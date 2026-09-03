import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
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

    buildTypes {
        release {
            isMinifyEnabled = false
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
