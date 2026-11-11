package com.example.casdonlink

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.casdonlink.connection.TelnetClient
import com.example.casdonlink.data.TerminalHistoryStore
import com.example.casdonlink.data.TerminalSettingsStore
import com.example.casdonlink.ui.applyDarkSystemBars
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    /** 输入框命令历史，仅在本地回放，不依赖设备端的 readline 状态。 */
    private val history = ArrayDeque<String>()
    /** 历史游标，等于 history.size 表示停在最新（空）行。 */
    private var historyIndex = 0
    private lateinit var historyStore: TerminalHistoryStore
    private lateinit var settingsStore: TerminalSettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDarkSystemBars()
        setContentView(R.layout.activity_terminal)
        applyKeyboardInsets()
        output = findViewById(R.id.terminalOutput)
        input = findViewById(R.id.commandInput)
        // 命令历史持久化在 SharedPreferences，重启 App 后仍可继续回放。
        historyStore = TerminalHistoryStore(this)
        settingsStore = TerminalSettingsStore(this)
        history.addAll(historyStore.load().takeLast(MAX_HISTORY))
        historyIndex = history.size
        applyFontSize(settingsStore.fontSize())
        // 旋转/重建时恢复输出与未发送的输入；输出最多保存 MAX_OUTPUT_CHARS 字符。
        savedInstanceState?.getString(KEY_OUTPUT)?.let { output.text = it }
        savedInstanceState?.getString(KEY_INPUT)?.let {
            input.setText(it)
            input.setSelection(it.length)
        }
        findViewById<TextView>(R.id.terminalTitle).text = intent.getStringExtra(EXTRA_HOST) ?: "终端"
        // 主机名/标题过长时用 marquee 横向滚动，短标题不会滚动。
        findViewById<TextView>(R.id.terminalTitle).isSelected = true
        findViewById<TextView>(R.id.connectionState).text = "已连接 · ${timeText()}"
        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }
        if (!DeviceSessionHolder.hasTarget()) {
            Toast.makeText(this, "设备连接已断开", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        session = DeviceSessionHolder.session()
        startReader()
        findViewById<Button>(R.id.sendCommandButton).setOnClickListener { sendCommand() }
        findViewById<Button>(R.id.terminalClear).setOnClickListener { clearOutput() }
        findViewById<Button>(R.id.terminalCopyAll).setOnClickListener { copyAllOutput() }
        findViewById<Button>(R.id.terminalShare).setOnClickListener { shareOutput() }
        findViewById<Button>(R.id.terminalFont).setOnClickListener { showFontSizePicker() }
        setUpControlKeys()
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                sendCommand(); true
            } else false
        }
    }

    /**
     * 控制键行。
     *
     * 控制字符（Esc、Tab、Ctrl+*）没有可见字符，软键盘打不出来，点击后直接发往设备；
     * 符号键与历史键只修改本地输入框，不打扰设备。按键在样式里设了 focusable=false，
     * 点击不会把焦点从输入框抢走，软键盘保持展开。
     */
    private fun setUpControlKeys() {
        bindSendKey(R.id.keyEsc, "\u001B")
        bindSendKey(R.id.keyTab, "\t")
        bindSendKey(R.id.keyCtrlC, "\u0003")
        bindSendKey(R.id.keyCtrlD, "\u0004")
        bindSendKey(R.id.keyCtrlZ, "\u001A")
        bindSendKey(R.id.keyCtrlL, "\u000C")
        bindInsertKey(R.id.keyPipe, "|")
        bindInsertKey(R.id.keySlash, "/")
        bindInsertKey(R.id.keyDash, "-")
        bindInsertKey(R.id.keyTilde, "~")
        findViewById<Button>(R.id.keyHistoryPrev).setOnClickListener { recallHistory(-1) }
        findViewById<Button>(R.id.keyHistoryNext).setOnClickListener { recallHistory(+1) }
    }

    /** 直接向设备发送控制字符（中断、补全、清屏等），不改动输入框内容。 */
    private fun bindSendKey(id: Int, sequence: String) {
        findViewById<Button>(id).setOnClickListener {
            lifecycleScope.launch {
                try {
                    session?.write(sequence)
                } catch (error: Exception) {
                    Toast.makeText(this@TerminalActivity, error.message ?: "发送失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 符号键：插入光标处而不是立即发送，由用户补完命令后统一提交。 */
    private fun bindInsertKey(id: Int, text: String) {
        findViewById<Button>(id).setOnClickListener { insertIntoInput(text) }
    }

    private fun insertIntoInput(text: String) {
        val editable = input.text
        // 输入框未获得焦点时选区为 -1，按追加到末尾处理。
        val start = input.selectionStart.takeIf { it >= 0 } ?: editable.length
        val end = input.selectionEnd.takeIf { it >= 0 } ?: editable.length
        val from = minOf(start, end)
        editable.replace(from, maxOf(start, end), text)
        input.setSelection(from + text.length)
    }

    /** 回放本地命令历史：[step] 为 -1 上一条、+1 下一条，走到末端回到空行。 */
    private fun recallHistory(step: Int) {
        if (history.isEmpty()) return
        val next = (historyIndex + step).coerceIn(0, history.size)
        if (next == historyIndex) return
        historyIndex = next
        val text = if (next == history.size) "" else history[next]
        input.setText(text)
        input.setSelection(text.length)
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
            scrollToBottom()
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
                findViewById<TextView>(R.id.connectionState).text = "连接已恢复 · ${timeText()}"
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
                // 首尾空白不进设备：输入法偶尔会在开头留空格，发过去会多出一个回显空格。
                val payload = command.trim()
                if (payload.isEmpty()) return@launch
                session?.write("$payload\n")
                input.text.clear()
                rememberCommand(payload)
            } catch (error: Exception) {
                Toast.makeText(this@TerminalActivity, error.message ?: "发送失败", Toast.LENGTH_SHORT).show()
            } finally {
                // 回车/发送后把焦点交还输入框，连续输入时不必重新点击。
                input.requestFocus()
            }
        }
    }

    /** 记录已发送的命令供 ↑/↓ 回放；重复的相邻命令不重复记录，并同步持久化。 */
    private fun rememberCommand(command: String) {
        if (history.lastOrNull() != command) {
            history.addLast(command)
            while (history.size > MAX_HISTORY) history.removeFirst()
            historyStore.save(history)
        }
        historyIndex = history.size
    }

    /** 清屏只清手机侧缓冲，不向设备发送 Ctrl+L，避免影响设备当前命令行状态。 */
    private fun clearOutput() {
        output.text = ""
        ansiCarry = ""
        toast("已清屏（仅本地缓冲）")
    }

    /** 复制当前全部终端输出到剪贴板。 */
    private fun copyAllOutput() {
        val text = output.text.toString()
        if (text.isBlank()) {
            toast("终端暂无输出")
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("CasdonLink 终端输出", text))
        toast("已复制全部输出（${text.length} 字符）")
    }

    /** 把当前终端输出交给系统分享面板。 */
    private fun shareOutput() {
        val text = output.text.toString()
        if (text.isBlank()) {
            toast("终端暂无输出")
            return
        }
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "CasdonLink 终端输出")
            .putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(intent, "分享终端输出"))
    }

    /** 字号设置：跟随系统使用样式默认字号，其余档位按 sp 缩放，选择持久化。 */
    private fun showFontSizePicker() {
        val options = TerminalSettingsStore.FontSize.values()
        MaterialAlertDialogBuilder(this)
            .setTitle("终端字号")
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), settingsStore.fontSize().ordinal) { dialog, which ->
                val size = options[which]
                settingsStore.setFontSize(size)
                applyFontSize(size)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyFontSize(size: TerminalSettingsStore.FontSize) {
        findViewById<Button>(R.id.terminalFont).text = "字号 · ${size.label}"
        if (size.sp != null) {
            output.setTextSize(TypedValue.COMPLEX_UNIT_SP, size.sp)
        } else {
            output.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_label))
        }
    }

    private fun timeText(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    /**
     * 滚动到最新输出。
     *
     * 不能用 `ScrollView.fullScroll(FOCUS_DOWN)`：它会向内部可聚焦的视图请求焦点
     * （输出 TextView 是 textIsSelectable，在 touch mode 下可聚焦），
     * 输入框因此失焦、软键盘收起，表现为「一弹键盘或一按回车就无法继续输入」。
     * 这里只改变滚动位置，不触碰焦点。
     */
    private fun scrollToBottom() {
        val scroll = findViewById<ScrollView>(R.id.terminalScroll)
        scroll.post {
            val content = scroll.getChildAt(0) ?: return@post
            scroll.scrollTo(0, content.bottom)
        }
    }

    private fun appendOutput(text: String) {
        val clean = sanitizeAnsi(text)
        if (clean.isEmpty()) return
        output.append(clean)
        if (output.length() > MAX_OUTPUT_CHARS) output.text = output.text.takeLast(MAX_OUTPUT_CHARS)
        scrollToBottom()
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 只保存本地输出与未发送输入；会话对象由 DeviceSessionHolder 管理，不放进 Bundle。
        if (::output.isInitialized) {
            outState.putString(KEY_OUTPUT, output.text.toString().takeLast(MAX_OUTPUT_CHARS))
        }
        if (::input.isInitialized) {
            outState.putString(KEY_INPUT, input.text.toString())
        }
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
        /** 本地命令历史最多保留的条数。 */
        const val MAX_HISTORY = 50
        /** 旋转重建时保存输出/输入的 Bundle key。 */
        private const val KEY_OUTPUT = "terminal_output"
        private const val KEY_INPUT = "terminal_input"
        private const val ESC = '\u001B'
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
