package com.example.casdonlink

import com.example.casdonlink.connection.Diagnostics
import com.example.casdonlink.connection.TelnetClient

/**
 * 当前设备连接会话的全局持有者：连接建立后由 [DeviceActivity] 及其子页面共享，
 * 避免在各页面之间搬运会话对象。会话只在用户主动断开或检测到设备断开时关闭。
 */
internal object DeviceSessionHolder {
    @Volatile
    private var current: TelnetClient.Session? = null

    @Volatile
    var host: String = ""
        private set

    @Volatile
    var port: Int = 23
        private set

    @Volatile
    var username: String = ""
        private set

    @Synchronized
    fun store(session: TelnetClient.Session, host: String, port: Int, username: String = "") {
        current = session
        this.host = host
        this.port = port
        this.username = username
    }

    @Synchronized
    fun session(): TelnetClient.Session? = current

    fun isActive(): Boolean = current?.isAlive() == true

    /** 主动断开连接并清空会话。 */
    @Synchronized
    fun disconnect() {
        Diagnostics.log("主动断开设备连接")
        runCatching { current?.close() }
        current = null
        host = ""
        port = 23
        username = ""
    }

    /** 会话已被设备断开时清理引用（不重复关闭）。 */
    @Synchronized
    fun release() {
        current = null
        host = ""
        port = 23
        username = ""
    }
}
