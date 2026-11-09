package com.example.casdonlink.connection

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * 连接失败时的网络自检。用于区分「网络不通」与「端口未监听」两类问题：
 * TCP 连接超时通常代表数据包被丢弃（不同网段、AP 隔离、防火墙），
 * 连接被拒绝（RST）才代表主机可达但没有服务在监听。
 */
object NetworkDiagnostics {

    data class LocalEndpoint(
        val interfaceName: String,
        val address: String,
        val prefixLength: Int,
        val gateway: String?
    )

    /** 常见设备服务端口，用于判断设备上到底开了哪些端口。 */
    private val PROBE_PORTS = listOf(23, 2323, 22, 80, 8080, 21)

    /**
     * 列出本机所有启用的非回环网卡 IPv4 地址。
     * 用 [java.net.NetworkInterface] 遍历（同步 API）替代已废弃的 [ConnectivityManager.getAllNetworks]；
     * 网关只对当前默认网络有意义，因此只为该网卡附加网关。
     */
    fun localEndpoints(context: Context): List<LocalEndpoint> {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeEndpoint = runCatching {
            manager?.let { connectivity ->
                connectivity.activeNetwork?.let { network ->
                    connectivity.getLinkProperties(network)?.let { properties ->
                        properties.interfaceName to properties.routes
                            .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                            ?.gateway?.hostAddress
                    }
                }
            }
        }.getOrNull()

        return runCatching {
            NetworkInterface.getNetworkInterfaces()
                .toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { nif ->
                    nif.interfaceAddresses.mapNotNull { interfaceAddress ->
                        val address = interfaceAddress.address as? Inet4Address
                            ?: return@mapNotNull null
                        if (address.isLoopbackAddress) return@mapNotNull null
                        LocalEndpoint(
                            nif.name ?: "?",
                            address.hostAddress ?: "?",
                            interfaceAddress.networkPrefixLength.toInt(),
                            activeEndpoint?.takeIf { it.first == nif.name }?.second
                        )
                    }
                }
        }.getOrDefault(emptyList())
    }

    /** 判断目标 IP 与本机地址是否同网段（按掩码计算网络地址）。 */
    fun sameSubnet(local: LocalEndpoint, targetIp: String): Boolean? = runCatching {
        val target = InetAddress.getByName(targetIp) as? Inet4Address ?: return null
        val source = InetAddress.getByName(local.address) as? Inet4Address ?: return null
        val mask = if (local.prefixLength == 0) 0 else (-1 shl (32 - local.prefixLength))
        (toInt(source.address) and mask) == (toInt(target.address) and mask)
    }.getOrNull()

    /** 把 4 字节 IPv4 地址转成有符号 32 位整数，便于按掩码位运算。 */
    private fun toInt(address: ByteArray): Int {
        var value = 0
        for (index in 0 until 4) value = (value shl 8) or (address[index].toInt() and 0xff)
        return value
    }

    /** 尝试建立 TCP 连接，返回可读结果（成功耗时 / 拒绝 / 超时）。 */
    fun probe(host: String, port: Int, timeoutMs: Int = 1_500): String {
        val startedAt = System.currentTimeMillis()
        return try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                "开放（${System.currentTimeMillis() - startedAt}ms）"
            }
        } catch (error: java.net.SocketTimeoutException) {
            "超时（包被丢弃）"
        } catch (error: java.net.ConnectException) {
            "拒绝连接（主机可达，端口未监听）"
        } catch (error: Exception) {
            "${error.javaClass.simpleName}: ${error.message}"
        }
    }

    /** 顺序探测常见端口，跳过与目标端口重复的项。 */
    fun probeCommonPorts(host: String, skipPort: Int): List<String> =
        PROBE_PORTS.filter { it != skipPort }.map { port -> "$port → ${probe(host, port)}" }

    /** 调用系统 ping，判断 IP 层是否可达（部分网络会屏蔽 ICMP，仅供参考）。 */
    fun ping(host: String, count: Int = 2): String = runCatching {
        val process = ProcessBuilder("/system/bin/ping", "-c", count.toString(), "-W", "1", host)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        val summary = output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .lastOrNull { it.contains("packets transmitted") || it.contains("packet loss") }
        val firstError = output.lineSequence().firstOrNull { it.contains("unreachable", true) || it.contains("not known", true) }
        summary ?: firstError ?: "无响应"
    }.getOrElse { "无法执行：${it.message}" }

    /** 汇总一段可读的网络自检文本。 */
    fun report(context: Context, host: String, port: Int): String {
        val endpoints = localEndpoints(context)
        val builder = StringBuilder()
        if (endpoints.isEmpty()) builder.append("本机网络：未获取到可用的 IPv4 地址\n")
        endpoints.forEach { endpoint ->
            val same = sameSubnet(endpoint, host)
            builder.append("本机 ${endpoint.interfaceName}: ${endpoint.address}/${endpoint.prefixLength}")
            endpoint.gateway?.let { builder.append("  网关 $it") }
            builder.append('\n')
            builder.append(
                when (same) {
                    true -> "  → 与 $host 同网段\n"
                    false -> "  → 与 $host 不同网段，需要经过网关或路由器转发\n"
                    null -> "  → 无法解析目标地址 $host\n"
                }
            )
        }
        builder.append("\nICMP：${ping(host)}\n")
        builder.append("\n端口探测 $host：\n")
        builder.append("  $port → ${probe(host, port, 3_000)}\n")
        probeCommonPorts(host, port).forEach { builder.append("  $it\n") }
        return builder.toString().trim()
    }
}
