package com.example.casdonlink.packages

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

class PackageImporter(context: Context) {
    private val packageRoot = File(context.filesDir, "repair_packages").apply { mkdirs() }

    fun import(uri: Uri, open: (Uri) -> java.io.InputStream?): ImportedPackage {
        val archive = File.createTempFile("package_", ".zip", packageRoot)
        try {
            open(uri)?.use { input -> archive.outputStream().use(input::copyTo) }
                ?: error("无法读取所选文件")
            return validateAndExtract(archive)
        } finally { archive.delete() }
    }

    private fun validateAndExtract(archive: File): ImportedPackage {
        val target = File(packageRoot, "pkg_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            ZipFile(archive).use { zip ->
                val entries = zip.entries().asSequence().toList()
                require(entries.size in 1..200) { "压缩包条目数量超限" }
                var total = 0L
                entries.forEach { entry ->
                    val normalized = entry.name.replace('\\', '/')
                    require(!normalized.startsWith('/') && normalized.split('/').none { it == ".." }) { "压缩包包含不安全路径" }
                    require(!entry.isDirectory) { "暂不支持显式目录条目" }
                    require(entry.size in 0..MAX_ENTRY_SIZE) { "压缩包条目过大或无法校验大小" }
                    total += entry.size
                    require(total <= MAX_TOTAL_SIZE) { "压缩包解压总量超限" }
                    require(entry.method == java.util.zip.ZipEntry.STORED || entry.method == java.util.zip.ZipEntry.DEFLATED) { "不支持的压缩方法" }
                    val output = File(target, normalized)
                    require(output.canonicalPath.startsWith(target.canonicalPath + File.separator)) { "压缩包路径越界" }
                    output.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input -> output.outputStream().use { dest ->
                        val buffer = ByteArray(8192)
                        var written = 0L
                        while (true) {
                            val count = input.read(buffer); if (count < 0) break
                            written += count
                            require(written <= MAX_ENTRY_SIZE && total - entry.size + written <= MAX_TOTAL_SIZE) { "压缩包解压数据超限" }
                            dest.write(buffer, 0, count)
                        }
                    } }
                }
            }
            val manifestFile = File(target, "manifest.json")
            require(manifestFile.isFile) { "缺少 manifest.json" }
            val manifest = JSONObject(manifestFile.readText())
            require(manifest.getInt("formatVersion") == 1) { "不支持的修复包版本" }
            val stepsArray = manifest.getJSONArray("steps")
            require(stepsArray.length() in 1..100) { "步骤数量无效" }
            val ids = mutableSetOf<String>()
            val steps = (0 until stepsArray.length()).map { index ->
                val item = stepsArray.getJSONObject(index)
                val id = item.getString("id")
                require(ids.add(id)) { "步骤 ID 重复：$id" }
                val name = item.optString("name", id)
                val keepGoing = item.optBoolean("continueOnError", false)
                when (item.getString("type")) {
                    "shell" -> RepairStep.Shell(id, name, item.getString("command"), keepGoing,
                        item.optLong("timeoutSeconds", 30).coerceIn(1, 600))
                    "file" -> {
                        val source = safeRelative(item.getString("source"))
                        val sourceFile = File(target, source)
                        require(sourceFile.canonicalPath.startsWith(target.canonicalPath + File.separator) && sourceFile.isFile) { "资源文件不存在或路径无效：$source" }
                        val hash = item.optString("sha256").takeIf(String::isNotBlank)
                        if (hash != null) require(hash.matches(Regex("[a-fA-F0-9]{64}")) && sha256(sourceFile).equals(hash, true)) { "资源校验失败：$source" }
                        RepairStep.File(id, name, source, item.getString("target"), hash, keepGoing)
                    }
                    else -> error("未知步骤类型")
                }
            }
            val repairPackage = RepairPackage(1, manifest.getString("packageId"), manifest.getString("name"),
                manifest.getString("version"), manifest.optString("description"), steps)
            return ImportedPackage(target, repairPackage)
        } catch (error: Exception) {
            target.deleteRecursively()
            throw error
        }
    }

    private fun safeRelative(path: String): String {
        val normalized = path.replace('\\', '/')
        require(normalized.isNotBlank() && !normalized.startsWith('/') && normalized.split('/').none { it == ".." }) { "无效资源路径" }
        return normalized
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    data class ImportedPackage(val directory: File, val manifest: RepairPackage)

    private companion object {
        const val MAX_ENTRY_SIZE = 64L * 1024 * 1024
        const val MAX_TOTAL_SIZE = 128L * 1024 * 1024
    }
}
