package com.example.casdonlink.data

data class DeviceRecord(
    val ipAddress: String,
    val port: Int = 23,
    /**
     * 使用者自定义的设备别名。旧记录没有该字段时为空串，
     * 界面按 `IP:端口` 回落显示，不影响已保存密码的复用。
     */
    val name: String = "",
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

    /** 记录的唯一键：同一 IP 的不同端口是两台不同设备。 */
    val address: String get() = "$ipAddress:$port"
}
