package com.example.casdonlink.files

import com.example.casdonlink.connection.Diagnostics
import com.example.casdonlink.connection.NetworkDiagnostics
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import kotlin.concurrent.thread

/**
 * 手机侧文件服务端：设备端用 `wget` 从这里拉取待上传的文件。
 *
 * 现场无线链路会出现秒级抖动，同一次上传可能断开重来，因此服务端不「只服务一次」，
 * 而是持续 accept 直到 [close]；并支持 `Range: bytes=<已下载>-`（`wget -c` 会带上它）
 * 让断点续传真正生效，HEAD 用于探测而不发正文。
 *
 * 传输路径用每次启动随机生成的 ASCII token，而不是文件名本身：中文/空格文件名经
 * `wget` 编码后与原始字符串比较会 404（1.10.1 报告已记录），随机路径同时避免同网段
 * 其他人猜到文件名后直接拉取。
 */
class PhoneHttpServer(private val file: File) {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var closed = false

    /** 本次传输的一次性路径，只包含 ASCII 字符。 */
    private val transferPath = "/t/${UUID.randomUUID().toString().replace("-", "")}"

    fun start(bindAddress: String): String {
        val socket = ServerSocket(0, 8, InetAddress.getByName(bindAddress))
        server = socket
        thread(name = "casdon-http-transfer", isDaemon = true) {
            try {
                // 两次重试之间可能隔几秒，用带超时的 accept 轮询关闭标志。
                socket.soTimeout = ACCEPT_POLL_TIMEOUT_MS
                while (!closed) {
                    val client = try {
                        socket.accept()
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    try {
                        client.use { serve(it) }
                    } catch (_: Exception) {
                        // 单个连接出错不能结束服务端：设备端会立即发起下一次重试。
                    }
                }
            } catch (_: Exception) {
            } finally {
                close()
            }
        }
        Diagnostics.log("上传服务端已启动：${file.name}（${file.length()} 字节）")
        return "http://$bindAddress:${socket.localPort}$transferPath"
    }

    private fun serve(client: Socket) {
        client.tcpNoDelay = true
        client.sendBufferSize = SEND_BUFFER_BYTES
        client.soTimeout = READ_TIMEOUT_MS
        val lines = readHeader(client.getInputStream()) ?: return
        val request = lines.firstOrNull().orEmpty().split(' ')
        val method = request.getOrNull(0).orEmpty()
        val requestPath = request.getOrNull(1).orEmpty().substringBefore('?')
        val output = client.getOutputStream()
        val total = file.length()
        if (requestPath != transferPath) {
            output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return
        }
        val range = if (method == "GET") parseRange(lines, total) else null
        Diagnostics.log("上传服务端收到 $method（${file.name}，$total 字节，range=${range ?: "无"}）")
        when {
            method == "HEAD" -> output.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
                    "Content-Length: $total\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n").toByteArray()
            )
            range is RangeResult.Invalid -> output.write(
                ("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\n" +
                    "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray()
            )
            range is RangeResult.Partial -> {
                val length = range.end - range.start + 1
                output.write(
                    ("HTTP/1.1 206 Partial Content\r\nContent-Type: application/octet-stream\r\n" +
                        "Content-Length: $length\r\nContent-Range: bytes ${range.start}-${range.end}/$total\r\n" +
                        "Accept-Ranges: bytes\r\nConnection: close\r\n\r\n").toByteArray()
                )
                copyRange(output, range.start, length)
            }
            method == "GET" -> {
                output.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: $total\r\n" +
                        "Accept-Ranges: bytes\r\nConnection: close\r\n\r\n").toByteArray()
                )
                copyRange(output, 0, total)
            }
            else -> output.write(
                "HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
            )
        }
        output.flush()
    }

    /** 读到空行为止（`\r\n\r\n`），返回请求头各行；超长或对端直接关闭时返回 null。 */
    private fun readHeader(input: java.io.InputStream): List<String>? {
        val buffer = ByteArray(HEADER_LIMIT_BYTES)
        var used = 0
        while (used < buffer.size) {
            val byte = input.read()
            if (byte < 0) return null
            buffer[used++] = byte.toByte()
            if (used >= 4 && buffer[used - 4] == 13.toByte() && buffer[used - 3] == 10.toByte() &&
                buffer[used - 2] == 13.toByte() && buffer[used - 1] == 10.toByte()) {
                return String(buffer, 0, used, Charsets.US_ASCII).lineSequence().toList()
            }
        }
        return null
    }

    private sealed interface RangeResult {
        data class Partial(val start: Long, val end: Long) : RangeResult
        data object Invalid : RangeResult
    }

    /** 解析 `Range: bytes=<start>-<end>`；未携带或格式不认识时返回 null（整文件发送）。 */
    private fun parseRange(lines: List<String>, total: Long): RangeResult? {
        val value = lines.firstOrNull { it.startsWith("Range:", ignoreCase = true) }
            ?.substringAfter(':')?.trim() ?: return null
        if (!value.startsWith("bytes=", ignoreCase = true)) return null
        val spec = value.substringAfter('=').substringBefore(',').trim()
        val startText = spec.substringBefore('-').trim()
        val endText = spec.substringAfter('-', "").trim()
        val start = startText.toLongOrNull() ?: return null
        if (start >= total) return RangeResult.Invalid
        val end = endText.toLongOrNull()?.coerceAtMost(total - 1) ?: (total - 1)
        if (end < start) return RangeResult.Invalid
        return RangeResult.Partial(start, end)
    }

    private fun copyRange(output: java.io.OutputStream, start: Long, length: Long) {
        java.io.RandomAccessFile(file, "r").use { source ->
            source.seek(start)
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            var remaining = length
            while (remaining > 0) {
                val read = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read <= 0) break
                output.write(buffer, 0, read)
                remaining -= read
            }
        }
    }

    fun close() {
        closed = true
        runCatching { server?.close() }
        server = null
    }

    private companion object {
        const val ACCEPT_POLL_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 15_000
        const val SEND_BUFFER_BYTES = 128 * 1024
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val HEADER_LIMIT_BYTES = 4096
    }
}

