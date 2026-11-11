package com.example.casdonlink.device

import com.example.casdonlink.connection.Diagnostics
import com.example.casdonlink.connection.TelnetClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DeviceRepository(private val session: TelnetClient.Session) {
    suspend fun readOverview(): DeviceInfo = withContext(Dispatchers.IO) {
        // 复用会话的受控命令通道：结束标记由 execute 追加，读取由后台收集协程统一负责。
        // 每项前加 @标记，输出一行一项，设备缺命令/文件时该项为空，不影响其余字段。
        val startedAt = System.currentTimeMillis()
        val raw = session.execute(
            "printf '\\n@HOST '; hostname 2>/dev/null; " +
                "printf '\\n@UNAME '; uname -srmo 2>/dev/null; " +
                "printf '\\n@UPTIME '; cat /proc/uptime 2>/dev/null; " +
                "printf '\\n@LOAD '; cat /proc/loadavg 2>/dev/null; " +
                "printf '\\n@MEM '; grep -E '^(MemTotal|MemAvailable):' /proc/meminfo 2>/dev/null | tr '\\n' ' '; " +
                "printf '\\n@CPU '; grep -m 1 -E 'model name|^Processor|^Hardware' /proc/cpuinfo 2>/dev/null; " +
                "printf '\\n@DF '; df -h / 2>/dev/null | tail -n 1; " +
                "printf '\\n@DF2 '; df -h /userdata 2>/dev/null | tail -n 1; " +
                "printf '\\n@NET '; (ip -4 addr show wlan0 2>/dev/null || ifconfig wlan0 2>/dev/null) | grep -m 1 'inet '; " +
                "printf '\\n@MAC '; cat /sys/class/net/wlan0/address 2>/dev/null; " +
                "printf '\\n@END\\n'",
            timeoutMs = 15_000
        )
        Diagnostics.log("状态查询完成，耗时 ${System.currentTimeMillis() - startedAt}ms")
        parseDeviceInfo(raw)
    }
}

/** 读取一行 `@KEY 内容`；设备没有该项时返回空串。 */
private fun field(lines: List<String>, key: String): String =
    lines.firstOrNull { it.trimStart().startsWith("@$key ") }
        ?.substringAfter("@$key ")?.trim().orEmpty()

/** 解析设备概览命令输出。抽成纯函数，便于用真机输出做单元测试。 */
internal fun parseDeviceInfo(raw: String): DeviceInfo {
    val lines = raw.replace("\r", "").lines()
    val system = field(lines, "UNAME").ifBlank { "Linux" }
    val uptime = parseUptime(field(lines, "UPTIME").substringBefore(' '))
    val cpu = field(lines, "CPU").substringAfter(':', "").trim().ifBlank { field(lines, "CPU") }
    val load = field(lines, "LOAD").split(' ').filter(String::isNotBlank).take(3).joinToString(" ")
    val memoryText = field(lines, "MEM")
    val networkText = field(lines, "NET")
    val mac = field(lines, "MAC")
    val storageUsage = listOfNotNull(
        parseDfUsage(field(lines, "DF")),
        parseDfUsage(field(lines, "DF2"))
    )
    return DeviceInfo(
        host = field(lines, "HOST"),
        system = system,
        uptime = uptime,
        cpu = cpu,
        load = load,
        memory = parseMemory(memoryText),
        storage = storageUsage.joinToString(" · ") {
            "${it.mountPoint} ${it.sizeText} 已用 ${it.usedPercent}%"
        },
        network = parseNetwork(networkText, mac),
        memoryUsage = parseMemoryUsage(memoryText),
        storageUsage = storageUsage,
        networkDetail = parseNetworkDetail(networkText, mac)
    )
}

/** `/proc/uptime` 的首列秒数；不可读时返回「不可用」。 */
internal fun parseUptime(secondsText: String): String {
    val seconds = secondsText.toDoubleOrNull()?.toLong() ?: return "不可用"
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val minutes = (seconds % 3_600) / 60
    return if (days > 0) "${days}天 ${hours}小时" else "${hours}小时 ${minutes}分钟"
}

/** 读取 MemTotal/MemAvailable；返回 kB 值，缺失项为 null。 */
private fun memoryValues(text: String): Pair<Long?, Long?> {
    val total = Regex("MemTotal:\\s*(\\d+)\\s*kB").find(text)?.groupValues?.get(1)?.toLongOrNull()
    val available = Regex("MemAvailable:\\s*(\\d+)\\s*kB").find(text)?.groupValues?.get(1)?.toLongOrNull()
    return total to available
}

/** 供环形指标使用的内存使用率；只有总量和可用量都有效时才生成。 */
internal fun parseMemoryUsage(text: String): MemoryUsage? {
    val (totalKb, availableKb) = memoryValues(text)
    if (totalKb == null || availableKb == null) return null
    val totalMb = totalKb / 1024
    if (totalMb <= 0) return null
    return MemoryUsage(totalMb = totalMb, availableMb = availableKb / 1024)
}

/** `/proc/meminfo` 的 MemTotal/MemAvailable；设备缺少时返回「内存信息不可用」。 */
internal fun parseMemory(text: String): String {
    val (total, available) = memoryValues(text)
    if (total == null && available == null) return "内存信息不可用"
    return buildList {
        total?.let { add("总内存 ${"%.0f MB".format(it / 1024.0)}") }
        available?.let { add("可用内存 ${"%.0f MB".format(it / 1024.0)}") }
    }.joinToString(" · ")
}

/**
 * `df -h` 的数据行：`文件系统 总量 已用 可用 使用率 挂载点`。
 * 只解析容量与使用率，挂载点用于区分系统盘/数据盘；表头/异常行返回 null。
 */
internal fun parseDfUsage(line: String?): StorageUsage? {
    val fields = line?.trim()?.split(Regex("\\s+")).orEmpty()
    if (fields.size < 6) return null
    val size = fields[1]
    val used = fields[2]
    val percentText = fields[4]
    if (!size.matches(Regex("\\d+(?:\\.\\d+)?[KMGTPE]?", RegexOption.IGNORE_CASE))) return null
    if (!percentText.matches(Regex("\\d+%"))) return null
    return StorageUsage(
        mountPoint = fields[5],
        sizeText = size,
        usedText = used,
        usedPercent = percentText.removeSuffix("%").toInt()
    )
}

/** 兼容旧的字符串展示：`94.0M 已用 42%`。 */
internal fun parseDfLine(line: String?): String? =
    parseDfUsage(line)?.let { "${it.sizeText} 已用 ${it.usedPercent}%" }

/** 从 `ip addr`/`ifconfig` 输出里取 IPv4 地址，并与 MAC 组合成结构化摘要。 */
internal fun parseNetworkDetail(addressText: String, mac: String): NetworkDetail? {
    val ip = Regex("inet\\s+(?:addr:)?([0-9]+(?:\\.[0-9]+){3}(?:/\\d+)?)").find(addressText)
        ?.groupValues?.get(1)
    val cleanMac = mac.trim().uppercase()
        .takeIf { it.matches(Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}")) }
    if (ip == null && cleanMac == null) return null
    return NetworkDetail(ip = ip, mac = cleanMac)
}

/** 兼容旧的字符串展示：IP 与 MAC 用 ` · ` 连接，都缺失时返回「网络信息不可用」。 */
internal fun parseNetwork(addressText: String, mac: String): String =
    parseNetworkDetail(addressText, mac)
        ?.let { listOfNotNull(it.ip, it.mac).joinToString(" · ") }
        ?: "网络信息不可用"
