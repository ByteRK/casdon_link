package com.example.casdonlink

import com.example.casdonlink.connection.Diagnostics
import com.example.casdonlink.connection.TelnetClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 当前设备连接会话的全局持有者：连接建立后由 [DeviceActivity] 及其子页面共享，
 * 避免在各页面之间搬运会话对象。会话只在用户主动断开或检测到设备断开时关闭。
 *
 * 部分 ROM 会在应用退到后台一段时间后中止既有长连接（回到前台的瞬间读到 ECONNABORTED），
 * 表现为“选完文件回来就提示设备已断开”。因此这里提供 [ensure]：会话失效时按原地址
 * 静默重连一次，调用方拿到的是可用会话，无需感知重连过程。
 */
internal object DeviceSessionHolder {
    @Volatile
    private var current: TelnetClient.Session? = null

    /** 重连所需凭证：仅保存在内存中，不落盘、不写入日志。 */
    @Volatile
    private var credentials: TelnetClient.Credentials? = null

    /** 串行化重连，避免多个页面同时触发建立多条连接。 */
    private val reconnectMutex = Mutex()

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
    fun store(
        session: TelnetClient.Session,
        host: String,
        port: Int,
        username: String = "",
        credentials: TelnetClient.Credentials? = null
    ) {
        current = session
        this.host = host
        this.port = port
        this.username = username
        this.credentials = credentials
    }

    @Synchronized
    fun session(): TelnetClient.Session? = current

    fun isActive(): Boolean = current?.isAlive() == true

    /** 是否存在可重连的目标地址。 */
    fun hasTarget(): Boolean = host.isNotBlank()

    /**
     * 返回可用的会话：连接仍存活时直接返回；已失效则用保存的地址与凭证重连一次。
     * 没有目标地址或重连失败时返回 null，由调用方提示用户重新连接。
     */
    suspend fun ensure(): TelnetClient.Session? {
        current?.takeIf { it.isAlive() }?.let { return it }
        return reconnectMutex.withLock {
            current?.takeIf { it.isAlive() }?.let { return@withLock it }
            val target = host
            if (target.isBlank()) return@withLock null
            Diagnostics.log("会话已失效，尝试自动重连 $target:$port")
            val restored = runCatching {
                TelnetClient().connect(target, port, credentials = credentials)
            }.onFailure {
                Diagnostics.log("自动重连失败：${it.javaClass.simpleName}: ${it.message}")
            }.getOrNull() ?: return@withLock null
            // 重连成功后保留原凭证与显示名，供后续再次重连使用。
            store(restored, target, port, username, credentials)
            Diagnostics.log("自动重连成功")
            restored
        }
    }

    /** 主动断开连接并清空会话。 */
    @Synchronized
    fun disconnect() {
        Diagnostics.log("主动断开设备连接")
        runCatching { current?.close() }
        clearLocked()
    }

    /** 会话已被设备断开时清理引用（不重复关闭）。 */
    @Synchronized
    fun release() {
        clearLocked()
    }

    private fun clearLocked() {
        current = null
        credentials = null
        host = ""
        port = 23
        username = ""
    }
}
