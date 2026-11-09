package com.example.casdonlink.data

import android.content.Context

/**
 * 完整功能开关。
 *
 * 为降低售后误操作风险，连接成功后默认只开放「修复包」；交互终端、文件管理等
 * 高级操作以及后续新增的受限功能都默认隐藏。只有在首页左上角标题栏连续点击
 * [TAP_COUNT] 下并输入正确密码才能开启，开关保存在本机 SharedPreferences 中，
 * 应用重启、手机重启后依然有效；已开启时再次连续点击同样次数即可关闭。
 */
object FeatureGate {
    /** 开启完整功能所需密码；需要更换密码时只改这一处。 */
    const val UNLOCK_PASSWORD = "Abcd+1234"

    /** 连续点击标题栏触发开关所需的次数。 */
    const val TAP_COUNT = 6

    /** 相邻两次点击的最大间隔，超过后重新计数，避免零散点击被误算。 */
    const val TAP_INTERVAL_MS = 1200L

    private const val PREFERENCES = "feature_gate"
    private const val KEY_FULL_MODE = "full_mode"

    /** 是否已在本机开启完整功能；未开启时设备页只显示修复包。 */
    fun isFullModeEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_FULL_MODE, false)

    /** 持久化完整功能开关，重启应用后仍然生效。 */
    fun setFullModeEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_FULL_MODE, enabled).apply()
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
