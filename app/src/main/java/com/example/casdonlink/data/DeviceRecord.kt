package com.example.casdonlink.data

data class DeviceRecord(
    val ipAddress: String,
    val port: Int = 23,
    val lastConnectedAt: Long = System.currentTimeMillis()
)
