package com.example.casdonlink.files

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class PhoneHttpServer(private val file: File) {
    private var server: ServerSocket? = null

    fun start(bindAddress: String): String {
        val socket = ServerSocket(0, 4, InetAddress.getByName(bindAddress))
        server = socket
        thread(name = "casdon-http-transfer", isDaemon = true) {
            try {
                socket.soTimeout = 45_000
                socket.accept().use { client -> serve(client) }
            } catch (_: Exception) { }
            finally { close() }
        }
        return "http://$bindAddress:${socket.localPort}/${file.name}"
    }

    private fun serve(client: Socket) {
        client.soTimeout = 10_000
        val input = client.getInputStream()
        val request = ByteArray(4096)
        var used = 0
        while (used < request.size) {
            val byte = input.read()
            if (byte < 0) return
            request[used++] = byte.toByte()
            if (used >= 4 && request[used - 4] == 13.toByte() && request[used - 3] == 10.toByte() &&
                request[used - 2] == 13.toByte() && request[used - 1] == 10.toByte()) break
        }
        val firstLine = String(request, 0, used, Charsets.US_ASCII).lineSequence().firstOrNull().orEmpty()
        val output = client.getOutputStream()
        if (!firstLine.startsWith("GET /${file.name} ")) {
            output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return
        }
        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${file.length()}\r\nConnection: close\r\n\r\n".toByteArray())
        file.inputStream().use { it.copyTo(output) }
        output.flush()
    }

    fun close() { runCatching { server?.close() }; server = null }
}

object LocalAddress {
    fun find(context: android.content.Context): String? {
        val manager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = manager.activeNetwork ?: return null
        val properties = manager.getLinkProperties(network) ?: return null
        return properties.linkAddresses.asSequence().map { it.address }
            .filterIsInstance<java.net.Inet4Address>().firstOrNull { !it.isLoopbackAddress }?.hostAddress
    }
}
