package com.example.casdonlink.files

import android.util.Base64
import com.example.casdonlink.connection.TelnetClient

class FileRepository(private val session: TelnetClient.Session) {
    suspend fun list(path: String): List<DeviceFile> {
        val safe = quote(path)
        val output = session.execute("for p in $safe/* $safe/.[!.]* $safe/..?*; do [ -e \"\$p\" ] || continue; if [ -d \"\$p\" ]; then t=d; else t=f; fi; n=\${p##*/}; s=\$(wc -c < \"\$p\" 2>/dev/null); printf '%s\\t%s\\t%s\\n' \"\$t\" \"\$s\" \"\$n\"; done")
        return output.lineSequence().mapNotNull { line ->
            val fields = line.split('\t', limit = 3)
            if (fields.size < 3) null else DeviceFile(fields[2], path.trimEnd('/') + "/" + fields[2], fields[0] == "d", fields[1].toLongOrNull() ?: 0)
        }.sortedWith(compareByDescending<DeviceFile> { it.directory }.thenBy { it.name.lowercase() }).toList()
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
}
