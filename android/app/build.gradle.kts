import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.tzk1986.libretvaz"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tzk1986.libretvaz"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.4.0-m4"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    // B17 点播提速：OkHttp 播放数据源（HTTP/2 多路复用+大连接池，替代 HttpURLConnection）
    implementation(libs.androidx.media3.datasource.okhttp)
    // 用户 2026-11-06 走查 #1：CMS/订阅等普通 GET 也上 OkHttp 共享连接池（版本对齐 media3 传递的 4.12.0）
    implementation(libs.okhttp)
    // B11 视频播放盘缓存 SimpleCache 需要数据库提供器；周期清理任务用 WorkManager
    implementation(libs.androidx.media3.database)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.coil.compose)
    // 拼音首字母索引需与浏览器 Intl(pinyin) 行为一致：桌面 JVM 的 java.text.Collator 非拼音序，统一用 ICU4J
    implementation(libs.icu4j)
    // 扫码远程输入二维码：纯 JVM zxing core 离线生成（无 GMS 依赖）
    implementation(libs.zxing.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // 单元测试里 android.jar 的 org.json 是空桩，需放真实实现（测试 classpath 优先于 android.jar）
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
