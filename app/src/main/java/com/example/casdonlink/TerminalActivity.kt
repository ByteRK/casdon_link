package com.example.casdonlink

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.casdonlink.connection.TelnetClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class TerminalActivity : AppCompatActivity() {
    private var session: TelnetClient.Session? = null
    private var subscription: AutoCloseable? = null
    private var monitorJob: Job? = null
    private lateinit var output: TextView
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)
        output = findViewById(R.id.terminalOutput)
        input = findViewById(R.id.commandInput)
        findViewById<TextView>(R.id.terminalTitle).text = intent.getStringExtra(EXTRA_HOST) ?: "终端"
        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }
        session = DeviceSessionHolder.session() ?: run {
            Toast.makeText(this, "设备连接已断开", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        startReader()
        findViewById<Button>(R.id.sendCommandButton).setOnClickListener { sendCommand() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                sendCommand(); true
            } else false
        }
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
        if (::output.isInitialized) {
            startReader()
            startMonitor()
        }
    }

    /** 设备掉线时关闭终端页，回到首页（设备页也会同步退出）。 */
    private fun startMonitor() {
        if (monitorJob?.isActive == true) return
        val active = session ?: return
        monitorJob = lifecycleScope.launch {
            active.monitorClosed {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    findViewById<TextView>(R.id.connectionState).text = "已断开"
                    Toast.makeText(this@TerminalActivity, "设备连接已断开", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
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
        output.append(text.replace("\u0000", ""))
        if (output.length() > MAX_OUTPUT_CHARS) output.text = output.text.takeLast(MAX_OUTPUT_CHARS)
        findViewById<ScrollView>(R.id.terminalScroll).post {
            findViewById<ScrollView>(R.id.terminalScroll).fullScroll(ScrollView.FOCUS_DOWN)
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
    }
}
