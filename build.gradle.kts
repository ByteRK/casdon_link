// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    // Kotlin 编译由 AGP 9 内置支持提供，无需额外声明 Kotlin Gradle 插件。
    alias(libs.plugins.android.application) apply false
}