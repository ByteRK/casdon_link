package com.example.casdonlink

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.casdonlink.connection.TelnetClient
import com.example.casdonlink.data.DeviceRecord
import com.example.casdonlink.data.DeviceStore
import com.example.casdonlink.device.DeviceRepository
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val telnetClient = TelnetClient()
    private lateinit var deviceStore: DeviceStore
    private var activeSession: TelnetClient.Session? = null

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
            connect(findViewById<EditText>(R.id.ipInput).text.toString().trim())
        }
        findViewById<Button>(R.id.filesButton).setOnClickListener {
            Toast.makeText(this, "文件管理即将开放", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.terminalButton).setOnClickListener {
            val session = activeSession
            if (session == null) showMessage("请先连接设备") else {
                activeSession = null
                TerminalSessionHolder.store(session)
                startActivity(android.content.Intent(this, TerminalActivity::class.java)
                    .putExtra(TerminalActivity.EXTRA_HOST, findViewById<TextView>(R.id.deviceTitle).text))
            }
        }
    }

    private fun connect(host: String) {
        if (!isValidHost(host)) {
            showMessage("请输入有效的 IP 或主机名")
            return
        }
        val button = findViewById<Button>(R.id.connectButton)
        button.isEnabled = false
        button.text = "连接中…"
        lifecycleScope.launch {
            try {
                activeSession?.close()
                val session = telnetClient.connect(host)
                activeSession = session
                session.write("\r\n") // 唤起设备端 shell 提示符
                kotlinx.coroutines.delay(250)
                session.readAvailable()
                val info = DeviceRepository(session).readOverview()
                deviceStore.save(DeviceRecord(host))
                findViewById<TextView>(R.id.deviceTitle).text = host
                findViewById<TextView>(R.id.deviceStatus).text = "${info.system}\n运行时间 · ${info.uptime}\n${info.memory}"
                findViewById<View>(R.id.devicePanel).visibility = View.VISIBLE
                renderRecentDevices()
                showMessage("设备已连接")
            } catch (error: Exception) {
                activeSession?.close()
                activeSession = null
                showMessage(error.message ?: "连接失败")
            } finally {
                button.isEnabled = true
                button.text = "连接设备"
            }
        }
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
                    connect(record.ipAddress)
                }
            }
            container.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 }
            )
        }
    }

    private fun showMessage(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        activeSession?.close()
        super.onDestroy()
    }
}
