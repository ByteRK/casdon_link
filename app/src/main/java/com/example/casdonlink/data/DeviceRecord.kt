package com.example.casdonlink.data

data class DeviceRecord(
    val ipAddress: String,
    val port: Int = 23,
    /** 登录用户名（设备无需登录时为空）。 */
    val username: String = "",
    /**
     * 登录密码。仅存在于内存中，持久化时由 [SecretCipher] 用 Keystore 密钥加密，
     * 磁盘上没有明文；设备无需登录时为空。
     */
    val password: String = "",
    val lastConnectedAt: Long = System.currentTimeMillis()
) {
    /** 是否保存了可用于自动登录的密码。 */
    val hasPassword: Boolean get() = password.isNotEmpty()
}
