package com.example.casdonlink.ui

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

/**
 * 全应用都是固定深色背景，所以系统栏需要固定使用**浅色**图标。
 *
 * 不能直接用 `enableEdgeToEdge()`：它默认的 `SystemBarStyle.auto` 按系统是否开着深色模式
 * 决定图标深浅（浅色模式下会请求深色图标），落在 `@color/background` 上就看不见了。
 * 这里显式指定深色系统栏（透明底 + 浅色图标），与主题里的 `windowLightStatusBar=false` 保持一致。
 */
fun ComponentActivity.applyDarkSystemBars() {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
    )
}
