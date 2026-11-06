package com.example.casdonlink.files

import android.util.Base64
import com.example.casdonlink.connection.TelnetClient

class FileRepository(private val session: TelnetClient.Session) {
    /**
     * 列出目录内容。
     *
     * 文件大小只读元数据：`wc -c < 文件` 会真正读取内容，遇到设备节点或管道会一直阻塞，
     * 遇到大文件也会拖慢整个目录。`stat -c %s` 不可用时才退回 `wc`，且只对普通文件使用。
     */
    suspend fun list(path: String): List<DeviceFile> {
        val safe = quote(path)
        val script = "for p in $safe/* $safe/.[!.]* $safe/..?*; do " +
            "[ -e \"\$p\" ] || [ -L \"\$p\" ] || continue; " +
            "if [ -d \"\$p\" ]; then t=d; else t=f; fi; " +
            "n=\${p##*/}; " +
            "s=\$(stat -c %s \"\$p\" 2>/dev/null); " +
            "if [ -z \"\$s\" ] && [ -f \"\$p\" ]; then s=\$(wc -c < \"\$p\" 2>/dev/null); fi; " +
            "[ -n \"\$s\" ] || s=0; " +
            "printf '%s\\t%s\\t%s\\n' \"\$t\" \"\$s\" \"\$n\"; " +
            "done"
        val output = session.execute(script, timeoutMs = 15_000)
        return output.lineSequence()
            .map { ANSI_ESCAPE.replace(it, "") }
            .mapNotNull { line ->
                val fields = line.split('\t', limit = 3)
                if (fields.size < 3 || fields[2].isEmpty()) null
                else DeviceFile(
                    name = fields[2],
                    path = path.trimEnd('/') + "/" + fields[2],
                    directory = fields[0] == "d",
                    size = fields[1].toLongOrNull() ?: 0
                )
            }
            .sortedWith(compareByDescending<DeviceFile> { it.directory }.thenBy { it.name.lowercase() })
            .toList()
    }

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
    }
}
