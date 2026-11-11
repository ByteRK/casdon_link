package com.example.casdonlink.data

import android.content.Context
import org.json.JSONArray

/**
 * 终端命令历史：只保存命令文本（最近 [MAX_COMMANDS] 条），不保存输出、密码等敏感内容。
 * 用 SharedPreferences 持久化，App 重启后仍可继续用 ↑/↓ 回放。
 */
class TerminalHistoryStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): List<String> {
        val raw = preferences.getString(KEY_COMMANDS, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }.takeLast(MAX_COMMANDS)
        }.getOrDefault(emptyList())
    }

    fun save(commands: Collection<String>) {
        val array = JSONArray()
        commands.toList().takeLast(MAX_COMMANDS).forEach(array::put)
        preferences.edit().putString(KEY_COMMANDS, array.toString()).apply()
    }

    private companion object {
        const val PREFERENCES = "terminal_history"
        const val KEY_COMMANDS = "commands"
        const val MAX_COMMANDS = 50
    }
}
