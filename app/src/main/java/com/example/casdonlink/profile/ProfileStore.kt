package com.example.casdonlink.profile

import android.content.Context
import com.example.casdonlink.connection.Diagnostics

/** 读取内置机型规则；后续如需签名导入，只需在这里增加外部来源并复用解析校验。 */
class ProfileStore(context: Context) {
    private val appContext = context.applicationContext

    fun load(): List<DeviceProfile> = runCatching {
        val raw = appContext.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
        DeviceProfileParser.parse(raw)
    }.onFailure {
        Diagnostics.log("加载机型规则失败：${it.javaClass.simpleName}: ${it.message}")
    }.getOrDefault(emptyList())

    fun find(id: String): DeviceProfile? = load().firstOrNull { it.id == id }

    private companion object {
        const val ASSET_NAME = "profiles.json"
    }
}
