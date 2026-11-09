package com.example.casdonlink.files

import java.util.Locale

/** 未知大小的占位符：设备端 `stat`/`wc` 都不可用时使用，避免把「不知道」显示成 0 B。 */
const val UNKNOWN_SIZE = -1L

/**
 * 把字节数格式化成直观的大小文本。
 *
 * 逐级换算到 KB/MB/GB/TB，数值 < 100 时保留一位小数（1.5 MB），
 * 更大时取整（128 MB），避免列表里出现一长串难以比较的数字。
 */
fun formatByteSize(bytes: Long): String {
    if (bytes < 0) return "大小未知"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB", "PB")
    var value = bytes.toDouble() / 1024
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    val text = if (value >= 100) {
        String.format(Locale.US, "%.0f", value)
    } else {
        String.format(Locale.US, "%.1f", value)
    }
    return "$text ${units[index]}"
}
