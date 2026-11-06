package com.example.casdonlink.connection

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket

class TelnetClient {
    suspend fun connect(host: String, port: Int = 23, timeoutMs: Int = 5_000): Session = withContext(Dispatchers.IO) {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        Session(socket)
    }

    class Session internal constructor(private val socket: Socket) : AutoCloseable {
        private val input = BufferedInputStream(socket.getInputStream())
        private val output = BufferedOutputStream(socket.getOutputStream())

        @Synchronized
        fun write(text: String) {
            output.write(text.toByteArray(Charsets.UTF_8))
            output.flush()
        }

        @Synchronized
        fun readAvailable(maxBytes: Int = 16_384): ByteArray {
            val size = input.available().coerceAtMost(maxBytes)
            if (size <= 0) return ByteArray(0)
            return ByteArray(size).also { input.read(it) }
        }

        override fun close() = socket.close()
    }
}
