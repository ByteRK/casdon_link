package com.example.casdonlink.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** socket 读超时：既能让阻塞读定期返回，便于检查协程取消与连接状态。 */
private const val READ_POLL_TIMEOUT_MS = 500

/** 单次等待登录提示的时限；BusyBox 设备首次输出可能较慢。 */
private const val HANDSHAKE_TIMEOUT_MS = 10_000L

class TelnetClient {
    /** Telnet 登录凭证；设备无需登录时传 null。 */
    data class Credentials(val username: String, val password: String)

    /**
     * 建立会话并完成 shell 握手。设备若要求登录，会使用 [credentials] 自动认证；
     * 未提供凭证时会抛出说明性错误，由调用方向用户提示。
     */
    suspend fun connect(
        host: String,
        port: Int = 23,
        timeoutMs: Int = 5_000,
        credentials: Credentials? = null
    ): Session = withContext(Dispatchers.IO) {
        Diagnostics.log("开始连接 $host:$port（超时 ${timeoutMs}ms）")
        val startedAt = System.currentTimeMillis()
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), timeoutMs)
        } catch (error: Exception) {
            Diagnostics.log("TCP 连接失败：${error.javaClass.simpleName}: ${error.message}")
            runCatching { socket.close() }
            throw error
        }
        Diagnostics.log("TCP 连接成功，耗时 ${System.currentTimeMillis() - startedAt}ms")
        socket.soTimeout = READ_POLL_TIMEOUT_MS
        val session = Session(socket)
        try {
            session.handshake(credentials)
            // 部分设备的 pty 开着 tab3（OXTABS），会把输出里的 TAB 展开成空格，
            // 使「类型 \t 大小 \t 名称」的结构化列目录无法解析。这里只做尽力而为的关闭，
            // 失败时由 FileRepository 的空格分隔兼容解析兼底。
            runCatching { session.execute("stty tab0 2>/dev/null", timeoutMs = 3_000) }
                .onFailure { Diagnostics.log("关闭终端 tab 展开失败，将兼容空格分隔输出：${it.message}") }
        } catch (error: Exception) {
            Diagnostics.log("握手失败：${error.javaClass.simpleName}: ${error.message}")
            runCatching { session.close() }
            throw error
        }
        session
    }

    class Session internal constructor(private val socket: Socket) : AutoCloseable {
        private val input = socket.getInputStream()
        private val output = socket.getOutputStream()
        private val commandMutex = Mutex()
        /** 整个会话共用同一解码器，保证 IAC 协商状态跨读取调用连续。 */
        private val decoder = TelnetDecoder { response -> writeRawBlocking(response) }
        /** 会话专属范围：后台收集协程在其中运行，关闭会话时一并取消。 */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var collectorJob: Job? = null
        /** 对端断开时完成，供设备页等待「连接丢失」。 */
        private val closedSignal = CompletableDeferred<Unit>()
        private val lock = Any()
        /** 已解码输出的滚动缓冲，供 [execute] 匹配结束标记（有上限）。 */
        private val commandBuffer = StringBuilder()
        /** 终端不在前台时保留的输出；回到终端页时补发，避免后台数据丢失。 */
        private val terminalBacklog = ArrayDeque<String>()
        private var backlogChars = 0
        private var terminalSink: ((String) -> Unit)? = null
        /** 受控命令执行期间暂停向终端转发，避免内部结束标记出现在终端里。 */
        @Volatile private var terminalSuppressed = false
        @Volatile private var peerClosed = false

        /**
         * 建立 shell 会话：设备若要求登录则用 [credentials] 完成认证，否则直接等待提示符。
         * 设备 telnetd 通常不主动发送 banner，需先发送一次 CRLF 唤起提示符。
         * 此阶段直接读取 socket；握手成功后才会启动后台收集协程。
         */
        internal fun handshake(credentials: Credentials?) {
            run {
                // 部分设备会把登录密码回显到终端，先登记以便日志脱敏。
                credentials?.password?.let { Diagnostics.registerSecret(it) }
                Diagnostics.log("发送 CRLF 唤起设备提示符")
                writeBlocking("\n")
                var sentUsername = false
                var sentPassword = false
                var lastSent = ""
                repeat(MAX_AUTH_STEPS) {
                    val text = readTextUntil(HANDSHAKE_TIMEOUT_MS) { candidate ->
                        val visible = stripEcho(candidate, lastSent)
                        AUTH_FAILURE.containsMatchIn(visible) ||
                            PASSWORD_PROMPT.containsMatchIn(visible) ||
                            LOGIN_PROMPT.containsMatchIn(visible) ||
                            SHELL_PROMPT.containsMatchIn(visible)
                    }
                    val visible = stripEcho(text, lastSent)
                    when {
                        AUTH_FAILURE.containsMatchIn(visible) ->
                            throw IllegalStateException("Telnet 登录失败：用户名或密码错误")
                        PASSWORD_PROMPT.containsMatchIn(visible) -> {
                            if (sentPassword) throw IllegalStateException("Telnet 登录失败：密码未被接受")
                            val auth = credentials ?: throw IllegalStateException("该设备需要登录，请输入用户名和密码")
                            // 空密码重试必然失败，直接提示用户补全，而不是让设备报 Login incorrect。
                            if (auth.password.isEmpty()) throw IllegalStateException("该设备需要登录，请输入密码")
                            Diagnostics.log("设备要求输入密码")
                            lastSent = auth.password
                            // 密码不得写入日志。
                            writeBlocking(auth.password + "\n", secret = true)
                            sentPassword = true
                        }
                        LOGIN_PROMPT.containsMatchIn(visible) -> {
                            if (sentUsername) throw IllegalStateException("Telnet 登录失败：用户名未被接受")
                            val auth = credentials ?: throw IllegalStateException("该设备需要登录，请输入用户名和密码")
                            if (auth.username.isEmpty()) throw IllegalStateException("该设备需要登录，请输入用户名")
                            Diagnostics.log("设备要求输入用户名")
                            lastSent = auth.username
                            writeBlocking(auth.username + "\n")
                            sentUsername = true
                        }
                        SHELL_PROMPT.containsMatchIn(visible) -> {
                            Diagnostics.log(
                                if (sentUsername || sentPassword) "登录成功，已进入 shell"
                                else "设备无需登录，已进入 shell"
                            )
                            // 局部返回，保证随后仍会启动后台收集协程。
                            return@run
                        }
                    }
                }
                throw IllegalStateException("无法识别设备的登录提示，请确认设备 Telnet 服务是否正常")
            }
            // 握手成功后才启动唯一读取者，此前由握手独占 socket。
            startCollector()
        }

        /** 去掉刚刚发送内容在终端上的回显，避免其中的 `#`、`$` 被误判为提示符。 */
        private fun stripEcho(text: String, sent: String): String =
            if (sent.isEmpty()) text else text.replace(sent, "")

        /** 阻塞读取并解码，直到 [predicate] 成立或超时。只能在握手阶段调用。 */
        private fun readTextUntil(timeoutMs: Long, predicate: (String) -> Boolean): String {
            val deadline = System.currentTimeMillis() + timeoutMs
            val collected = StringBuilder()
            val buffer = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                val text = try {
                    readChunk(buffer)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                } ?: throw IllegalStateException("设备关闭了 Telnet 连接")
                collected.append(text)
                if (predicate(collected.toString())) return collected.toString()
            }
            throw IllegalStateException("等待设备响应超时（${timeoutMs / 1000} 秒）")
        }

        /** 握手阶段直接读取一段数据并解码；返回 null 表示对端已关闭。 */
        private fun readChunk(buffer: ByteArray): String? {
            val count = input.read(buffer)
            if (count < 0) return null
            if (count == 0) return ""
            Diagnostics.log("收到 ${Diagnostics.describe(buffer, count)}")
            return decoder.decode(buffer, count)
        }

        /**
         * 启动会话内唯一的后台读取者。握手完成后调用，此后 socket 只由这里读取：
         * 解码结果同时供 [execute] 匹配标记、[attachTerminal] 的订阅者消费。
         */
        private fun startCollector() {
            synchronized(lock) {
                if (collectorJob != null) return
                collectorJob = scope.launch {
                    val buffer = ByteArray(8_192)
                    var loggedBytes = 0
                    var lastLogAt = 0L
                    var lastPreview = ""
                    try {
                        while (!socket.isClosed) {
                            val count = try {
                                input.read(buffer)
                            } catch (_: java.net.SocketTimeoutException) {
                                currentCoroutineContext().ensureActive()
                                continue
                            }
                            currentCoroutineContext().ensureActive()
                            if (count < 0) {
                                finishWithPeerClosed("设备关闭了 TCP 连接（读到 EOF）")
                                return@launch
                            }
                            if (count == 0) continue
                            val text = decoder.decode(buffer, count)
                            if (text.isEmpty()) continue
                            // 终端是持续流，逐包写日志会冲掉环形缓冲，这里按 1 秒聚合一次。
                            // 受控命令进行中收到的是命令回显（含 readline 重绘的孤立 CR），
                            // 记录它只会把诊断日志刷成噪声；真正的输出由 execute 自己处理。
                            if (!terminalSuppressed) {
                                loggedBytes += count
                                lastPreview = Diagnostics.describe(buffer, count, 120)
                                val now = System.currentTimeMillis()
                                if (now - lastLogAt >= 1_000) {
                                    Diagnostics.log("收到 $loggedBytes 字节，最近：$lastPreview")
                                    loggedBytes = 0
                                    lastLogAt = now
                                }
                            }
                            publish(text)
                        }
                    } catch (_: CancellationException) {
                        // 会话主动关闭，不算设备断开。
                    } catch (error: Exception) {
                        finishWithPeerClosed("读取中断：${error.javaClass.simpleName}: ${error.message}")
                    }
                }
            }
        }

        /** 把解码后的文本分发给终端订阅者，或暂存到回放缓冲。 */
        private fun publish(text: String) {
            var sink: ((String) -> Unit)? = null
            synchronized(lock) {
                // 标记匹配缓冲有上限，避免长时间空闲后无限增长。
                commandBuffer.append(text)
                if (commandBuffer.length > MAX_COMMAND_BUFFER_CHARS) {
                    commandBuffer.delete(0, commandBuffer.length - MAX_COMMAND_BUFFER_CHARS / 2)
                }
                // 受控命令（状态查询、文件操作）的输出属于内部交互，不进入终端视图。
                if (!terminalSuppressed) {
                    sink = terminalSink
                    if (sink == null) {
                        terminalBacklog.addLast(text)
                        backlogChars += text.length
                        while (backlogChars > MAX_BACKLOG_CHARS && terminalBacklog.isNotEmpty()) {
                            backlogChars -= terminalBacklog.removeFirst().length
                        }
                    }
                }
            }
            // 在锁外回调，避免订阅者阻塞收集协程时持有锁。
            sink?.invoke(text)
        }

        private fun finishWithPeerClosed(reason: String) {
            peerClosed = true
            Diagnostics.log(reason)
            closedSignal.complete(Unit)
        }

        /**
         * 统一换行为 LF。
         *
         * 孤立 CR 直接丢弃而**不**转成换行：交互式 shell 的回显会在超过屏幕宽度处插入
         * 用于重绘的孤立 CR，若当成换行会把一条命令或一行文件列表劈成两半。
         */
        private fun normalizeNewlines(text: String): String =
            text.replace("\r\n", "\n").replace("\r", "")

        /**
         * 订阅终端字节流。返回对象用于解除订阅；解除后输出重新进入回放缓冲，
         * 因此离开终端页期间的设备输出不会丢失。
         */
        fun attachTerminal(sink: (String) -> Unit): AutoCloseable {
            synchronized(lock) {
                terminalSink = sink
                if (terminalBacklog.isNotEmpty()) {
                    val history = terminalBacklog.joinToString("")
                    terminalBacklog.clear()
                    backlogChars = 0
                    sink(history)
                }
            }
            return AutoCloseable {
                synchronized(lock) { if (terminalSink === sink) terminalSink = null }
            }
        }

        /**
         * 等待设备断开。读取由后台收集协程负责，这里只等待断开信号，
         * 因此不会再与其它页面的读取相互竞争。
         */
        suspend fun monitorClosed(onClosed: () -> Unit) {
            if (!isAlive()) {
                onClosed()
                return
            }
            closedSignal.await()
            onClosed()
        }

        /**
         * 执行受控命令并返回命令输出。
         *
         * 交互式 shell 会把输入的命令回显到同一个数据流里，若把标记直接写进命令，
         * 回显中就会出现完整标记，被误判为命令结束（文件列表因此拿到空输出）。
         * 这里把起止标记都拆成两段、由设备端 printf 拼接：回显里不出现完整标记，
         * 且正文始终取「起始标记之后、结束标记之前」，与设备是否回显无关。
         */
        suspend fun execute(command: String, timeoutMs: Long = 8_000): String = commandMutex.withLock {
            withContext(Dispatchers.IO) {
                val token = java.util.UUID.randomUUID().toString().replace("-", "")
                val beginMarker = "__CL_BEGIN_${token}__"
                val endMarker = "__CL_DONE_${token}__"
                val beginCommand = "printf '\\n%s%s\\n' '__CL_BEGIN_' '${token}__'"
                val endCommand = "printf '\\n%s%s:%s\\n' '__CL_DONE_' '${token}__' \"$?\""
                val payload = buildPayload(command, beginCommand, endCommand)
                synchronized(lock) { commandBuffer.clear() }
                terminalSuppressed = true
                try {
                    write("$payload\n")
                    val deadline = System.currentTimeMillis() + timeoutMs
                    while (System.currentTimeMillis() < deadline) {
                        val current = synchronized(lock) { commandBuffer.toString() }
                        val beginIndex = current.indexOf(beginMarker)
                        val endIndex = current.indexOf(endMarker)
                        if (beginIndex >= 0 && endIndex > beginIndex) {
                            val body = normalizeNewlines(
                                current.substring(beginIndex + beginMarker.length, endIndex)
                            ).trim()
                            val trailer = current.substring(endIndex + endMarker.length)
                            val code = Regex("^:(\\d+)").find(trailer)?.groupValues?.get(1)?.toIntOrNull()
                            if (code != null && code != 0) {
                                // 设备没有输出时省略冒号，避免出现「命令失败（1）：」这种悬空文案。
                                val output = body.takeLast(240)
                                throw IllegalStateException(
                                    if (output.isEmpty()) "命令失败（退出码 $code）" else "命令失败（$code）：$output"
                                )
                            }
                            return@withContext body
                        }
                        if (!isAlive()) throw IllegalStateException("设备连接已断开")
                        kotlinx.coroutines.delay(20)
                    }
                    // 记录设备实际输出，便于判断是命令没执行还是标记被吞掉。
                    // 起始标记之前是命令回显，没有诊断价值，只保留其后的部分。
                    val raw = normalizeNewlines(
                        synchronized(lock) { commandBuffer.toString() }
                    )
                    val echoed = raw.lastIndexOf(beginMarker)
                    val captured = (if (echoed >= 0) raw.substring(echoed + beginMarker.length) else raw)
                        .take(400)
                    Diagnostics.log("命令超时，已捕获输出：${captured.ifBlank { "（空）" }}")
                    throw java.net.SocketTimeoutException("设备命令执行超时")
                } finally {
                    // 让残留的输出（如新提示符）重新进入终端流。
                    terminalSuppressed = false
                }
            }
        }

        /**
         * 生成「起始标记 + 命令 + 结束标记」的脚本。
         *
         * 单行命令整体写在一行里：shell 要等整行读完才执行，因此命令回显和
         * readline 的重绘控制序列都早于起始标记输出，命令正文保持干净。
         * 多行命令（修复包里的脚本片段）按原样分行，避免破坏其结构。
         */
        private fun buildPayload(command: String, beginCommand: String, endCommand: String): String {
            val single = command.trim()
            if (single.isEmpty()) return "$beginCommand; $endCommand"
            if (command.contains('\n')) return "$beginCommand\n$command\n$endCommand"
            val separator = if (single.endsWith("&") || single.endsWith(";")) " " else "; "
            return "$beginCommand; $single$separator$endCommand"
        }

        /** 发送数据。会切换到 IO 线程，可直接在协程中调用。[secret] 为 true 时不记录内容。 */
        suspend fun write(text: String, secret: Boolean = false) =
            withContext(Dispatchers.IO) { writeBlocking(text, secret) }

        /** 阻塞发送，只能在 IO 线程调用（获取锁后会直接写 socket）。 */
        internal fun writeBlocking(text: String, secret: Boolean = false) {
            synchronized(this) {
                Diagnostics.log(
                    if (secret) "发送：（登录凭证已隐藏，${text.length} 字符）"
                    else "发送：${text.replace("\r", "\\r").replace("\n", "\\n")}"
                )
                writeRawBlocking(TelnetEncoder.encode(text))
            }
        }

        /** 直接写原始字节（telnet 协商应答），不能经过文本编码器。 */
        private fun writeRawBlocking(bytes: ByteArray) {
            synchronized(this) {
                output.write(bytes)
                output.flush()
            }
        }

        override fun close() {
            runCatching { socket.close() }
            collectorJob?.cancel()
            scope.cancel()
        }

        fun isAlive(): Boolean = !socket.isClosed && !peerClosed

        private companion object {
            /** 标记匹配缓冲上限：超过后丢弃较旧内容，只保留尾部。 */
            const val MAX_COMMAND_BUFFER_CHARS = 262_144
            /** 终端回放缓冲上限（字符），避免离开终端页过久占用内存。 */
            const val MAX_BACKLOG_CHARS = 65_536
            /** 最多经历「用户名 → 密码 → 提示符」三轮交互。 */
            const val MAX_AUTH_STEPS = 5
            val LOGIN_PROMPT = Regex("(?im)(?:^|[\\r\\n])[^\\r\\n]{0,40}(?:login|username|user name|user)\\s*[:：]\\s*$")
            val PASSWORD_PROMPT = Regex("(?im)(?:password|passwd)\\s*[:：]\\s*$")
            // '>' 不能是箭头或比较符的一部分，banner 中常出现此类字符。
            val SHELL_PROMPT = Regex("(?m)(?:^|\\n)[^\\r\\n]{0,120}(?:[#$%]|(?<![=\\-])>)\\s*$")
            val AUTH_FAILURE = Regex(
                "(?i)incorrect|authentication failed|login failed|invalid (?:password|login|user)|" +
                    "permission denied|access denied|too many (?:login|attempts)"
            )
        }
    }
}

