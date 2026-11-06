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

class TelnetClient {
    suspend fun connect(host: String, port: Int = 23, timeoutMs: Int = 5_000): Session = withContext(Dispatchers.IO) {
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
        // 固定读超时：让读取循环能周期性检查取消与连接状态，而不是永久阻塞。
        socket.soTimeout = 1_000
        Session(socket)
    }

    class Session internal constructor(private val socket: Socket) : AutoCloseable {
        private val input = socket.getInputStream()
        private val output = socket.getOutputStream()
        private val commandMutex = Mutex()
        private val commandBuffer = StringBuilder()
        @Volatile private var reading = false
        /** 有页面正在独占消费数据流时，存活探测需要让出读取。 */
        @Volatile private var busy = false
        @Volatile private var monitoring = false
        @Volatile private var peerClosed = false

        suspend fun readLoop(onText: (String) -> Unit) = withContext(Dispatchers.IO) {
            reading = true
            busy = true
            Diagnostics.log("进入持续读取循环")
            try {
                val buffer = ByteArray(4096)
                val decoder = TelnetDecoder { response -> writeRawBlocking(response) }
                while (!socket.isClosed) {
                    val count = try {
                        input.read(buffer)
                    } catch (_: java.net.SocketTimeoutException) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        continue
                    }
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (count < 0) {
                        peerClosed = true
                        Diagnostics.log("设备关闭了 TCP 连接（读到 EOF）")
                        break
                    }
                    Diagnostics.log("持续读取 ${Diagnostics.describe(buffer, count)}")
                    val text = decoder.decode(buffer, count)
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
            val decoder = TelnetDecoder { response -> writeRawBlocking(response) }
            var closed = false
            try {
                while (!socket.isClosed) {
                    if (busy || reading) {
                        kotlinx.coroutines.delay(200)
                        continue
                    }
                    val value = try {
                        input.read()
                    } catch (_: java.net.SocketTimeoutException) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        continue // 超时说明连接仍然存活
                    }
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (value < 0) {
                        closed = true
                        break
                    }
                    val text = decoder.decode(byteArrayOf(value.toByte()), 1)
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
                            val buffer = ByteArray(input.available().coerceAtMost(16_384))
                            if (buffer.isNotEmpty()) {
                                val count = try {
                                    input.read(buffer)
                                } catch (_: java.net.SocketTimeoutException) {
                                    0
                                }
                                if (count > 0) synchronized(commandBuffer) { commandBuffer.append(TelnetDecoder { }.decode(buffer, count)) }
                            }
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

        /** 发送数据。会切换到 IO 线程，可直接在协程中调用。 */
        suspend fun write(text: String) = withContext(Dispatchers.IO) { writeBlocking(text) }

        /** 阻塞发送，只能在 IO 线程调用（获取锁后会直接写 socket）。 */
        internal fun writeBlocking(text: String) {
            synchronized(this) {
                Diagnostics.log("发送：${text.replace("\r", "\\r").replace("\n", "\\n")}")
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

        /** 读取当前可用的数据。会切换到 IO 线程，可直接在协程中调用。 */
        suspend fun readAvailable(maxBytes: Int = 16_384): ByteArray =
            withContext(Dispatchers.IO) { readAvailableBlocking(maxBytes) }

        /** 阻塞读取，只能在 IO 线程调用。 */
        internal fun readAvailableBlocking(maxBytes: Int): ByteArray = synchronized(this) {
            val size = input.available().coerceAtMost(maxBytes)
            if (size <= 0) return ByteArray(0)
            val buffer = ByteArray(size)
            val count = input.read(buffer)
            if (count <= 0) return ByteArray(0)
            Diagnostics.log("收到 ${Diagnostics.describe(buffer, count)}")
            if (count == buffer.size) buffer else buffer.copyOf(count)
        }

        override fun close() = socket.close()

        fun isReading(): Boolean = reading

        /** 标记为独占访问，暂停空闲断开检测，避免与其抢读数据。 */
        internal fun beginExclusive() { busy = true }

        internal fun endExclusive() { busy = false }
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
