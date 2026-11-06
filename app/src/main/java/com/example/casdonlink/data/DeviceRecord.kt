package com.example.casdonlink.data

data class DeviceRecord(
    val ipAddress: String,
    val port: Int = 23,
    /** 登录用户名（设备无需登录时为空）；密码出于安全考虑不做持久化。 */
    val username: String = "",
    val lastConnectedAt: Long = System.currentTimeMillis()
)
