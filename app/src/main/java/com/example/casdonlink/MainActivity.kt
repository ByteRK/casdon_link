package com.example.casdonlink

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.casdonlink.connection.Diagnostics
import com.example.casdonlink.connection.NetworkDiagnostics
import com.example.casdonlink.connection.TelnetClient
import com.example.casdonlink.data.DeviceRecord
import com.example.casdonlink.data.DeviceStore
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val telnetClient = TelnetClient()
    private lateinit var deviceStore: DeviceStore
    private var connecting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        deviceStore = DeviceStore(this)
        renderRecentDevices()
        findViewById<Button>(R.id.connectButton).setOnClickListener {
            connect(
                findViewById<EditText>(R.id.ipInput).text.toString().trim(),
                findViewById<EditText>(R.id.portInput).text.toString().trim()
            )
        }
        // 长按连接按钮可随时查看最近一次连接诊断日志。
        findViewById<Button>(R.id.connectButton).setOnLongClickListener {
            showDiagnostics("连接诊断日志", null)
            true
        }
        findViewById<Button>(R.id.diagnoseButton).setOnClickListener {
            val host = findViewById<EditText>(R.id.ipInput).text.toString().trim()
            if (!isValidHost(host)) {
                showMessage("请先输入有效的 IP 或主机名")
                return@setOnClickListener
            }
            runNetworkDiagnostics(host, parsePort(findViewById<EditText>(R.id.portInput).text.toString().trim()))
        }
    }

    override fun onResume() {
        super.onResume()
        // 从设备页返回（主动断开或设备已断开）时刷新最近设备列表。
        renderRecentDevices()
        findViewById<Button>(R.id.connectButton).apply {
            if (!connecting) {
                isEnabled = true
                text = "连接设备"
            }
        }
    }

    private fun connect(host: String, portText: String) {
        if (!isValidHost(host)) {
            showMessage("请输入有效的 IP 或主机名")
            return
        }
        val port = parsePort(portText)
        if (port == null) {
            showMessage("端口需为 1-65535 之间的整数")
            return
        }
        val button = findViewById<Button>(R.id.connectButton)
        button.isEnabled = false
        button.text = "连接中…"
        connecting = true
        Diagnostics.clear()
        Diagnostics.log("===== 开始连接 $host:$port =====")
        Diagnostics.logNetworkContext()
        // 清理上一次可能残留的会话，避免连接重叠。
        if (DeviceSessionHolder.session() != null) DeviceSessionHolder.disconnect()
        lifecycleScope.launch {
            var session: TelnetClient.Session? = null
            try {
                session = telnetClient.connect(host, port)
                // 设备 telnetd 不主动发送 banner，需发送一次 CRLF 唤起 shell 提示符。
                // 发送 "\n" 由编码器转换成 CRLF；若写成 "\r\n" 会产生 CR CR LF（多一个回车）。
                session.write("\n")
                if (!awaitPrompt(session)) throw IllegalStateException("设备未返回 shell 提示符，请确认设备已启动且 Telnet 服务可用")
                Diagnostics.log("已收到 shell 提示符")
                // 连接确认可用后交给设备页，首页不再持有会话。
                DeviceSessionHolder.store(session, host, port)
                deviceStore.save(DeviceRecord(host, port))
                renderRecentDevices()
                startActivity(Intent(this@MainActivity, DeviceActivity::class.java))
            } catch (error: Exception) {
                Diagnostics.log("连接中止：${error.javaClass.simpleName}: ${error.message}")
                runCatching { session?.close() }
                showDiagnostics(error.message ?: "连接失败", error)
            } finally {
                connecting = false
                button.isEnabled = true
                button.text = "连接设备"
            }
        }
    }

    private fun parsePort(text: String): Int? {
        val value = text.toIntOrNull() ?: return null
        return if (value in 1..65535) value else null
    }

    /** 后台执行网络自检：网段判断 + ICMP + 常见端口探测。 */
    private fun runNetworkDiagnostics(host: String, port: Int?) {
        val targetPort = port ?: 23
        val button = findViewById<Button>(R.id.diagnoseButton)
        button.isEnabled = false
        button.text = "诊断中…"
        lifecycleScope.launch {
            val report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                NetworkDiagnostics.report(this@MainActivity, host, targetPort)
            }
            button.isEnabled = true
            button.text = "网络诊断"
            Diagnostics.log("网络自检结果：\n$report")
            showTextDialog("网络诊断", report)
        }
    }

    /** 展示本次连接诊断日志，可直接复制后分享。 */
    private fun showDiagnostics(title: String, error: Exception?) {
        val reason = error?.let { "${it.javaClass.simpleName}: ${it.message}" }
        val body = buildString {
            if (reason != null) append("$reason\n\n")
            append(Diagnostics.dump())
            val host = findViewById<EditText>(R.id.ipInput).text.toString().trim()
            if (host.isNotBlank() && Diagnostics.suggestsNetworkIssue()) {
                append("\n\n提示：TCP 连接超时通常意味着网络层不通（不同网段 / AP 隔离 / 防火墙）。\n点击「网络诊断」可查看网段判断与端口探测结果。")
            }
        }
        showTextDialog(title, body)
    }

    /** 通用文本展示弹窗，支持一键复制。 */
    private fun showTextDialog(title: String, body: String) {
        val text = TextView(this).apply {
            this.text = body
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(36, 24, 36, 24)
        }
        val scroll = ScrollView(this).apply { addView(text) }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("复制") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("CasdonLink $title", body))
                showMessage("已复制到剪贴板")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 轮询等待设备输出提示符：收到数据后再等待短暂静默，避免设备尚未就绪就发送命令。 */
    private suspend fun awaitPrompt(session: TelnetClient.Session, timeoutMs: Long = 8_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var received = false
        var idle = 0
        while (System.currentTimeMillis() < deadline) {
            val chunk = session.readAvailable()
            if (chunk.isNotEmpty()) {
                received = true
                idle = 0
            } else if (received) {
                if (++idle >= 3) return true
            }
            kotlinx.coroutines.delay(100)
        }
        return received
    }

    private fun isValidHost(host: String): Boolean = host.isNotBlank() && host.length <= 253 &&
        host.matches(Regex("[A-Za-z0-9._:-]+"))

    private fun renderRecentDevices() {
        val container = findViewById<LinearLayout>(R.id.recentDevices)
        container.removeAllViews()
        deviceStore.getAll().forEach { record ->
            val row = TextView(this).apply {
                text = "${record.ipAddress}  ·  Telnet ${record.port}"
                textSize = 14f
                setTextColor(getColor(R.color.text_primary))
                setPadding(18, 20, 18, 20)
                background = getDrawable(R.drawable.bg_card)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    findViewById<EditText>(R.id.ipInput).setText(record.ipAddress)
                    findViewById<EditText>(R.id.portInput).setText(record.port.toString())
                    connect(record.ipAddress, record.port.toString())
                }
            }
            container.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 }
            )
        }
    }

    private fun showMessage(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
