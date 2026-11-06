package com.example.casdonlink.connection

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** socket 读超时：既能让阻塞读定期返回，便于检查协程取消与连接状态。 */
private const val READ_POLL_TIMEOUT_MS = 500

/** 单次等待登录提示的时限；BusyBox 设备首次输出可能较慢。 */
private const val HANDSHAKE_TIMEOUT_MS = 10_000L

class TelnetClient {
    /** Telnet 登录凭证；设备无需登录时传 null。 */
    data class Credentials(val username: String, val password: String)

    /**
     * 建立会话并完成 shell 握手。设备若要求登录，会使用 [credentials] 自动认证；
     * 未提供凭证时会抛出说明性错误，由调用方向用户提示。
     */
    suspend fun connect(
        host: String,
        port: Int = 23,
        timeoutMs: Int = 5_000,
        credentials: Credentials? = null
    ): Session = withContext(Dispatchers.IO) {
        Diagnostics.log("开始连接 $host:$port（超时 ${timeoutMs}ms）")
        val startedAt = System.currentTimeMillis()
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), timeoutMs)
        } catch (error: Exception) {
            Diagnostics.log("TCP 连接失败：${error.javaClass.simpleName}: ${error.message}")
            runCatching { socket.close() }
            throw error
        }
        Diagnostics.log("TCP 连接成功，耗时 ${System.currentTimeMillis() - startedAt}ms")
        socket.soTimeout = READ_POLL_TIMEOUT_MS
        val session = Session(socket)
        try {
            session.handshake(credentials)
        } catch (error: Exception) {
            Diagnostics.log("握手失败：${error.javaClass.simpleName}: ${error.message}")
            runCatching { session.close() }
            throw error
        }
        session
    }

    class Session internal constructor(private val socket: Socket) : AutoCloseable {
        private val input = socket.getInputStream()
        private val output = socket.getOutputStream()
        private val commandMutex = Mutex()
        private val commandBuffer = StringBuilder()
        /** 整个会话共用同一解码器，保证 IAC 协商状态跨读取调用连续。 */
        private val decoder = TelnetDecoder { response -> writeRawBlocking(response) }
        /** 串行化所有 socket 输入读取，避免多个协程抢同一数据流。 */
        private val readLock = Any()
        @Volatile private var reading = false
        /** 有页面正在独占消费数据流时，存活探测需要让出读取。 */
        @Volatile private var busy = false
        @Volatile private var monitoring = false
        @Volatile private var peerClosed = false

        /**
         * 建立 shell 会话：设备若要求登录则用 [credentials] 完成认证，否则直接等待提示符。
         * 设备 telnetd 通常不主动发送 banner，需先发送一次 CRLF 唤起提示符。
         */
        internal fun handshake(credentials: Credentials?) {
            busy = true
            try {
                // 部分设备会把登录密码回显到终端，先登记以便日志脱敏。
                credentials?.password?.let { Diagnostics.registerSecret(it) }
                Diagnostics.log("发送 CRLF 唤起设备提示符")
                writeBlocking("\n")
                var sentUsername = false
                var sentPassword = false
                var lastSent = ""
                repeat(MAX_AUTH_STEPS) {
                    val text = readTextUntil(HANDSHAKE_TIMEOUT_MS) { candidate ->
                        val visible = stripEcho(candidate, lastSent)
                        AUTH_FAILURE.containsMatchIn(visible) ||
                            PASSWORD_PROMPT.containsMatchIn(visible) ||
                            LOGIN_PROMPT.containsMatchIn(visible) ||
                            SHELL_PROMPT.containsMatchIn(visible)
                    }
                    val visible = stripEcho(text, lastSent)
                    when {
                        AUTH_FAILURE.containsMatchIn(visible) ->
                            throw IllegalStateException("Telnet 登录失败：用户名或密码错误")
                        PASSWORD_PROMPT.containsMatchIn(visible) -> {
                            if (sentPassword) throw IllegalStateException("Telnet 登录失败：密码未被接受")
                            val auth = credentials ?: throw IllegalStateException("该设备需要登录，请输入用户名和密码")
                            // 空密码重试必然失败，直接提示用户补全，而不是让设备报 Login incorrect。
                            if (auth.password.isEmpty()) throw IllegalStateException("该设备需要登录，请输入密码")
                            Diagnostics.log("设备要求输入密码")
                            lastSent = auth.password
                            // 密码不得写入日志。
                            writeBlocking(auth.password + "\n", secret = true)
                            sentPassword = true
                        }
                        LOGIN_PROMPT.containsMatchIn(visible) -> {
                            if (sentUsername) throw IllegalStateException("Telnet 登录失败：用户名未被接受")
                            val auth = credentials ?: throw IllegalStateException("该设备需要登录，请输入用户名和密码")
                            if (auth.username.isEmpty()) throw IllegalStateException("该设备需要登录，请输入用户名")
                            Diagnostics.log("设备要求输入用户名")
                            lastSent = auth.username
                            writeBlocking(auth.username + "\n")
                            sentUsername = true
                        }
                        SHELL_PROMPT.containsMatchIn(visible) -> {
                            Diagnostics.log(
                                if (sentUsername || sentPassword) "登录成功，已进入 shell"
                                else "设备无需登录，已进入 shell"
                            )
                            return
                        }
                    }
                }
                throw IllegalStateException("无法识别设备的登录提示，请确认设备 Telnet 服务是否正常")
            } finally {
                busy = false
            }
        }

        /** 去掉刚刚发送内容在终端上的回显，避免其中的 `#`、`$` 被误判为提示符。 */
        private fun stripEcho(text: String, sent: String): String =
            if (sent.isEmpty()) text else text.replace(sent, "")

        /** 阻塞读取并解码，直到 [predicate] 成立或超时。只能在 IO 线程调用。 */
        private fun readTextUntil(timeoutMs: Long, predicate: (String) -> Boolean): String {
            val deadline = System.currentTimeMillis() + timeoutMs
            val collected = StringBuilder()
            val buffer = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                val text = try {
                    readChunk(buffer)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                } ?: throw IllegalStateException("设备关闭了 Telnet 连接")
                collected.append(text)
                if (predicate(collected.toString())) return collected.toString()
            }
            throw IllegalStateException("等待设备响应超时（${timeoutMs / 1000} 秒）")
        }

        /** 读取一段原始数据并解码；返回 null 表示对端已关闭。只能在 IO 线程调用。 */
        private fun readChunk(buffer: ByteArray): String? = synchronized(readLock) {
            val count = input.read(buffer)
            if (count < 0) return null
            if (count == 0) return ""
            Diagnostics.log("收到 ${Diagnostics.describe(buffer, count)}")
            decoder.decode(buffer, count)
        }

        suspend fun readLoop(onText: (String) -> Unit) = withContext(Dispatchers.IO) {
            reading = true
            busy = true
            Diagnostics.log("进入持续读取循环")
            val buffer = ByteArray(4096)
            try {
                while (!socket.isClosed) {
                    val text = try {
                        readChunk(buffer)
                    } catch (_: java.net.SocketTimeoutException) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        continue
                    }
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (text == null) {
                        peerClosed = true
                        Diagnostics.log("设备关闭了 TCP 连接（读到 EOF）")
                        break
                    }
                    if (text.isNotEmpty()) {
                        synchronized(commandBuffer) { commandBuffer.append(text) }
                        onText(text)
                    }
                }
            } finally {
                reading = false
                busy = false
            }
        }

        /**
         * 空闲时监控连接状态：设备断开（对端关闭或连接重置）时回调 [onClosed]。
         * 其他页面正在读写（[busy]）时主动让出 socket，避免抢数据。
         */
        suspend fun monitorClosed(onClosed: () -> Unit) = withContext(Dispatchers.IO) {
            if (monitoring || socket.isClosed) return@withContext
            monitoring = true
            val buffer = ByteArray(256)
            var closed = false
            try {
                while (!socket.isClosed) {
                    if (busy || reading) {
                        kotlinx.coroutines.delay(200)
                        continue
                    }
                    val text = try {
                        readChunk(buffer)
                    } catch (_: java.net.SocketTimeoutException) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        continue // 超时说明连接仍然存活
                    }
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (text == null) {
                        closed = true
                        break
                    }
                    if (text.isNotEmpty()) synchronized(commandBuffer) { commandBuffer.append(text) }
                }
                closed = closed || socket.isClosed
            } catch (_: kotlinx.coroutines.CancellationException) {
                closed = false
            } catch (_: Exception) {
                closed = true
            } finally {
                monitoring = false
            }
            if (closed) {
                peerClosed = true
                Diagnostics.log("检测到设备已断开")
                onClosed()
            }
        }

        /** 连接是否仍然可用（本端未关闭且未检测到对端断开）。 */
        fun isAlive(): Boolean = !socket.isClosed && !peerClosed

        suspend fun execute(command: String, timeoutMs: Long = 8_000): String = commandMutex.withLock {
            withContext(Dispatchers.IO) {
                busy = true
                try {
                    val marker = "__CL_DONE_${java.util.UUID.randomUUID().toString().replace("-", "")}__"
                    synchronized(commandBuffer) { commandBuffer.clear() }
                    write("$command\nprintf '\\n$marker:%s\\n' \"$?\"\n")
                    val deadline = System.currentTimeMillis() + timeoutMs
                    while (System.currentTimeMillis() < deadline) {
                        if (!reading) {
                            val chunk = readTextAvailableBlocking(16_384)
                            if (chunk.isNotEmpty()) synchronized(commandBuffer) { commandBuffer.append(chunk) }
                        }
                        val current = synchronized(commandBuffer) { commandBuffer.toString() }
                        val markerIndex = current.indexOf(marker)
                        if (markerIndex >= 0) {
                            val body = current.substringBefore(marker).substringAfter(command, "")
                            val trailer = current.substring(markerIndex + marker.length)
                            val code = Regex("^:(\\d+)").find(trailer)?.groupValues?.get(1)?.toIntOrNull()
                            if (code != 0) throw IllegalStateException("命令失败（$code）：${body.trim().takeLast(240)}")
                            return@withContext body.trim()
                        }
                        kotlinx.coroutines.delay(40)
                    }
                    throw java.net.SocketTimeoutException("设备命令执行超时")
                } finally {
                    busy = false
                }
            }
        }

        /** 发送数据。会切换到 IO 线程，可直接在协程中调用。[secret] 为 true 时不记录内容。 */
        suspend fun write(text: String, secret: Boolean = false) =
            withContext(Dispatchers.IO) { writeBlocking(text, secret) }

        /** 阻塞发送，只能在 IO 线程调用（获取锁后会直接写 socket）。 */
        internal fun writeBlocking(text: String, secret: Boolean = false) {
            synchronized(this) {
                Diagnostics.log(
                    if (secret) "发送：（登录凭证已隐藏，${text.length} 字符）"
                    else "发送：${text.replace("\r", "\\r").replace("\n", "\\n")}"
                )
                writeRawBlocking(TelnetEncoder.encode(text))
            }
        }

        /** 直接写原始字节（telnet 协商应答），不能经过文本编码器。 */
        private fun writeRawBlocking(bytes: ByteArray) {
            synchronized(this) {
                output.write(bytes)
                output.flush()
            }
        }

        /** 读取当前可用数据并解码。会切换到 IO 线程，可直接在协程中调用。 */
        suspend fun readTextAvailable(maxBytes: Int = 16_384): String =
            withContext(Dispatchers.IO) { readTextAvailableBlocking(maxBytes) }

        /** 阻塞读取并解码，只能在 IO 线程调用。 */
        internal fun readTextAvailableBlocking(maxBytes: Int): String = synchronized(readLock) {
            val size = input.available().coerceAtMost(maxBytes)
            if (size <= 0) return ""
            val buffer = ByteArray(size)
            val count = input.read(buffer)
            if (count <= 0) return ""
            Diagnostics.log("收到 ${Diagnostics.describe(buffer, count)}")
            decoder.decode(buffer, count)
        }

        override fun close() = socket.close()

        fun isReading(): Boolean = reading

        /** 标记为独占访问，暂停空闲断开检测，避免与其抢读数据。 */
        internal fun beginExclusive() { busy = true }

        internal fun endExclusive() { busy = false }

        private companion object {
            /** 最多经历「用户名 → 密码 → 提示符」三轮交互。 */
            const val MAX_AUTH_STEPS = 5
            val LOGIN_PROMPT = Regex("(?im)(?:^|[\\r\\n])[^\\r\\n]{0,40}(?:login|username|user name|user)\\s*[:：]\\s*$")
            val PASSWORD_PROMPT = Regex("(?im)(?:password|passwd)\\s*[:：]\\s*$")
            // '>' 不能是箭头或比较符的一部分，banner 中常出现此类字符。
            val SHELL_PROMPT = Regex("(?m)(?:^|\\n)[^\\r\\n]{0,120}(?:[#$%]|(?<![=\\-])>)\\s*$")
            val AUTH_FAILURE = Regex(
                "(?i)incorrect|authentication failed|login failed|invalid (?:password|login|user)|" +
                    "permission denied|access denied|too many (?:login|attempts)"
            )
        }
    }
}

