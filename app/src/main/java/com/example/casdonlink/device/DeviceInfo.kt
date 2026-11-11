package com.example.casdonlink.device

/**
 * 设备概览信息。字段按 README 承诺的「系统 / 运行时间 / CPU / 内存 / 存储 / 网络」拆分，
 * 设备缺少某项命令时对应字段为空串，由界面决定是否展示。
 */
data class DeviceInfo(
    val host: String,
    val system: String,
    val uptime: String,
    val cpu: String,
    val load: String,
    val memory: String,
    val storage: String,
    val network: String
)