object LocalAddress {
    /**
     * 本机传输地址选择结果。
     * [address] 为空时 [message] 说明原因；[sameSubnet] 表示是否确认目标与所选网卡同网段。
     */
    data class Selection(val address: String?, val message: String, val sameSubnet: Boolean)

    /**
     * 选择用于传输的本机 IPv4 地址。
     *
     * 优先选与目标设备同网段的网卡：Wi-Fi + VPN/热点并存时，「第一个非回环 IPv4」很可能
     * 是 VPN 地址，设备端 wget 拉不到。目标不是数字 IP（域名）、跨网段或只有一块网卡时，
     * 退回默认路由网卡，并在 [Selection.message] 里说明。
     */
    fun select(context: android.content.Context, targetHost: String? = null): Selection {
        val endpoints = NetworkDiagnostics.localEndpoints(context)
        if (endpoints.isEmpty()) {
            return Selection(null, "未获取到本机 IPv4 地址，请检查 Wi-Fi 连接", false)
        }
        val target = targetHost?.trim().orEmpty()
        if (target.isNotEmpty()) {
            val matched = endpoints.firstOrNull { NetworkDiagnostics.sameSubnet(it, target) == true }
            if (matched != null) {
                return Selection(
                    matched.address,
                    "已选择与 $target 同网段的 ${matched.interfaceName} 地址 ${matched.address}",
                    true
                )
            }
        }
        // 同网段没有匹配时优先走默认路由的网卡，其次退回第一块可用网卡。
        val fallback = endpoints.firstOrNull { it.gateway != null } ?: endpoints.first()
        val reason = if (target.isNotEmpty()) "未找到与 $target 同网段的地址，" else ""
        return Selection(
            fallback.address,
            "${reason}改用 ${fallback.interfaceName} 的 ${fallback.address}",
            false
        )
    }
}
