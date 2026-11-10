import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    // AGP 9 起 Kotlin 编译由 AGP 内置（built-in Kotlin）提供，不再需要 kotlin-android 插件。
    alias(libs.plugins.android.application)
}

// APK 产物时间戳与页脚展示的编译时间取同一次构建时刻，避免两者出现秒级偏差。
val buildMoment = Date()
val apkTimestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(buildMoment)
val buildTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(buildMoment)

// 版本号规范（见 docs/TECHNICAL_DESIGN.md 第 11 节）：MAJOR.MINOR.PATCH，每次改动必须递增其一。
//   MAJOR 不兼容变更 / MINOR 新增功能 / PATCH 修复、样式与重构；一批改动按其中最高级别递增。
val appMajor = 1
val appMinor = 8
val appPatch = 0
val appVersionName = "$appMajor.$appMinor.$appPatch"
// versionCode 由版本名派生，避免两者不同步：MAJOR*10000 + MINOR*100 + PATCH。
val appVersionCode = appMajor * 10000 + appMinor * 100 + appPatch

// 仅 debug 构建可指向本地授权平台联调；release 始终使用代码内混淆后的生产地址。
val authBaseUrlOverride = (project.findProperty("authBaseUrl") as String?).orEmpty()
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

android {
    namespace = "com.example.casdonlink"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.casdonlink"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        // 编译时间注入 BuildConfig，供首页底部「版本与版权」声明展示。
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
        // 授权服务本地联调地址；默认空串，release 不会使用它。
        buildConfigField("String", "AUTH_BASE_URL_OVERRIDE", "\"$authBaseUrlOverride\"")

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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        // 首页页脚的版本/编译时间来自 BuildConfig，必须显式开启（AGP 8+ 默认关闭）。
        buildConfig = true
    }
}

// 统一 APK 产物命名：CasdonLink_<debug|release>_V<版本名>_<时间戳>.apk
// AGP 9 移除了旧版 applicationVariants 变体 API，改用 androidComponents 的 VariantOutput.outputFileName。
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set(
                "CasdonLink_${variant.buildType}_V${appVersionName}_${apkTimestamp}.apk"
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
