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
    implementation(libs.coil.compose)
    // 拼音首字母索引需与浏览器 Intl(pinyin) 行为一致：桌面 JVM 的 java.text.Collator 非拼音序，统一用 ICU4J
    implementation(libs.icu4j)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // 单元测试里 android.jar 的 org.json 是空桩，需放真实实现（测试 classpath 优先于 android.jar）
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
