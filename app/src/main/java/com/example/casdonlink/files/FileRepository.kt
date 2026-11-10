package com.example.casdonlink.files

import android.util.Base64
import com.example.casdonlink.connection.Diagnostics
import com.example.casdonlink.connection.TelnetClient
import kotlinx.coroutines.CancellationException
import java.net.SocketTimeoutException

class FileRepository(private val session: TelnetClient.Session) {
    /** 已确认可写的目录；同一次执行（同一个仓库实例）内不再重复探测。 */
    private val writableDirs = mutableSetOf<String>()

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
            .mapNotNull { line -> parseRecord(line, path) }
            .sortedWith(FILE_ORDER)
            .toList()
    }

    /**
     * 解析一行 `类型 \t 大小 \t 名称`。
     *
     * 若设备未能关闭 pty 的 tab 展开（OXTABS 会把 TAB 换成空格）或自行改造了输出，
     * 则退回按空白分隔解析，避免整份列表被当作空结果而不得不降级。
     */
    private fun parseRecord(line: String, parent: String): DeviceFile? {
        val fields = line.split('\t', limit = 3)
        val parsed = if (fields.size >= 3 && fields[2].isNotEmpty()) {
            Triple(fields[0], fields[1], fields[2])
        } else {
            val match = SPACE_SEPARATED_RECORD.find(line) ?: return null
            Triple(match.groupValues[1], match.groupValues[2], match.groupValues[3])
        }
        if (parsed.first != "d" && parsed.first != "f") return null
        return DeviceFile(
            name = parsed.third,
            path = childPath(parent, parsed.third),
            directory = parsed.first == "d",
            size = parsed.second.toLongOrNull() ?: 0
        )
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
                    // 简易方式只列名字，拿不到大小，用 UNKNOWN_SIZE 区分于真正的 0 字节文件。
                    size = UNKNOWN_SIZE
                )
            }
            .sortedWith(FILE_ORDER)
            .toList()
    }

    private fun childPath(parent: String, name: String): String = parent.trimEnd('/') + "/" + name

    suspend fun readText(path: String, maxBytes: Int = PREVIEW_BYTES): String {
        val encoded = session.execute("head -c $maxBytes ${quote(path)} | base64 | tr -d '\\r\\n'")
        return String(Base64.decode(encoded.filterNot(Char::isWhitespace), Base64.DEFAULT), Charsets.UTF_8)
    }

    suspend fun delete(path: String) { session.execute("rm -rf -- ${quote(path)}") }

    /**
     * 新建空文件。`touch` 在目标已存在时只更新时间戳，不会清空原有内容；
     * 设备缺少 `touch` 时由 shell 重定向作为傅底。
     */
    suspend fun createFile(path: String) {
        session.execute("command -v touch >/dev/null 2>&1 && touch ${quote(path)} || : > ${quote(path)}")
    }

    /** 新建目录，已存在时视为成功（`-p` 语义，不报错）。 */
    suspend fun createDirectory(path: String) {
        session.execute("mkdir -p ${quote(path)}")
    }

    suspend fun rename(source: String, target: String) {
        session.execute("mv -- ${quote(source)} ${quote(target)}")
    }

    /**
     * 通过局域网 HTTP 让设备端 `wget` 拉取文件，并在传输期间轮询设备端已写入的字节数。
     *
     * 大文件如果让 wget 前台执行，会一直占用受控命令通道直到超时，既看不到进度，
     * 也无法判断是否卡死。这里把 wget 放进后台子 shell，并把退出码写到旁路文件，
     * 再用独立的短命令轮询大小，因此可以上报真实进度、检测长时间无进展，
     * 并在取消时杀掉设备端进程、删掉半个文件。
     *
     * 现场无线链路存在秒级抖动：轮询命令本身可能超时，而设备端下载仍在推进。
     * 因此单次轮询超时只算「本轮没取到样本」，只有文件大小在 [STALL_TIMEOUT_MS]
     * 内没有增长才判定失败；失败后保留半成品并用 `wget -c` 续传重试，
     * 下载完成后再按期望大小与 sha256 双重校验，校验通过才替换目标文件。
     */
    suspend fun uploadFromHttp(
        url: String,
        target: String,
        totalBytes: Long = 0,
        expectedSha256: String? = null,
        isCancelled: () -> Boolean = { false },
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        onRetry: (attempt: Int, reason: String) -> Unit = { _, _ -> }
    ) {
        require(url.startsWith("http://")) { "仅支持局域网 HTTP 传输" }
        // 只读文件系统（如嵌入式设备的 squashfs 根目录）无法写入临时文件，
        // 此时 wget 会立即失败且退出码也写不下来，必须提前探测并给出明确提示。
        val dir = target.substringBeforeLast('/', "/").ifEmpty { "/" }
        ensureWritable(dir)
        // 临时文件与退出码都放在目标同目录，不依赖 /tmp 是否可写。
        val part = "$target.casdonpart"
        val marker = "$target.casdonrc"
        preparePart(part, marker, totalBytes)
        var attempt = 0
        while (true) {
            attempt++
            try {
                transferOnce(url, part, marker, totalBytes, isCancelled, onProgress)
                // 校验放在替换之前：续传拼接出的字节若不一致，绝不覆盖设备上的原文件。
                verifyTransfer(part, totalBytes, expectedSha256)
                session.execute("mv -f ${quote(part)} ${quote(target)}")
                val done = if (totalBytes > 0) totalBytes else remoteSize(part)
                onProgress(done, done)
                runCatching { session.execute("rm -f ${quote(marker)}", timeoutMs = 5_000) }
                return
            } catch (cancelled: CancellationException) {
                runCatching { session.execute("rm -f ${quote(marker)} ${quote(part)}", timeoutMs = 5_000) }
                throw cancelled
            } catch (error: Exception) {
                if (isCancelled()) {
                    runCatching { session.execute("rm -f ${quote(marker)} ${quote(part)}", timeoutMs = 5_000) }
                    throw IllegalStateException("已取消")
                }
                // 校验失败说明半成品不可用，删掉整份重下；传输中断则保留半成品等续传。
                if (error is IntegrityException) {
                    runCatching { session.execute("rm -f ${quote(part)}", timeoutMs = 5_000) }
                }
                runCatching { session.execute("rm -f ${quote(marker)}", timeoutMs = 5_000) }
                Diagnostics.log("上传失败（第 $attempt/$MAX_UPLOAD_ATTEMPTS 次）：$target · ${error.message}")
                if (attempt >= MAX_UPLOAD_ATTEMPTS) {
                    runCatching { session.execute("rm -f ${quote(part)}", timeoutMs = 5_000) }
                    throw error
                }
                onRetry(attempt, error.message ?: error.javaClass.simpleName)
                kotlinx.coroutines.delay(RETRY_DELAY_MS)
            }
        }
    }

    /**
     * 清理上一次遗留的退出码文件。
     *
     * 半成品只有在「不可能再续传」时（已达到目标大小，说明是旧版本文件）才删除；
     * 否则保留给 `wget -c` 继续下载，避免每次抖动都从头重传。
     */
    private suspend fun preparePart(part: String, marker: String, totalBytes: Long) {
        val staleCleanup = if (totalBytes > 0) {
            "f=${quote(part)}; if [ -f \"\$f\" ]; then " +
                "s=\$(stat -c %s \"\$f\" 2>/dev/null); [ -n \"\$s\" ] || s=\$(wc -c < \"\$f\" 2>/dev/null); " +
                "[ -n \"\$s\" ] || s=0; [ \"\$s\" -ge $totalBytes ] && rm -f \"\$f\"; fi; "
        } else {
            ""
        }
        session.execute("rm -f ${quote(marker)}; $staleCleanup true", timeoutMs = 10_000)
    }

    /** 单次传输尝试：启动后台 wget 并轮询到出现退出码（断链/超时会抛出，半成品保留）。 */
    private suspend fun transferOnce(
        url: String,
        part: String,
        marker: String,
        totalBytes: Long,
        isCancelled: () -> Boolean,
        onProgress: (Long, Long) -> Unit
    ) {
        var pid: String? = null
        try {
            // 已有半成品时先取一次大小，续传场景下进度从该值接着走。
            var lastSize = remoteSizeOrNull(part) ?: 0L
            pid = startBackgroundTransfer(url, part, marker)
            var lastChange = System.currentTimeMillis()
            val startedAt = lastChange
            if (lastSize > 0) onProgress(lastSize, if (totalBytes > 0) totalBytes else lastSize)
            while (true) {
                if (isCancelled()) throw IllegalStateException("已取消")
                val sample = pollTransfer(part, marker)
                if (sample != null) {
                    val (size, code) = sample
                    if (size > lastSize) {
                        lastSize = size
                        lastChange = System.currentTimeMillis()
                    }
                    onProgress(size, if (totalBytes > 0) totalBytes else size)
                    // 退出码非 0 代表 wget 自身失败；退出码 0 但大小不足由外层校验判定。
                    if (code != null) {
                        if (code != 0) throw IllegalStateException("设备端下载失败（退出码 $code）")
                        return
                    }
                }
                val now = System.currentTimeMillis()
                // 一个字节都没收到时用更短的窗口，避免权限/命令缺失等问题让用户干等。
                val idleLimit = if (lastSize == 0L) FIRST_BYTE_TIMEOUT_MS else STALL_TIMEOUT_MS
                if (now - lastChange > idleLimit) {
                    throw IllegalStateException("传输超时（${idleLimit / 1000} 秒内没有收到数据）")
                }
                if (now - startedAt > UPLOAD_MAX_MS) throw IllegalStateException("传输超时（文件过大或网络过慢）")
                kotlinx.coroutines.delay(POLL_INTERVAL_MS)
            }
        } catch (error: Exception) {
            // 只终止设备端进程，半成品留给续传；彻底失败/取消由调用方清理。
            pid?.let { runCatching { session.execute("kill $it 2>/dev/null", timeoutMs = 5_000) } }
            throw error
        }
    }

    /** 用一次性探测文件确认目录真能写入（`-w` 在只读挂载上会被 root 权限绕过，不可靠）。 */
    private suspend fun ensureWritable(dir: String) {
        // 同一次执行会反复写入同一目录，探测结果缓存，避免每个文件多一条命令。
        if (writableDirs.contains(dir)) return
        val probe = dir.trimEnd('/') + "/.casdonprobe"
        val output = session.execute(
            "if : > ${quote(probe)} 2>/dev/null; then rm -f ${quote(probe)}; echo ok; else echo no; fi"
        )
        if (!output.contains("ok")) throw IllegalStateException("目录不可写：$dir")
        writableDirs.add(dir)
    }

    /**
     * 启动后台 wget，返回设备端进程号，供取消时终止。
     *
     * `-c` 让 wget 在已有半成品时带 `Range: bytes=<已有字节>-` 续传；若服务端不支持
     * Range（直接回 200 整文件），BusyBox wget 会重新从头写入，不会叠加到文件尾部。
     */
    private suspend fun startBackgroundTransfer(url: String, part: String, marker: String): String {
        val script = "rm -f ${quote(marker)}; " +
            "( wget -c -O ${quote(part)} ${quote(url)} >/dev/null 2>&1; echo \$? > ${quote(marker)} ) >/dev/null 2>&1 & echo \$!"
        val output = session.execute(script, timeoutMs = 10_000)
        return output.lineSequence().mapNotNull { it.trim().toLongOrNull() }.lastOrNull()?.toString()
            ?: throw IllegalStateException("无法启动设备端下载")
    }

    /**
     * 轮询一次传输状态，返回「已写入字节数」与「wget 退出码（未结束时为 null）」。
     *
     * 查询本身超时或输出无法解析时返回 null（本轮没有样本）：此时设备端下载很可能仍在
     * 推进，若据此判定失败会白白杀掉正在传的文件，正是现场「很容易超时」的根因。
     */
    private suspend fun pollTransfer(part: String, marker: String): Pair<Long, Int?>? {
        // 先判断文件存在再取大小：设备缺少 stat 时用 wc 兜底，且避免不存在的路径向 pty 打错误。
        val script = "s=0; f=${quote(part)}; " +
            "[ -f \"\$f\" ] && { s=\$(stat -c %s \"\$f\" 2>/dev/null); [ -n \"\$s\" ] || s=\$(wc -c < \"\$f\" 2>/dev/null); }; " +
            "c=\$(cat ${quote(marker)} 2>/dev/null); " +
            "printf '%s %s\\n' \"\${s:-0}\" \"\$c\""
        val output = try {
            session.execute(script, timeoutMs = POLL_TIMEOUT_MS)
        } catch (timeout: SocketTimeoutException) {
            Diagnostics.log("进度查询超时，等待下一次采样：${part.substringAfterLast('/')}")
            return null
        }
        val record = output.lineSequence()
            .map { it.trim() }
            .mapNotNull { POLL_RECORD.find(it) }
            .lastOrNull() ?: return null
        return (record.groupValues[1].toLongOrNull() ?: 0L) to record.groupValues[2].toIntOrNull()
    }

    /** 替换目标文件前的大小与内容校验；哈希不一致说明续传拼错了字节，必须整份重下。 */
    private suspend fun verifyTransfer(part: String, totalBytes: Long, expectedSha256: String?) {
        val size = remoteSize(part)
        if (totalBytes > 0 && size != totalBytes) {
            throw IntegrityException("传输不完整（期望 $totalBytes 字节，实际 $size 字节）")
        }
        val expected = expectedSha256?.trim()?.lowercase()?.takeIf { it.matches(SHA256_PATTERN) } ?: return
        val actual = runCatching {
            session.execute("sha256sum ${quote(part)} 2>/dev/null", timeoutMs = HASH_TIMEOUT_MS)
        }.getOrNull()?.substringBefore(' ')?.trim()?.lowercase()
        if (actual.isNullOrEmpty()) {
            // 设备缺少 sha256sum 或校验命令因网络抖动超时：大小校验已通过，记录后放行。
            Diagnostics.log("跳过 sha256 校验（设备无 sha256sum 或命令超时）：$part")
            return
        }
        if (actual != expected) throw IntegrityException("内容校验失败（sha256 不一致）")
    }

    /** 半成品内容不可信（大小或哈希不匹配）：只能整份重下，不能继续续传。 */
    private class IntegrityException(message: String) : IllegalStateException(message)

    private suspend fun remoteSize(path: String): Long =
        remoteSizeOrNull(path) ?: throw IllegalStateException("无法读取文件大小：$path")

    /** 读取设备端文件大小；不存在或无法取得时返回 null。 */
    private suspend fun remoteSizeOrNull(path: String): Long? {
        val output = session.execute(
            "if [ -f ${quote(path)} ]; then stat -c %s ${quote(path)} 2>/dev/null || " +
                "wc -c < ${quote(path)} 2>/dev/null; fi",
            timeoutMs = POLL_TIMEOUT_MS
        )
        return output.trim().toLongOrNull()
    }

    /**
     * 下载并解码设备文件。
     *
     * 受控命令的匹配缓冲区有上限，整文件 base64 在约 196 KB 后就会把起始标记挤掉，
     * 表现为超时。这里按固定块大小逐块 `dd | base64`，每块输出都远小于缓冲区上限，
     * 再在手机侧拼接解码；同时避免一次命令等待过久。
     */
    suspend fun downloadBase64(
        path: String,
        isCancelled: () -> Boolean = { false },
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): ByteArray {
        // BusyBox 一般带 stat；缺失时退回到只读元数据的 wc -c。
        val size = runCatching { session.execute("stat -c %s ${quote(path)}") }.getOrNull()
            ?.trim()?.toLongOrNull()
            ?: runCatching { session.execute("wc -c < ${quote(path)}") }.getOrNull()
                ?.trim()?.toLongOrNull()
            ?: throw IllegalStateException("无法读取文件大小：$path")
        val output = java.io.ByteArrayOutputStream(
            minOf(size, 4L * 1024 * 1024).toInt()
        )
        onProgress(0, size)
        var offset = 0L
        while (offset < size) {
            if (isCancelled()) throw IllegalStateException("已取消")
            val index = offset / DOWNLOAD_CHUNK_BYTES
            val expected = minOf(DOWNLOAD_CHUNK_BYTES.toLong(), size - offset).toInt()
            val encoded = session.execute(
                "dd if=${quote(path)} bs=$DOWNLOAD_CHUNK_BYTES skip=$index count=1 2>/dev/null | base64 | tr -d '\\r\\n'",
                timeoutMs = DOWNLOAD_CHUNK_TIMEOUT_MS
            )
            val chunk = Base64.decode(encoded.filterNot(Char::isWhitespace), Base64.DEFAULT)
            // 设备端 dd/base64 失败时管道末端的 tr 仍可能返回 0，必须按长度校验，避免静默截断。
            if (chunk.size != expected) {
                throw IllegalStateException("下载分块不完整（偏移 $offset，期望 $expected 字节，实际 ${chunk.size} 字节）")
            }
            output.write(chunk)
            offset += chunk.size
            onProgress(offset, size)
        }
        return output.toByteArray()
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        /** 文本预览上限：base64 后仍明显低于受控命令的 256K 字符匹配缓冲上限。 */
        const val PREVIEW_BYTES = 64_000
        /** 单次下载分块大小；base64 后约 175K 字符，留出足够余量。 */
        const val DOWNLOAD_CHUNK_BYTES = 128 * 1024
        const val DOWNLOAD_CHUNK_TIMEOUT_MS = 20_000L
        /** 上传进度轮询间隔；无线链路上查询本身要占通道，1 秒一次足够且更抗抖动。 */
        const val POLL_INTERVAL_MS = 1_000L
        /** 单条轮询命令的等待上限；超时只当作「本轮没样本」，不判定传输失败。 */
        const val POLL_TIMEOUT_MS = 15_000L
        /** 一段时间内设备端文件大小没有增长，判定链路已断。 */
        const val STALL_TIMEOUT_MS = 90_000L
        /** 一个字节都还没到时的容忍时间，用于快速暴露写入/命令问题。 */
        const val FIRST_BYTE_TIMEOUT_MS = 30_000L
        /** 单次上传的硬上限，防止设备端进程挂死时无限等待。 */
        const val UPLOAD_MAX_MS = 20 * 60_000L
        /** 单个文件的传输尝试次数；断链时用 `wget -c` 从已下载位置继续。 */
        const val MAX_UPLOAD_ATTEMPTS = 3
        /** 两次尝试之间的等待，给链路一点恢复时间。 */
        const val RETRY_DELAY_MS = 1_500L
        /** 设备端 sha256 校验的等待上限（10MB 文件在 armv7 上约 1~2 秒）。 */
        const val HASH_TIMEOUT_MS = 60_000L
        /** 清单里的 sha256 统一按小写十六进制比较。 */
        val SHA256_PATTERN = Regex("[a-f0-9]{64}")
        /** 上传轮询返回的 `已写入字节数 [退出码]`；未结束时第二列为空，可能被 trim 掉。 */
        val POLL_RECORD = Regex("^(\\d+)(?:\\s+(\\d+))?$")
        /** 去掉颜色、光标控制等 ANSI 序列，避免干扰按行解析。 */
        val ANSI_ESCAPE = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
        /** TAB 被展开成空格时的兼容格式：`类型 + 空白 + 大小 + 空白 + 名称`。 */
        val SPACE_SEPARATED_RECORD = Regex("^([df])\\s+(\\d+)\\s+(.+)$")
        /** 目录优先，同级按名称排序。 */
        val FILE_ORDER = compareByDescending<DeviceFile> { it.directory }.thenBy { it.name.lowercase() }
    }
}
