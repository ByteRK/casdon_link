package com.example.casdonlink.profile

import android.content.Context
import com.example.casdonlink.BuildConfig
import com.example.casdonlink.data.SecretCipher
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class LicenseStatus { PENDING, AUTHORIZED, WRITTEN, SUCCESS, FAILED }

/**
 * 本地授权操作记录。
 *
 * 授权码本身用 Keystore 加密后落盘，导出时才还原明文；记录只用于售后追溯和失败重写，
 * 不进入日志、不上传服务器。
 */
data class LicenseRecord(
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val host: String,
    val port: Int,
    val profileId: String,
    val profileName: String,
    val pid: String,
    val mac: String,
    val targetPath: String,
    val did: String = "",
    val license: String = "",
    val mode: String = "",
    val status: LicenseStatus = LicenseStatus.PENDING,
    val message: String = "",
    val appVersion: String = BuildConfig.VERSION_NAME
)

class LicenseHistoryStore(context: Context) {
    private val file = File(context.filesDir, FILE_NAME)
    private val lock = Any()

    fun all(): List<LicenseRecord> = synchronized(lock) { readLocked() }

    fun count(): Int = all().size

    fun add(record: LicenseRecord) = synchronized(lock) {
        val records = readLocked().toMutableList()
        records.add(0, record)
        writeLocked(records.take(MAX_RECORDS))
    }

    fun update(id: String, transform: (LicenseRecord) -> LicenseRecord) = synchronized(lock) {
        val records = readLocked().toMutableList()
        val index = records.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized
        records[index] = transform(records[index])
        writeLocked(records)
    }

    /** 导出为带完整字段的 JSON；授权码明文只出现在用户主动导出的文件里。 */
    fun exportJson(): String = synchronized(lock) {
        val root = JSONObject()
            .put("formatVersion", 1)
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("exportedAt", timeText(System.currentTimeMillis()))
        val array = JSONArray()
        readLocked().forEach { record ->
            array.put(JSONObject()
                .put("id", record.id)
                .put("time", timeText(record.createdAt))
                .put("host", record.host)
                .put("port", record.port)
                .put("profileId", record.profileId)
                .put("profileName", record.profileName)
                .put("pid", record.pid)
                .put("mac", record.mac)
                .put("did", record.did)
                .put("license", record.license)
                .put("mode", record.mode)
                .put("targetPath", record.targetPath)
                .put("status", record.status.name)
                .put("message", record.message)
                .put("appVersion", record.appVersion))
        }
        root.put("records", array).toString(2)
    }

    private fun readLocked(): List<LicenseRecord> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText(Charsets.UTF_8))
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(LicenseRecord(
                        id = item.optString("id", UUID.randomUUID().toString()),
                        createdAt = item.optLong("createdAt", 0L),
                        host = item.optString("host"),
                        port = item.optInt("port", 23),
                        profileId = item.optString("profileId"),
                        profileName = item.optString("profileName"),
                        pid = item.optString("pid"),
                        mac = item.optString("mac"),
                        targetPath = item.optString("targetPath"),
                        did = item.optString("did"),
                        license = item.optString("licenseEnc").takeIf { it.isNotBlank() }
                            ?.let { SecretCipher.decrypt(it) }.orEmpty(),
                        mode = item.optString("mode"),
                        status = runCatching { LicenseStatus.valueOf(item.optString("status")) }
                            .getOrDefault(LicenseStatus.FAILED),
                        message = item.optString("message"),
                        appVersion = item.optString("appVersion")
                    ))
                }
            }
        }.onFailure {
            // 记录文件损坏时不让授权页面整体失败，按空记录处理。
        }.getOrDefault(emptyList())
    }

    private fun writeLocked(records: List<LicenseRecord>) {
        val array = JSONArray()
        records.forEach { record ->
            val item = JSONObject()
                .put("id", record.id)
                .put("createdAt", record.createdAt)
                .put("host", record.host)
                .put("port", record.port)
                .put("profileId", record.profileId)
                .put("profileName", record.profileName)
                .put("pid", record.pid)
                .put("mac", record.mac)
                .put("targetPath", record.targetPath)
                .put("did", record.did)
                .put("mode", record.mode)
                .put("status", record.status.name)
                .put("message", record.message)
                .put("appVersion", record.appVersion)
            if (record.license.isNotEmpty()) {
                SecretCipher.encrypt(record.license)?.let { item.put("licenseEnc", it) }
            }
            array.put(item)
        }
        runCatching { file.writeText(array.toString(), Charsets.UTF_8) }
    }

    private fun timeText(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))

    private companion object {
        const val FILE_NAME = "license_records.json"
        const val MAX_RECORDS = 200
    }
}