private class TelnetDecoder(private val respond: (ByteArray) -> Unit) {
    private enum class State { DATA, IAC, OPTION, SUBNEGOTIATION, SUBNEGOTIATION_IAC }
    private var state = State.DATA

    fun decode(bytes: ByteArray, count: Int): String {
        val output = ArrayList<Byte>(count)
        for (index in 0 until count) {
            val value = bytes[index].toInt() and 0xff
            when (state) {
                State.DATA -> when (value) {
                    IAC -> state = State.IAC
                    else -> output.add(bytes[index])
                }
                State.IAC -> when (value) {
                    IAC -> { output.add(IAC.toByte()); state = State.DATA }
                    WILL, WONT, DO, DONT -> { command = value; state = State.OPTION }
                    SB -> state = State.SUBNEGOTIATION
                    else -> state = State.DATA
                }
                State.OPTION -> {
                    val reply = when (command) {
                        WILL -> byteArrayOf(IAC.toByte(), DONT.toByte(), value.toByte())
                        DO -> byteArrayOf(IAC.toByte(), WONT.toByte(), value.toByte())
                        else -> null
                    }
                    if (reply != null) respond(reply)
                    state = State.DATA
                }
                State.SUBNEGOTIATION -> if (value == IAC) state = State.SUBNEGOTIATION_IAC
                State.SUBNEGOTIATION_IAC -> state = if (value == SE) State.DATA else State.SUBNEGOTIATION
            }
        }
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    private var command = 0

    private companion object {
        const val SE = 240
        const val SB = 250
        const val WILL = 251
        const val WONT = 252
        const val DO = 253
        const val DONT = 254
        const val IAC = 255
    }
}

private object TelnetEncoder {
    fun encode(text: String): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val output = java.io.ByteArrayOutputStream(bytes.size + 8)
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            if (value == 13) {
                output.write(13)
                if (index + 1 >= bytes.size || bytes[index + 1].toInt() != 10) output.write(0)
            } else if (value == 10) {
                output.write(13)
                output.write(10)
            } else {
                if (value == 255) output.write(255)
                output.write(value)
            }
        }
        return output.toByteArray()
    }
}
