package com.example.casdonlink.data

import android.content.Context

/**
 * 终端显示设置（目前只有字体大小）。
 * 选项只影响手机侧终端输出，不改变设备端。
 */
class TerminalSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun fontSize(): FontSize = runCatching {
        FontSize.valueOf(preferences.getString(KEY_FONT_SIZE, FontSize.SYSTEM.name)!!)
    }.getOrDefault(FontSize.SYSTEM)

    fun setFontSize(size: FontSize) {
        preferences.edit().putString(KEY_FONT_SIZE, size.name).apply()
    }

    /** 终端字体档位；SYSTEM 使用样式里的默认字号并跟随系统字体缩放。 */
    enum class FontSize(val label: String, val sp: Float?) {
        SYSTEM("跟随系统", null),
        SMALL("小", 11f),
        MEDIUM("中", 13f),
        LARGE("大", 16f)
    }

    private companion object {
        const val PREFERENCES = "terminal_settings"
        const val KEY_FONT_SIZE = "font_size"
    }
}
