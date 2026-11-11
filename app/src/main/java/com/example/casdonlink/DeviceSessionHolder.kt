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

    /** 授权/快捷操作主动重启设备期间为 true，设备页看到断开时不应立即退回首页。 */
    @Volatile
    private var rebooting = false

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
        rebooting = false
    }

    @Synchronized
    fun session(): TelnetClient.Session? = current

    fun isActive(): Boolean = current?.isAlive() == true

    /** 是否存在可重连的目标地址。 */
    fun hasTarget(): Boolean = host.isNotBlank()

    /** 标记「即将主动重启」，设备断开属于预期行为，设备页不要据此退回首页。 */
    fun beginReboot() {
        rebooting = true
    }

    fun isRebooting(): Boolean = rebooting

    /** 操作页被销毁/取消时解除重启等待标记，避免设备页永久跳过自动恢复。 */
    fun endReboot() {
        rebooting = false
    }

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

    /**
     * 授权/快捷操作重启设备后，按原地址轮询等待设备重新上线。
     * 期间不清空地址与凭证；成功后把新会话写回全局持有者，设备页恢复时可直接复用。
     * [isCancelled] 为 true 时提前结束等待并返回 null（设备可能仍在重启），
     * 由调用方决定如何提示，不视为设备故障。
     */
    suspend fun reconnectAfterReboot(
        timeoutMs: Long = 90_000,
        previousBootId: String? = null,
        isCancelled: () -> Boolean = { false }
    ): TelnetClient.Session? {
        val target = host
        val targetPort = port
        val targetCredentials = credentials
        if (target.isBlank()) {
            rebooting = false
            return null
        }
        rebooting = true
        // 关键：reboot 发出后旧 socket 可能还保持打开，但设备已经不再响应命令。
        // 不能把旧会话当成"已重新上线"，必须先丢弃它，只认新建立的 Telnet 握手。
        val stale = synchronized(this) {
            val old = current
            current = null
            old
        }
        runCatching { stale?.close() }
        Diagnostics.log("等待设备重启后重新上线：$target:$targetPort")
        // 旧连接关闭后设备可能仍在关机过程中，先留出几秒再开始轮询，避免又连回旧系统。
        kotlinx.coroutines.delay(2_000)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isCancelled()) {
                rebooting = false
                Diagnostics.log("已取消等待设备重新上线")
                return null
            }
            val restored = runCatching {
                TelnetClient().connect(target, targetPort, timeoutMs = 3_000, credentials = targetCredentials)
            }.onFailure {
                Diagnostics.log("重启后重连失败：${it.javaClass.simpleName}: ${it.message}")
            }.getOrNull()
            if (restored != null) {
                // 用 boot_id 判断是否真的换了启动周期：同一 boot_id 说明设备还没重启完，
                // 这个连接仍然属于旧系统，必须丢弃后继续等。
                if (!previousBootId.isNullOrBlank()) {
                    val bootId = runCatching {
                        restored.execute("cat /proc/sys/kernel/random/boot_id", timeoutMs = 3_000).trim()
                    }.getOrNull()
                    if (bootId.isNullOrBlank() || bootId == previousBootId) {
                        Diagnostics.log("设备尚未完成重启，继续等待")
                        runCatching { restored.close() }
                        kotlinx.coroutines.delay(2_000)
                        continue
                    }
                }
                store(restored, target, targetPort, username, targetCredentials)
                Diagnostics.log("设备已重新上线")
                return restored
            }
            kotlinx.coroutines.delay(2_000)
        }
        rebooting = false
        Diagnostics.log("等待设备重新上线超时")
        return null
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
        // 主动重启期间保留地址和凭证，供 [reconnectAfterReboot] 使用。
        if (rebooting) {
            current = null
            return
        }
        clearLocked()
    }

    private fun clearLocked() {
        current = null
        credentials = null
        host = ""
        port = 23
        username = ""
        rebooting = false
    }
}
