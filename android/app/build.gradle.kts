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
            // P0-2（审计 2026-11-11）：开 R8 代码收缩+资源收缩（dex 18.12→预计 7~9.5MiB）；
            // 签名复用 AGP debug keystore（同签名与调试渠道可互相覆盖升级，minSdk23 保 v1 签名自动带上）
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // P2（审计 2026-11-11）：locale 过滤——app 自身仅默认 values（中文硬编码），其余几十语系
    // 全来自 compose/material3/media3/work 等 AAR 传递资源；只保留中英，砍掉翻译字符串与复数规则
    androidResources {
        localeFilters += listOf("zh", "en")
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
    // P2（审计 2026-11-11）：ui-tooling-preview 死依赖——全项目无 @Preview，预览注解不进运行时也无引用
    implementation(libs.androidx.compose.material3)
    // P2：androidx.tv:tv-material 死依赖——0 引用（TV 走焦全走 Compose 原生 focusRequester）
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
    // 扫码远程输入二维码：纯 JVM zxing core 离线生成（无 GMS 依赖）
    implementation(libs.zxing.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // 单元测试里 android.jar 的 org.json 是空桩，需放真实实现（测试 classpath 优先于 android.jar）
    testImplementation(libs.junit)
    testImplementation(libs.json)
    // P0-1（审计 2026-11-11）：拼音首字母改离线表（assets/pin_index.bin，tools/PinTableGen.java 用 ICU 预生成），
    // ICU4J 退出运行时（省 ~10.3MiB）；仅测试端保留：PinTableParityTest 全表逐字校验防漂移+锚点单调断言
    testImplementation(libs.icu4j)
}