private class TelnetDecoder(private val respond: (ByteArray) -> Unit) {
    private enum class State { DATA, IAC, OPTION, SUBNEGOTIATION, SUBNEGOTIATION_IAC }
    private var state = State.DATA

    fun decode(bytes: ByteArray, count: Int): String {
        val output = ArrayList<Byte>(count)
        for (index in 0 until count) {
            val value = bytes[index].toInt() and 0xff
            when (state) {
                State.DATA -> when (value) {
                    IAC -> state = State.IAC
                    else -> output.add(bytes[index])
                }
                State.IAC -> when (value) {
                    IAC -> { output.add(IAC.toByte()); state = State.DATA }
                    WILL, WONT, DO, DONT -> { command = value; state = State.OPTION }
                    SB -> state = State.SUBNEGOTIATION
                    else -> state = State.DATA
                }
                State.OPTION -> {
                    val reply = when (command) {
                        WILL -> byteArrayOf(IAC.toByte(), DONT.toByte(), value.toByte())
                        DO -> byteArrayOf(IAC.toByte(), WONT.toByte(), value.toByte())
                        else -> null
                    }
                    if (reply != null) respond(reply)
                    state = State.DATA
                }
                State.SUBNEGOTIATION -> if (value == IAC) state = State.SUBNEGOTIATION_IAC
                State.SUBNEGOTIATION_IAC -> state = if (value == SE) State.DATA else State.SUBNEGOTIATION
            }
        }
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    private var command = 0

    private companion object {
        const val SE = 240
        const val SB = 250
        const val WILL = 251
        const val WONT = 252
        const val DO = 253
        const val DONT = 254
        const val IAC = 255
    }
}

private object TelnetEncoder {
    fun encode(text: String): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val output = java.io.ByteArrayOutputStream(bytes.size + 8)
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            if (value == 13) {
                output.write(13)
                if (index + 1 >= bytes.size || bytes[index + 1].toInt() != 10) output.write(0)
            } else if (value == 10) {
                output.write(13)
                output.write(10)
            } else {
                if (value == 255) output.write(255)
                output.write(value)
            }
        }
        return output.toByteArray()
    }
}
