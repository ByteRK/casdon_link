package com.example.casdonlink.profile

/**
 * 把全部规则的路径检查合并成一次只读 shell 探测，避免一条规则一次往返。
 *
 * 设备端只执行 `[ -e/-f/-x ]` 与 `printf`，不会执行规则里的任何命令。
 */
object ProfileProbe {
    data class Plan(val command: String, val checks: List<FileCheck>)

    fun build(profiles: List<DeviceProfile>): Plan {
        val unique = LinkedHashSet<FileCheck>()
        profiles.forEach { profile ->
            unique += profile.match.all
            unique += profile.match.none
        }
        val checks = unique.toList()
        if (checks.isEmpty()) return Plan("true", checks)
        val command = checks.mapIndexed { index, check ->
            val flag = check.operation.testFlag
            val path = shellQuote(check.path)
            "[ $flag $path ] && printf 'CLP$index=1\\n' || printf 'CLP$index=0\\n';"
        }.joinToString(" ") + " true"
        return Plan(command, checks)
    }

    /** 解析探测输出；输出里缺少的检查项按 false 处理，由调用方判断是否整体失败。 */
    fun parse(output: String, checks: List<FileCheck>): Map<FileCheck, Boolean> {
        val result = LinkedHashMap<FileCheck, Boolean>()
        Regex("CLP(\\d+)=([01])").findAll(output).forEach { match ->
            val index = match.groupValues[1].toIntOrNull() ?: return@forEach
            val check = checks.getOrNull(index) ?: return@forEach
            result[check] = match.groupValues[2] == "1"
        }
        return result
    }

    /** POSIX shell 单引号转义，路径来自内置规则但仍在拼命令前转义。 */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
