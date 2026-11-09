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
                findViewById<EditText>(R.id.portInput).text.toString().trim(),
                findViewById<EditText>(R.id.usernameInput).text.toString().trim(),
                findViewById<EditText>(R.id.passwordInput).text.toString()
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
        // 连接结束后不保留密码，避免明文留在输入框中。
        findViewById<EditText>(R.id.passwordInput).setText("")
        findViewById<Button>(R.id.connectButton).apply {
            if (!connecting) {
                isEnabled = true
                text = "连接设备"
            }
        }
    }

    private fun connect(host: String, portText: String, usernameText: String = "", passwordText: String = "") {
        if (!isValidHost(host)) {
            showMessage("请输入有效的 IP 或主机名")
            return
        }
        val port = parsePort(portText)
        if (port == null) {
            showMessage("端口需为 1-65535 之间的整数")
            return
        }
        // 用户名/密码都为空表示设备无需登录；只填密码时用户名按常见设备默认值 root 处理。
        val credentials = if (usernameText.isBlank() && passwordText.isEmpty()) null
        else TelnetClient.Credentials(usernameText.ifBlank { "root" }, passwordText)
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
                // connect 内部完成 TCP 建连、Telnet 协商与（必要时）登录，成功后已可执行 shell 命令。
                session = telnetClient.connect(host, port, credentials = credentials)
                DeviceSessionHolder.store(session, host, port, usernameText, credentials)
                deviceStore.save(DeviceRecord(host, port, usernameText))
                renderRecentDevices()
                startActivity(Intent(this@MainActivity, DeviceActivity::class.java))
            } catch (error: Exception) {
                Diagnostics.log("连接中止：${error.javaClass.simpleName}: ${error.message}")
                runCatching { session?.close() }
                if (error.message?.contains("需要登录") == true) {
                    findViewById<EditText>(R.id.usernameInput).requestFocus()
                }
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
    private fun isValidHost(host: String): Boolean = host.isNotBlank() && host.length <= 253 &&
        host.matches(Regex("[A-Za-z0-9._:-]+"))

    private fun renderRecentDevices() {
        val container = findViewById<LinearLayout>(R.id.recentDevices)
        // 注意：这里必须取到 Activity 级别的控件引用再传入 apply，
        // 因为 apply 内部的 this 是 TextView，直接调用 findViewById 只会在行视图子树里查找。
        val ipInput = findViewById<EditText>(R.id.ipInput)
        val portInput = findViewById<EditText>(R.id.portInput)
        val usernameInput = findViewById<EditText>(R.id.usernameInput)
        val passwordInput = findViewById<EditText>(R.id.passwordInput)
        container.removeAllViews()
        deviceStore.getAll().forEach { record ->
            val displayName = if (record.username.isBlank()) record.ipAddress else "${record.ipAddress} · ${record.username}"
            val row = TextView(this).apply {
                text = "$displayName  ·  Telnet ${record.port}"
                textSize = 14f
                setTextColor(getColor(R.color.text_primary))
                setPadding(18, 20, 18, 20)
                background = getDrawable(R.drawable.bg_card)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    ipInput.setText(record.ipAddress)
                    portInput.setText(record.port.toString())
                    usernameInput.setText(record.username)
                    passwordInput.setText("")
                    connect(record.ipAddress, record.port.toString(), record.username)
                }
            }
            container.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 }
            )
        }
    }

    private fun showMessage(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
