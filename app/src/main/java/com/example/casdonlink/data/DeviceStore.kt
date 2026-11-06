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
                if (ip.isNotBlank()) add(DeviceRecord(ip, item.optInt("port", 23), item.optLong("lastConnectedAt")))
            }
        }.sortedByDescending { it.lastConnectedAt }
    }

    fun save(record: DeviceRecord) {
        val records = (listOf(record) + getAll().filterNot { it.ipAddress == record.ipAddress }).take(MAX_RECORDS)
        val array = JSONArray()
        records.forEach {
            array.put(JSONObject().put("ip", it.ipAddress).put("port", it.port).put("lastConnectedAt", it.lastConnectedAt))
        }
        preferences.edit().putString(KEY_RECORDS, array.toString()).apply()
    }

    private companion object {
        const val KEY_RECORDS = "records"
        const val MAX_RECORDS = 20
    }
}
