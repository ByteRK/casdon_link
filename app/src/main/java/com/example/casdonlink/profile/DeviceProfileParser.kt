package com.example.casdonlink.profile

import com.example.casdonlink.connection.Diagnostics
import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * assets/profiles.json 的解析与校验。
 *
 * 规则会触发设备侧 root 命令（授权写入、快捷操作），因此任何字段不合法都直接跳过该规则，
 * 绝不让一条残缺规则带着默认值生效。
 */
object DeviceProfileParser {
    const val FORMAT_VERSION = 1

    fun parse(raw: String): List<DeviceProfile> {
        val root = JSONObject(raw)
        require(root.optInt("formatVersion", -1) == FORMAT_VERSION) {
            "不支持的机型规则版本"
        }
        val array = root.optJSONArray("profiles")
            ?: throw IllegalArgumentException("缺少 profiles 数组")
        val profiles = mutableListOf<DeviceProfile>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id").ifBlank { "index-$index" }
            runCatching { parseProfile(item) }
                .onSuccess { profiles += it }
                .onFailure { Diagnostics.log("跳过机型规则 $id：${it.message}") }
        }
        return profiles
    }

    private fun parseProfile(item: JSONObject): DeviceProfile {
        val id = item.getString("id").trim()
        require(id.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "规则 id 格式不正确" }
        val name = item.getString("name").trim()
        require(name.isNotBlank() && name.length <= 100) { "规则名称无效" }
        val priority = item.optInt("priority", 0)

        val matchJson = item.optJSONObject("match") ?: throw IllegalArgumentException("缺少 match")
        val all = parseChecks(matchJson.optJSONArray("all"), "match.all")
        val none = parseChecks(matchJson.optJSONArray("none"), "match.none")
        // 没有 all 条件的规则会匹配所有设备，等同于把任意设备当成该机型，必须拒绝。
        require(all.isNotEmpty()) { "match.all 不能为空" }

        val license = item.optJSONObject("license")?.let { parseLicense(it) }
        val quickActions = parseGroups(item.optJSONArray("quickActions"))

        return DeviceProfile(
            id = id,
            name = name,
            priority = priority,
            match = ProfileMatch(all = all, none = none),
            license = license,
            quickActions = quickActions
        )
    }

    private fun parseChecks(array: JSONArray?, field: String): List<FileCheck> {
        if (array == null) return emptyList()
        val checks = mutableListOf<FileCheck>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val path = validatePath(item.getString("path"), "$field[$index].path")
            val operation = FileOperation.parse(item.optString("op", "EXISTS"))
                ?: throw IllegalArgumentException("$field[$index].op 不支持")
            checks += FileCheck(path, operation)
        }
        return checks
    }

    private fun parseLicense(item: JSONObject): LicenseConfig {
        val pid = item.getString("pid").trim()
        require(pid.isNotBlank() && pid.length <= 100 && !hasControlChars(pid)) { "license.pid 无效" }

        val networkInterface = item.optString("networkInterface", "wlan0").trim()
        require(networkInterface.matches(Regex("[A-Za-z0-9_.:-]{1,64}"))) { "网卡名格式不正确" }

        val targetPath = validatePath(item.getString("targetPath"), "license.targetPath")
        require(targetPath != "/" && !targetPath.endsWith("/")) { "license.targetPath 必须是文件路径" }
        val template = item.getString("contentTemplate")
        require(template.length <= 8192) { "授权文件模板过长" }
        listOf("{{pid}}", "{{did}}", "{{license}}").forEach { slot ->
            require(template.contains(slot)) { "授权文件模板缺少 $slot" }
        }

        val readJson = item.optJSONObject("read")
            ?: throw IllegalArgumentException("license.read 不能为空")
        val pidPattern = validRegex(readJson.getString("pidPattern"), "license.read.pidPattern")
        val didPattern = validRegex(readJson.getString("didPattern"), "license.read.didPattern")
        val licensePattern = readJson.optString("licensePattern").takeIf { it.isNotBlank() }
            ?.let { validRegex(it, "license.read.licensePattern") }

        return LicenseConfig(
            pid = pid,
            networkInterface = networkInterface,
            targetPath = targetPath,
            contentTemplate = template,
            read = LicenseReadSpec(pidPattern, didPattern, licensePattern)
        )
    }

    private fun parseGroups(array: JSONArray?): List<QuickActionGroup> {
        if (array == null) return emptyList()
        val groups = mutableListOf<QuickActionGroup>()
        val ids = mutableSetOf<String>()
        for (groupIndex in 0 until array.length()) {
            val groupJson = array.optJSONObject(groupIndex) ?: continue
            val title = groupJson.optString("title").trim().ifBlank { "操作" }
            val actionsJson = groupJson.optJSONArray("actions") ?: JSONArray()
            val actions = mutableListOf<QuickAction>()
            for (actionIndex in 0 until actionsJson.length()) {
                val actionJson = actionsJson.optJSONObject(actionIndex) ?: continue
                val action = parseAction(actionJson)
                require(ids.add(action.id)) { "动作 id 重复：${action.id}" }
                actions += action
            }
            if (actions.isNotEmpty()) groups += QuickActionGroup(title, actions)
        }
        return groups
    }

    private fun parseAction(item: JSONObject): QuickAction {
        val id = item.getString("id").trim()
        require(id.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "动作 id 格式不正确" }
        val label = item.getString("label").trim()
        require(label.isNotBlank() && label.length <= 60) { "动作名称无效" }
        val description = item.optString("description").trim().take(200)

        val commands = buildList {
            val array = item.optJSONArray("commands")
            if (array != null) {
                for (index in 0 until array.length()) {
                    val command = array.optString(index).trim()
                    if (command.isNotEmpty()) add(command)
                }
            } else {
                item.optString("command").trim().takeIf { it.isNotEmpty() }?.let(::add)
            }
        }
        require(commands.isNotEmpty()) { "动作 $id 没有命令" }
        commands.forEachIndexed { index, command ->
            require(command.length <= 4096) { "动作 $id 的第 ${index + 1} 条命令过长" }
            require(!command.contains('\u0000')) { "动作 $id 的命令包含 NUL" }
        }

        return QuickAction(
            id = id,
            label = label,
            description = description,
            commands = commands,
            confirm = item.optBoolean("confirm", false),
            danger = item.optBoolean("danger", false),
            output = item.optBoolean("output", false),
            timeoutSeconds = item.optLong("timeoutSeconds", 30L).coerceIn(1L, 600L),
            continueOnError = item.optBoolean("continueOnError", false),
            reboot = item.optBoolean("reboot", false)
        )
    }

    private fun validRegex(value: String, field: String): String {
        require(value.length <= 512) { "$field 过长" }
        runCatching { Pattern.compile(value) }.getOrElse { throw IllegalArgumentException("$field 不是合法正则") }
        require(Pattern.compile(value).matcher("").groupCount() >= 1) { "$field 至少需要一个捕获组" }
        return value
    }

    private fun validatePath(value: String, field: String): String {
        require(value.startsWith("/")) { "$field 必须是绝对路径" }
        require(value.length <= 512) { "$field 过长" }
        require(!hasControlChars(value)) { "$field 包含控制字符" }
        require(value.split('/').none { it == ".." }) { "$field 不能包含 .." }
        return value
    }

    private fun hasControlChars(value: String): Boolean =
        value.any { it < ' ' || it == '\u007F' }
}
