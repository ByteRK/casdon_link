package com.example.casdonlink.packages

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * 修复包导入：解密后的 ZIP 已经由 [PackageCrypto] 写到临时文件，这里只负责
 * 校验 ZIP 结构、解压资源和解析 manifest。加密容器本身不在这一层处理。
 */
class PackageImporter(context: Context) {
    private val packageRoot = File(context.filesDir, "repair_packages").apply { mkdirs() }

    /** 导入一个已经解密到本地的修复包 ZIP；成功返回解压目录与清单。 */
    fun importZip(archive: File): ImportedPackage {
        require(archive.isFile) { "修复包文件不存在" }
        return validateAndExtract(archive)
    }

    private fun validateAndExtract(archive: File): ImportedPackage {
        val target = File(packageRoot, "pkg_${System.currentTimeMillis()}_${System.nanoTime()}").apply { mkdirs() }
        try {
            ZipFile(archive).use { zip ->
                val entries = zip.entries().asSequence().toList()
                require(entries.size in 1..200) { "压缩包条目数量超限" }
                var total = 0L
                val paths = mutableSetOf<String>()
                entries.forEach { entry ->
                    val normalized = entry.name.replace('\\', '/')
                    require(!normalized.startsWith('/') && normalized.split('/').none { it == ".." }) {
                        "压缩包包含不安全路径"
                    }
                    require(paths.add(normalized)) { "压缩包包含重复路径" }
                    // 标准 ZIP 工具会自动写入目录条目；目录本身不产生文件，跳过即可。
                    if (entry.isDirectory) return@forEach
                    require(entry.size in 0..MAX_ENTRY_SIZE) { "压缩包条目过大或无法校验大小" }
                    total += entry.size
                    require(total <= MAX_TOTAL_SIZE) { "压缩包解压总量超限" }
                    require(entry.method == java.util.zip.ZipEntry.STORED || entry.method == java.util.zip.ZipEntry.DEFLATED) {
                        "不支持的压缩方法"
                    }
                    val output = File(target, normalized)
                    require(output.canonicalPath.startsWith(target.canonicalPath + File.separator)) {
                        "压缩包路径越界"
                    }
                    output.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        output.outputStream().use { dest ->
                            val buffer = ByteArray(8192)
                            var written = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                written += count
                                require(written <= MAX_ENTRY_SIZE && total - entry.size + written <= MAX_TOTAL_SIZE) {
                                    "压缩包解压数据超限"
                                }
                                dest.write(buffer, 0, count)
                            }
                        }
                    }
                }
            }
            val manifestFile = File(target, "manifest.json")
            require(manifestFile.isFile) { "缺少 manifest.json" }
            val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
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
                    "shell" -> RepairStep.Shell(
                        id, name, item.getString("command"), keepGoing,
                        item.optLong("timeoutSeconds", 30).coerceIn(1, 600)
                    )
                    "file" -> {
                        val source = safeRelative(item.getString("source"))
                        val sourceFile = File(target, source)
                        require(
                            sourceFile.canonicalPath.startsWith(target.canonicalPath + File.separator) &&
                                sourceFile.isFile
                        ) { "资源文件不存在或路径无效：$source" }
                        val hash = item.optString("sha256").takeIf(String::isNotBlank)
                        if (hash != null) {
                            require(hash.matches(Regex("[a-fA-F0-9]{64}")) && sha256(sourceFile).equals(hash, true)) {
                                "资源校验失败：$source"
                            }
                        }
                        RepairStep.File(id, name, source, item.getString("target"), hash, keepGoing)
                    }
                    else -> error("未知步骤类型")
                }
            }
            val repairPackage = RepairPackage(
                1, manifest.getString("packageId"), manifest.getString("name"),
                manifest.getString("version"), manifest.optString("description"), steps
            )
            writeMetadata(target, repairPackage)
            pruneOldPackages()
            return ImportedPackage(target, repairPackage)
        } catch (error: Exception) {
            target.deleteRecursively()
            throw error
        }
    }

    private fun safeRelative(path: String): String {
        val normalized = path.replace('\\', '/')
        require(normalized.isNotBlank() && !normalized.startsWith('/') && normalized.split('/').none { it == ".." }) {
            "无效资源路径"
        }
        return normalized
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 写入包元数据，供后续“最近包列表”或缓存治理使用；不参与执行。 */
    private fun writeMetadata(directory: File, repairPackage: RepairPackage) {
        runCatching {
            File(directory, METADATA_FILE).writeText(
                JSONObject()
                    .put("packageId", repairPackage.packageId)
                    .put("name", repairPackage.name)
                    .put("version", repairPackage.version)
                    .put("steps", repairPackage.steps.size)
                    .put("importedAt", System.currentTimeMillis())
                    .toString(),
                Charsets.UTF_8
            )
        }
    }

    /** 保留最近 [MAX_CACHED_PACKAGES] 个解压目录，避免每次导入都永久占用存储。 */
    private fun pruneOldPackages() {
        val directories = packageRoot.listFiles { file ->
            file.isDirectory && file.name.startsWith("pkg_")
        }?.sortedByDescending { it.lastModified() } ?: return
        directories.drop(MAX_CACHED_PACKAGES).forEach { directory ->
            runCatching { directory.deleteRecursively() }
        }
    }

    data class ImportedPackage(val directory: File, val manifest: RepairPackage)

    private companion object {
        const val MAX_ENTRY_SIZE = 64L * 1024 * 1024
        const val MAX_TOTAL_SIZE = 128L * 1024 * 1024
        const val MAX_CACHED_PACKAGES = 5
        const val METADATA_FILE = ".casdon-package.json"
    }
}
