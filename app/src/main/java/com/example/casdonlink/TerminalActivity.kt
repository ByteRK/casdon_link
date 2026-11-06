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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

class TerminalActivity : AppCompatActivity() {
    private var session: TelnetClient.Session? = null
    private var readerStarted = false
    private lateinit var output: TextView
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)
        output = findViewById(R.id.terminalOutput)
        input = findViewById(R.id.commandInput)
        findViewById<TextView>(R.id.terminalTitle).text = intent.getStringExtra(EXTRA_HOST) ?: "终端"
        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }
        session = TerminalSessionHolder.take() ?: run {
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

    private fun startReader() {
        if (readerStarted || session == null || session?.isReading() == true) return
        readerStarted = true
        lifecycleScope.launch {
            try {
                session?.readLoop { text -> runOnUiThread { appendOutput(text) } }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                runOnUiThread {
                    findViewById<TextView>(R.id.connectionState).text = "已断开"
                    Toast.makeText(this@TerminalActivity, "终端连接已断开", Toast.LENGTH_SHORT).show()
                }
            } finally { readerStarted = false }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::output.isInitialized && !readerStarted) startReader()
    }

    private fun sendCommand() {
        val command = input.text.toString()
        if (command.isEmpty()) return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { session?.write("$command\n") }
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

    override fun onDestroy() {
        if (isFinishing) session?.close() else TerminalSessionHolder.store(session)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_HOST = "host"
        const val MAX_OUTPUT_CHARS = 100_000
    }
}

internal object TerminalSessionHolder {
    private var current: TelnetClient.Session? = null
    @Synchronized fun store(session: TelnetClient.Session?) { current = session }
    @Synchronized fun take(): TelnetClient.Session? = current.also { current = null }
}
