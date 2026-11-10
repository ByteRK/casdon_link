package com.example.casdonlink.profile

/**
 * 机型规则（机型档案）模型。
 *
 * 一套规则同时描述「怎么识别设备」「怎么补授权码」「快捷操作有哪些」。
 * 规则由 App 内置在 assets/profiles.json，运行时只做只读探测，不执行规则里的任意识别命令。
 */

/** 机型识别只允许文件级检查，避免规则里塞入任意 shell 命令。 */
enum class FileOperation(val testFlag: String) {
    EXISTS("-e"),
    REGULAR("-f"),
    EXECUTABLE("-x");

    companion object {
        fun parse(value: String): FileOperation? = values().firstOrNull {
            it.name.equals(value, ignoreCase = true)
        }
    }
}

/** 对设备上某个绝对路径做一次只读检查。 */
data class FileCheck(val path: String, val operation: FileOperation)

/** 规则命中条件：all 全部为真，none 全部为假。 */
data class ProfileMatch(val all: List<FileCheck>, val none: List<FileCheck>)

/** 从设备现有授权文件里提取字段的正则；每条正则至少包含一个捕获组。 */
data class LicenseReadSpec(
    val pidPattern: String,
    val didPattern: String,
    val licensePattern: String?
)

/** 补发授权码所需的规则配置。 */
data class LicenseConfig(
    val pid: String,
    val networkInterface: String,
    val targetPath: String,
    val contentTemplate: String,
    val read: LicenseReadSpec
)

/** 快捷操作里的一条动作，可以包含一串按顺序执行的命令。 */
data class QuickAction(
    val id: String,
    val label: String,
    val description: String,
    val commands: List<String>,
    val confirm: Boolean,
    val danger: Boolean,
    val output: Boolean,
    val timeoutSeconds: Long,
    val continueOnError: Boolean,
    val reboot: Boolean
)

/** 快捷操作分组，二级页按组展示。 */
data class QuickActionGroup(val title: String, val actions: List<QuickAction>)

/** 一个机型的完整规则。 */
data class DeviceProfile(
    val id: String,
    val name: String,
    val priority: Int,
    val match: ProfileMatch,
    val license: LicenseConfig?,
    val quickActions: List<QuickActionGroup>
) {
    val hasQuickActions: Boolean get() = quickActions.any { it.actions.isNotEmpty() }
    val actionCount: Int get() = quickActions.sumOf { it.actions.size }
}
