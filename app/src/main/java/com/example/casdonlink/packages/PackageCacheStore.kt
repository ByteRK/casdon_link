package com.example.casdonlink.packages

import android.content.Context
import java.io.File

/** 修复包解压缓存：只统计/清理 `repair_packages/pkg_*`，不触碰其他应用数据。 */
data class PackageCacheInfo(val count: Int, val bytes: Long)

object PackageCacheStore {
    private const val ROOT = "repair_packages"
    private const val PREFIX = "pkg_"

    private fun root(context: Context): File = File(context.filesDir, ROOT)

    fun info(context: Context): PackageCacheInfo {
        val directories = root(context).listFiles { file ->
            file.isDirectory && file.name.startsWith(PREFIX)
        }.orEmpty()
        val bytes = directories.sumOf { directory ->
            directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
        return PackageCacheInfo(directories.size, bytes)
    }

    /** 清理解压目录和导入过程中残留的临时 ZIP。 */
    fun clear(context: Context) {
        root(context).listFiles { file ->
            file.isDirectory && file.name.startsWith(PREFIX)
        }?.forEach { runCatching { it.deleteRecursively() } }
        context.cacheDir.listFiles { file ->
            file.isFile && file.name.startsWith("package_import_")
        }?.forEach { runCatching { it.delete() } }
    }
}
