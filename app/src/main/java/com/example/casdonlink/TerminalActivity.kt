package com.example.casdonlink

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.casdonlink.connection.TelnetClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class TerminalActivity : AppCompatActivity() {
    private var session: TelnetClient.Session? = null
    private var subscription: AutoCloseable? = null
    private var monitorJob: Job? = null
    /** 重连进行中，避免掉线回调与前台恢复重复触发。 */
    private var recovering = false
    private lateinit var output: TextView
    private lateinit var input: EditText
    /** 上一数据块末尾未闭合的 ANSI 转义序列。 */
    private var ansiCarry = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_terminal)
        applyKeyboardInsets()
        output = findViewById(R.id.terminalOutput)
        input = findViewById(R.id.commandInput)
        findViewById<TextView>(R.id.terminalTitle).text = intent.getStringExtra(EXTRA_HOST) ?: "终端"
        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }
        if (!DeviceSessionHolder.hasTarget()) {
            Toast.makeText(this, "设备连接已断开", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        session = DeviceSessionHolder.session()
        startReader()
        findViewById<Button>(R.id.sendCommandButton).setOnClickListener { sendCommand() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                sendCommand(); true
            } else false
        }
    }

    /**
     * 适配系统栏与输入法：targetSdk 35+ 强制 edge-to-edge，系统栏和软键盘会覆盖内容，
     * 仅靠 manifest 的 adjustResize 不再生效，需要按 IME insets 给根布局追加底部留白，
     * 否则输入框会被键盘挡住，看起来像“输入了但看不到”。
     */
    private fun applyKeyboardInsets() {
        val root = findViewById<View>(R.id.terminalRoot)
        val base = intArrayOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(
                base[0] + bars.left,
                base[1] + bars.top,
                base[2] + bars.right,
                base[3] + bars.bottom
            )
            // 键盘弹出后输出区变矮，保持最新内容可见。
            view.post { findViewById<ScrollView>(R.id.terminalScroll).fullScroll(ScrollView.FOCUS_DOWN) }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    /** 订阅会话的后台字节流；离开页面时解除订阅，输出会继续进入回放缓冲。 */
    private fun startReader() {
        if (subscription != null) return
        val active = session ?: return
        subscription = active.attachTerminal { text ->
            runOnUiThread { appendOutput(text) }
        }
    }

    private fun stopReader() {
        subscription?.close()
        subscription = null
    }

    override fun onResume() {
        super.onResume()
        if (!::output.isInitialized) return
        val active = session
        if (active != null && active.isAlive()) {
            startReader()
            startMonitor()
            return
        }
        // 后台期间连接可能被 ROM 中止，回到前台先尝试恢复再继续使用终端。
        recoverSession()
    }

    /** 重连成功后重新订阅输出与掉线监听；失败则关闭页面。 */
    private fun recoverSession() {
        if (recovering) return
        recovering = true
        lifecycleScope.launch {
            try {
                val restored = DeviceSessionHolder.ensure()
                if (restored == null) {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this@TerminalActivity, "设备连接已断开", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    return@launch
                }
                stopReader()
                monitorJob?.cancel()
                session = restored
                findViewById<TextView>(R.id.connectionState).text = "连接已恢复"
                startReader()
                startMonitor()
            } finally {
                recovering = false
            }
        }
    }

    /** 设备掉线时先尝试重连，仍失败才关闭终端页并回到首页。 */
    private fun startMonitor() {
        if (monitorJob?.isActive == true) return
        val active = session ?: return
        monitorJob = lifecycleScope.launch {
            active.monitorClosed { recoverSession() }
        }
    }

    private fun sendCommand() {
        val command = input.text.toString()
        if (command.isEmpty()) return
        lifecycleScope.launch {
            try {
                session?.write("$command\n")
                input.text.clear()
            } catch (error: Exception) {
                Toast.makeText(this@TerminalActivity, error.message ?: "发送失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun appendOutput(text: String) {
        val clean = sanitizeAnsi(text)
        if (clean.isEmpty()) return
        output.append(clean)
        if (output.length() > MAX_OUTPUT_CHARS) output.text = output.text.takeLast(MAX_OUTPUT_CHARS)
        findViewById<ScrollView>(R.id.terminalScroll).post {
            findViewById<ScrollView>(R.id.terminalScroll).fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    /**
     * 去掉 ANSI 控制序列（颜色、光标控制、OSC 标题等）并统一换行。
     * TextView 无法渲染这些序列，原样显示会变成 `[1;32m` 之类的乱码。
     * 被截断在数据块边界的序列会暂存到下一块继续处理。
     */
    private fun sanitizeAnsi(input: String): String {
        if (ansiCarry.isEmpty() && input.indexOf(ESC) < 0) return input.replace("\r", "")
        val text = ansiCarry + input
        ansiCarry = ""
        val builder = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char != ESC) {
                // NUL 在 TextView 中无意义，直接丢弃。
                if (char != '\u0000') builder.append(char)
                index++
                continue
            }
            val next = text.getOrNull(index + 1) ?: break
            when (next) {
                // CSI: ESC [ 参数 终止字节
                '[' -> {
                    var cursor = index + 2
                    while (cursor < text.length && text[cursor] !in '@'..'~') cursor++
                    if (cursor >= text.length) break
                    index = cursor + 1
                }
                // OSC: ESC ] ... BEL 或 ESC \
                ']' -> {
                    var cursor = index + 2
                    var closed = false
                    while (cursor < text.length) {
                        if (text[cursor] == '\u0007') { closed = true; break }
                        if (text[cursor] == ESC && text.getOrNull(cursor + 1) == '\\') {
                            cursor++
                            closed = true
                            break
                        }
                        cursor++
                    }
                    if (!closed) break
                    index = cursor + 1
                }
                else -> index += 2
            }
        }
        if (index < text.length) ansiCarry = text.substring(index)
        return builder.toString().replace("\r", "")
    }

    override fun onPause() {
        stopReader()
        monitorJob?.cancel()
        monitorJob = null
        super.onPause()
    }

    override fun onDestroy() {
        // 会话由 DeviceSessionHolder 统一管理，离开终端页不关闭连接。
        stopReader()
        monitorJob?.cancel()
        monitorJob = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_HOST = "host"
        const val MAX_OUTPUT_CHARS = 100_000
        private const val ESC = '\u001B'
    }
}
