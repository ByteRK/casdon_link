package com.example.casdonlink.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class DeviceStore(context: Context) {
    private val preferences = context.getSharedPreferences("devices", Context.MODE_PRIVATE)

    fun getAll(): List<DeviceRecord> {
        val array = runCatching { JSONArray(preferences.getString(KEY_RECORDS, "[]")) }.getOrDefault(JSONArray())
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val ip = item.optString("ip")
                if (ip.isNotBlank()) add(
                    DeviceRecord(
                        ipAddress = ip,
                        port = item.optInt("port", 23),
                        // 增加 name 字段之前的记录没有该项，读不到时按空别名处理。
                        name = item.optString("name", ""),
                        username = item.optString("username", ""),
                        // 密文解不开（密钥已失效）时视为未保存密码，不影响记录本身可用。
                        password = item.optString("passwordEnc").takeIf(String::isNotBlank)
                            ?.let(SecretCipher::decrypt).orEmpty(),
                        lastConnectedAt = item.optLong("lastConnectedAt")
                    )
                )
            }
        }.sortedByDescending { it.lastConnectedAt }
    }

    /** 按地址与端口查找记录，供输入框留空时复用已保存的登录信息。 */
    fun find(host: String, port: Int): DeviceRecord? =
        getAll().firstOrNull { it.ipAddress == host && it.port == port }

    /**
     * 保存或更新记录；[DeviceRecord.password] 为空时会清除该地址已保存的密码。
     *
     * 记录键为「IP + 端口」：设备常在同 IP 上同时开 23/2323 等端口，只按 IP 去重会让
     * 两条记录互相覆盖。
     */
    fun save(record: DeviceRecord) {
        val records = (listOf(record) + getAll().filterNot {
            it.ipAddress == record.ipAddress && it.port == record.port
        }).take(MAX_RECORDS)
        write(records)
    }

    /** 删除记录（同时丢弃其保存的密码）。 */
    fun remove(host: String, port: Int) {
        write(getAll().filterNot { it.ipAddress == host && it.port == port })
    }

    /** 清空全部设备记录（含已保存密码）。 */
    fun clear() = write(emptyList())

    /** 只清除所有已保存密码，保留设备别名、地址与最近连接时间。 */
    fun clearPasswords() = write(getAll().map { it.copy(password = "") })

    private fun write(records: List<DeviceRecord>) {
        val array = JSONArray()
        records.forEach {
            val item = JSONObject().put("ip", it.ipAddress).put("port", it.port)
                .put("name", it.name)
                .put("username", it.username).put("lastConnectedAt", it.lastConnectedAt)
            // 只存密文；加密失败时干脆不写该字段，避免退化成明文存储。
            if (it.password.isNotEmpty()) {
                SecretCipher.encrypt(it.password)?.let { encoded -> item.put("passwordEnc", encoded) }
            }
            array.put(item)
        }
        preferences.edit().putString(KEY_RECORDS, array.toString()).apply()
    }

    private companion object {
        const val KEY_RECORDS = "records"
        const val MAX_RECORDS = 20
    }
}
