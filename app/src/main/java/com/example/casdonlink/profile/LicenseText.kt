package com.example.casdonlink.profile

/** 授权文件内容生成与读取。 */
object LicenseText {
    data class Parsed(val pid: String, val did: String, val license: String)

    fun render(template: String, pid: String, did: String, license: String): String =
        template.replace("{{pid}}", pid)
            .replace("{{did}}", did)
            .replace("{{license}}", license)

    fun parse(content: String, read: LicenseReadSpec): Parsed? {
        if (content.isBlank()) return null
        val pid = capture(read.pidPattern, content) ?: return null
        val did = capture(read.didPattern, content) ?: return null
        val license = read.licensePattern?.let { capture(it, content) }.orEmpty()
        return Parsed(pid = pid, did = did, license = license)
    }

    /** 界面展示授权码时只保留首尾，完整值只在导出文件/内部重写时使用。 */
    fun mask(value: String): String = when {
        value.isEmpty() -> ""
        value.length <= 8 -> "********"
        else -> value.take(4) + "…" + value.takeLast(4)
    }

    private fun capture(pattern: String, content: String): String? = runCatching {
        Regex(pattern).find(content)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()
}
