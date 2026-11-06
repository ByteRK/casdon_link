package com.example.casdonlink.files

import android.util.Base64
import com.example.casdonlink.connection.Diagnostics
import com.example.casdonlink.connection.TelnetClient

class FileRepository(private val session: TelnetClient.Session) {
    /**
     * 列出目录内容。
     *
     * 先用结构化命令取得「文件/目录标记 + 大小 + 名称」；若设备对较长的组合命令
     * 不友好（无输出或命令被拒绝），退回最基础的 `ls -1ap`，用结尾 `/` 判断目录。
     * 结构化输出里不含 `!`，避免交互式 bash 的历史展开把整行判为非法而不执行。
     */
    suspend fun list(path: String): List<DeviceFile> {
        val structured = try {
            listStructured(path)
        } catch (error: Exception) {
            // 回退方案仍可能成功；这里只记录原因，不打断流程。
            Diagnostics.log("结构化列目录失败：${error.javaClass.simpleName}: ${error.message}")
            emptyList()
        }
        if (structured.isNotEmpty()) return structured
        Diagnostics.log("结构化列目录无结果，退回 ls -1ap：$path")
        val simple = listSimple(path)
        Diagnostics.log("目录 $path 列举到 ${simple.size} 项（简易方式）")
        return simple
    }

    /** 结构化列目录：每行 `类型 \t 大小 \t 名称`。大小只读元数据，不读取文件内容。 */
    private suspend fun listStructured(path: String): List<DeviceFile> {
        val safe = quote(path)
        val script = "for p in $safe/* $safe/.*; do " +
            "n=\${p##*/}; " +
            "if [ \"\$n\" = . ] || [ \"\$n\" = .. ]; then continue; fi; " +
            "[ -e \"\$p\" ] || [ -L \"\$p\" ] || continue; " +
            "if [ -d \"\$p\" ]; then t=d; else t=f; fi; " +
            "s=\$(stat -c %s \"\$p\" 2>/dev/null); " +
            "if [ -z \"\$s\" ] && [ -f \"\$p\" ]; then s=\$(wc -c < \"\$p\" 2>/dev/null); fi; " +
            "[ -n \"\$s\" ] || s=0; " +
            "printf '%s\\t%s\\t%s\\n' \"\$t\" \"\$s\" \"\$n\"; " +
            "done"
        val output = session.execute(script, timeoutMs = 10_000)
        if (output.isBlank()) Diagnostics.log("结构化列目录输出为空：$path")
        return output.lineSequence()
            .map { ANSI_ESCAPE.replace(it, "") }
            .mapNotNull { line ->
                val fields = line.split('\t', limit = 3)
                if (fields.size < 3 || fields[2].isEmpty()) null
                else DeviceFile(
                    name = fields[2],
                    path = childPath(path, fields[2]),
                    directory = fields[0] == "d",
                    size = fields[1].toLongOrNull() ?: 0
                )
            }
            .sortedWith(FILE_ORDER)
            .toList()
    }

    /** 回退方案：只用 `ls`，不依赖 stat/printf，几乎在所有 BusyBox/完整发行版上都可用。 */
    private suspend fun listSimple(path: String): List<DeviceFile> {
        val output = session.execute(
            "cd ${quote(path)} 2>/dev/null || exit 9; ls -1ap",
            timeoutMs = 10_000
        )
        if (output.isBlank()) Diagnostics.log("ls -1ap 输出为空：$path")
        return output.lineSequence()
            .map { ANSI_ESCAPE.replace(it, "").trimEnd() }
            .mapNotNull { line ->
                val name = line.removeSuffix("/")
                if (name.isEmpty() || name == "." || name == "..") null
                else DeviceFile(
                    name = name,
                    path = childPath(path, name),
                    directory = line.endsWith("/"),
                    size = 0
                )
            }
            .sortedWith(FILE_ORDER)
            .toList()
    }

    private fun childPath(parent: String, name: String): String = parent.trimEnd('/') + "/" + name

    suspend fun readText(path: String, maxBytes: Int = 256_000): String {
        val encoded = session.execute("head -c $maxBytes ${quote(path)} | base64 | tr -d '\\r\\n'")
        return String(Base64.decode(encoded.filterNot(Char::isWhitespace), Base64.DEFAULT), Charsets.UTF_8)
    }

    suspend fun delete(path: String) { session.execute("rm -rf -- ${quote(path)}") }

    suspend fun rename(source: String, target: String) {
        session.execute("mv -- ${quote(source)} ${quote(target)}")
    }

    suspend fun uploadFromHttp(url: String, target: String) {
        require(url.startsWith("http://")) { "仅支持局域网 HTTP 传输" }
        session.execute("wget -O ${quote(target)} ${quote(url)}")
    }

    suspend fun downloadBase64(path: String): ByteArray {
        val encoded = session.execute("base64 ${quote(path)} | tr -d '\\r\\n'")
        return Base64.decode(encoded.filterNot(Char::isWhitespace), Base64.DEFAULT)
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        /** 去掉颜色、光标控制等 ANSI 序列，避免干扰按行解析。 */
        val ANSI_ESCAPE = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
        /** 目录优先，同级按名称排序。 */
        val FILE_ORDER = compareByDescending<DeviceFile> { it.directory }.thenBy { it.name.lowercase() }
    }
}
