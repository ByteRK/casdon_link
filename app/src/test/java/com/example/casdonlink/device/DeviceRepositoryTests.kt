package com.example.casdonlink.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用真机（RK3506 / Buildroot）输出验证设备概览解析，避免字段缺失时拖垮整张状态卡。 */
class DeviceRepositoryTests {

    private val sample = """
        @HOST rk3506-buildroot
        @UNAME Linux 6.1.118 armv7l GNU/Linux
        @UPTIME 13123.45 1000.00
        @LOAD 0.42 0.38 0.31 1/123 456
        @MEM MemTotal:        96264 kB MemAvailable:     55304 kB 
        @CPU Processor : ARMv7 Processor rev 1 (v7l)
        @DF /dev/root 94.0M 39.2M 54.8M 42% /
        @DF2 /dev/ubi0_2 1.2G 512.0M 688.0M 43% /userdata
        @NET     inet 10.0.0.93/24 brd 10.0.0.255 scope global wlan0
        @MAC 14:0a:02:2c:18:bc
        @END
    """.trimIndent()

    @Test
    fun parsesFullOverview() {
        val info = parseDeviceInfo(sample)
        assertEquals("rk3506-buildroot", info.host)
        assertEquals("Linux 6.1.118 armv7l GNU/Linux", info.system)
        assertEquals("3小时 38分钟", info.uptime)
        assertEquals("ARMv7 Processor rev 1 (v7l)", info.cpu)
        assertEquals("0.42 0.38 0.31", info.load)
        assertTrue(info.memory.contains("总内存 94 MB"))
        assertTrue(info.memory.contains("可用内存 54 MB"))
        assertEquals("/ 94.0M 已用 42% · /userdata 1.2G 已用 43%", info.storage)
        assertEquals("10.0.0.93/24 · 14:0A:02:2C:18:BC", info.network)
    }

    @Test
    fun buildsStructuredStatusForGauges() {
        val info = parseDeviceInfo(sample)
        val memory = info.memoryUsage
        assertEquals(94L, memory?.totalMb)
        assertEquals(54L, memory?.availableMb)
        assertEquals(40L, memory?.usedMb)
        assertEquals(42, memory?.usedPercent)

        assertEquals(2, info.storageUsage.size)
        assertEquals("/", info.storageUsage[0].mountPoint)
        assertEquals("94.0M", info.storageUsage[0].sizeText)
        assertEquals("39.2M", info.storageUsage[0].usedText)
        assertEquals(42, info.storageUsage[0].usedPercent)
        assertEquals("/userdata", info.storageUsage[1].mountPoint)
        assertEquals(43, info.storageUsage[1].usedPercent)

        assertEquals("10.0.0.93/24", info.networkDetail?.ip)
        assertEquals("14:0A:02:2C:18:BC", info.networkDetail?.mac)
    }

    @Test
    fun missingFieldsFallBackInsteadOfCrashing() {
        val info = parseDeviceInfo("@UNAME Linux 4.19 armv7l\n@END")
        assertEquals("Linux 4.19 armv7l", info.system) // 无 @HOST 时退回系统信息
        assertEquals("", info.host)
        assertEquals("不可用", info.uptime)
        assertEquals("内存信息不可用", info.memory)
        assertEquals("", info.storage)
        assertEquals("网络信息不可用", info.network)
        assertEquals("", info.cpu)
        assertEquals("", info.load)
        assertNull(info.memoryUsage)
        assertTrue(info.storageUsage.isEmpty())
        assertNull(info.networkDetail)
    }

    @Test
    fun ifconfigStyleAddressIsParsed() {
        val network = parseNetwork("          inet addr:10.0.0.93  Bcast:10.0.0.255  Mask:255.255.255.0", "")
        assertEquals("10.0.0.93", network)
    }

    @Test
    fun macOnlyNetworkStillShowsMac() {
        assertEquals("14:0A:02:2C:18:BC", parseNetwork("", "14:0a:02:2c:18:bc"))
    }

    @Test
    fun malformedDfLineIsIgnored() {
        assertNull(parseDfLine("Filesystem Size Used Available Use% Mounted on"))
        assertNull(parseDfLine(""))
        assertNull(parseDfUsage("Filesystem Size Used Available Use% Mounted on"))
    }

    @Test
    fun memoryUsageNeedsBothTotalAndAvailable() {
        assertNull(parseMemoryUsage("MemTotal: 96264 kB"))
        assertEquals(
            42,
            parseMemoryUsage("MemTotal: 96264 kB MemAvailable: 55304 kB")?.usedPercent
        )
    }

    @Test
    fun uptimeHandlesMissingValue() {
        assertEquals("不可用", parseUptime(""))
        assertEquals("0小时 0分钟", parseUptime("12.5"))
        assertEquals("2天 3小时", parseUptime("183600.0"))
    }
}
