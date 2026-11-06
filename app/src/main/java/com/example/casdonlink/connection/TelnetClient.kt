package com.example.casdonlink.connection

import kotlinx.coroutines.Dispatchers
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
        Session(socket)
    }

    class Session internal constructor(private val socket: Socket) : AutoCloseable {
        private val input = socket.getInputStream()
        private val output = socket.getOutputStream()
        private val commandMutex = Mutex()
        private val commandBuffer = StringBuilder()
        @Volatile private var reading = false

        suspend fun readLoop(onText: (String) -> Unit) = withContext(Dispatchers.IO) {
            reading = true
            Diagnostics.log("进入持续读取循环")
            val buffer = ByteArray(4096)
            val decoder = TelnetDecoder { response -> synchronized(this@Session) {
                output.write(response)
                output.flush()
            } }
            while (!socket.isClosed) {
                val count = input.read(buffer)
                if (count < 0) {
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
            reading = false
        }

        suspend fun execute(command: String, timeoutMs: Long = 8_000): String = commandMutex.withLock {
            withContext(Dispatchers.IO) {
                val marker = "__CL_DONE_${java.util.UUID.randomUUID().toString().replace("-", "")}__"
                synchronized(commandBuffer) { commandBuffer.clear() }
                write("$command\nprintf '\\n$marker:%s\\n' \"$?\"\n")
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                if (!reading) {
                    val buffer = ByteArray(input.available().coerceAtMost(16_384))
                    if (buffer.isNotEmpty()) {
                        val count = input.read(buffer)
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
            }
        }

        @Synchronized
        fun write(text: String) {
            Diagnostics.log("发送：${text.replace("\r", "\\r").replace("\n", "\\n")}")
            output.write(TelnetEncoder.encode(text))
            output.flush()
        }

        @Synchronized
        fun readAvailable(maxBytes: Int = 16_384): ByteArray {
            val size = input.available().coerceAtMost(maxBytes)
            if (size <= 0) return ByteArray(0)
            val buffer = ByteArray(size)
            val count = input.read(buffer)
            if (count <= 0) return ByteArray(0)
            Diagnostics.log("收到 ${Diagnostics.describe(buffer, count)}")
            return if (count == buffer.size) buffer else buffer.copyOf(count)
        }

        override fun close() = socket.close()

        fun isReading(): Boolean = reading
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
