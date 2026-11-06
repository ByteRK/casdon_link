package com.example.casdonlink.connection

import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 连接诊断记录器：内存保留最近若干条日志，便于在设备上直接查看；
 * 同时输出到 logcat（TAG = [TAG]），可用 `adb logcat -s CasdonLink` 抓取。
 */
object Diagnostics {
    const val TAG = "CasdonLink"
    private const val MAX_LINES = 500
    private const val LOGCAT_LIMIT = 3_500

    private val lines = ArrayDeque<String>()
    private val secrets = LinkedHashSet<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var networkFailure = false

    /** 登记需要在日志中屏蔽的敏感文本（如登录密码），设备回显时同样会被脱敏。 */
    @Synchronized
    fun registerSecret(value: String) {
        if (value.length >= 3) secrets.add(value)
    }

    private fun redact(text: String): String {
        var result = text
        synchronized(this) { secrets.toList() }.forEach { result = result.replace(it, "***") }
        return result
    }

    @Synchronized
    fun log(message: String) {
        val line = "${timeFormat.format(Date())} ${redact(message)}"
        if (message.startsWith("TCP 连接失败")) networkFailure = true
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        Log.i(TAG, if (line.length > LOGCAT_LIMIT) line.take(LOGCAT_LIMIT) + "…" else line)
    }

    /** 最近一次连接是否卡在 TCP 建连阶段（网络层问题，而非 shell / 协议问题）。 */
    fun suggestsNetworkIssue(): Boolean = networkFailure

    @Synchronized
    fun clear() {
        lines.clear()
        secrets.clear()
        networkFailure = false
    }

    @Synchronized
    fun dump(): String = if (lines.isEmpty()) "（无日志）" else lines.joinToString("\n")

    /** 把字节流转成可读文本，控制字符以转义形式显示，便于识别 telnet 协商（IAC = 0xFF）。 */
    fun describe(bytes: ByteArray, count: Int, limit: Int = 320): String {
        if (count <= 0) return "（空）"
        val builder = StringBuilder()
        var truncated = false
        for (index in 0 until count) {
            val value = bytes[index].toInt() and 0xff
            when {
                value == 13 -> builder.append("\\r")
                value == 10 -> builder.append("\\n")
                value in 0x20..0x7e -> builder.append(value.toChar())
                else -> builder.append(String.format(Locale.US, "<%02X>", value))
            }
            if (builder.length > limit) {
                truncated = true
                break
            }
        }
        val suffix = if (truncated) "…（共 $count 字节）" else "（共 $count 字节）"
        return builder.toString() + suffix
    }

    /** 记录本机各网卡 IPv4 地址，用于判断手机与设备是否在同一网段。 */
    fun logNetworkContext() {
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .forEach { nif ->
                    val addresses = nif.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .mapNotNull { it.hostAddress }
                    if (addresses.isNotEmpty()) log("本机网卡 ${nif.name}: ${addresses.joinToString()}")
                }
        }.onFailure { log("读取本机网卡信息失败：${it.javaClass.simpleName}: ${it.message}") }
    }
}
