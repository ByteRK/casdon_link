import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// APK 产物时间戳取本项目配置阶段时刻，保证同一次构建内所有变体命名一致。
val apkTimestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

android {
    namespace = "com.example.casdonlink"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.casdonlink"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    // 统一 APK 产物命名：CasdonLink_<debug|release>_V<版本名>_<时间戳>.apk
    applicationVariants.all {
        val buildTypeName = buildType.name
        val appVersion = versionName
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "CasdonLink_${buildTypeName}_V${appVersion}_${apkTimestamp}.apk"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
