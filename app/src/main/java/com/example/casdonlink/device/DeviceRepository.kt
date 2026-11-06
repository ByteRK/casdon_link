package com.example.casdonlink.device

import com.example.casdonlink.connection.Diagnostics
import com.example.casdonlink.connection.TelnetClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.UUID

class DeviceRepository(private val session: TelnetClient.Session) {
    suspend fun readOverview(): DeviceInfo = withContext(Dispatchers.IO) {
        val marker = "__CL_${UUID.randomUUID().toString().replace("-", "")}__"
        // 所有片段必须串在同一条命令行里，且结束标记必须在命令执行后立即输出；
        // 引号必须成对闭合，否则 shell 会停在 PS2 续行提示符上等待输入，标记永远不会出现。
        session.write(
            "printf '\\n'; uname -srmo 2>/dev/null; printf '\\nUPTIME '; cat /proc/uptime 2>/dev/null; " +
                "printf '\\nMEMORY\\n'; head -n 3 /proc/meminfo 2>/dev/null; printf '\\n$marker\\n'\n"
        )
        val output = StringBuilder()
        val startedAt = System.currentTimeMillis()
        // 设备为 BusyBox + 低性能 ARM，首次命令可能需数秒，超时不宜过短。
        repeat(150) {
            delay(100)
            output.append(String(session.readAvailable(), Charsets.UTF_8))
            if (output.toString().contains(marker)) {
                Diagnostics.log("状态查询完成，耗时 ${System.currentTimeMillis() - startedAt}ms")
                return@withContext parseOverview(output.toString().substringBefore(marker))
            }
        }
        Diagnostics.log("状态查询超时，15 秒内共收到 ${output.length} 字符")
        throw IllegalStateException("设备未返回状态信息")
    }

    private fun parseOverview(raw: String): DeviceInfo {
        val lines = raw.replace("\r", "").lines().map(String::trim).filter(String::isNotEmpty)
        val system = lines.firstOrNull { it.contains("Linux") } ?: "Linux"
        val uptimeRaw = lines.firstOrNull { it.startsWith("UPTIME ") }?.substringAfter("UPTIME ")
        val seconds = uptimeRaw?.substringBefore(' ')?.toDoubleOrNull()?.toLong()
        val uptime = seconds?.let {
            val days = it / 86_400
            val hours = (it % 86_400) / 3_600
            val minutes = (it % 3_600) / 60
            if (days > 0) "${days}天 ${hours}小时" else "${hours}小时 ${minutes}分钟"
        } ?: "不可用"
        val memory = lines.filter { it.startsWith("MemTotal:") || it.startsWith("MemAvailable:") }
            .joinToString(" · ") { line ->
                val key = if (line.startsWith("MemTotal:")) "总内存" else "可用内存"
                val kb = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull()
                "$key ${kb?.let { "%.0f MB".format(it / 1024.0) } ?: "不可用"}"
            }.ifBlank { "内存信息不可用" }
        return DeviceInfo(system, uptime, memory)
    }
}
