package com.example.casdonlink.device

/**
 * 设备概览信息。字段按 README 承诺的「系统 / 运行时间 / CPU / 内存 / 存储 / 网络」拆分，
 * 设备缺少某项命令时对应字段为空串，由界面决定是否展示。
 *
 * 字符串字段保留给「详情」弹窗与既有测试；结构化字段供设备状态卡片做图形化展示。
 */
data class DeviceInfo(
    val host: String,
    val system: String,
    val uptime: String,
    val cpu: String,
    val load: String,
    val memory: String,
    val storage: String,
    val network: String,
    val memoryUsage: MemoryUsage? = null,
    val storageUsage: List<StorageUsage> = emptyList(),
    val networkDetail: NetworkDetail? = null
)

/** 内存使用率；只有 MemTotal 与 MemAvailable 都读取到时才生成。 */
data class MemoryUsage(
    val totalMb: Long,
    val availableMb: Long
) {
    val usedMb: Long get() = (totalMb - availableMb).coerceAtLeast(0)
    val usedPercent: Int get() = if (totalMb > 0) ((usedMb * 100) / totalMb).toInt().coerceIn(0, 100) else 0
}

/** 单个挂载点的容量与使用率，用于系统盘/数据盘环形指标。 */
data class StorageUsage(
    val mountPoint: String,
    val sizeText: String,
    val usedText: String,
    val usedPercent: Int
)

/** 网络摘要；IP 与 MAC 都可能缺失，两者都缺失时不生成。 */
data class NetworkDetail(
    val ip: String?,
    val mac: String?
)
