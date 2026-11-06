package com.example.casdonlink

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.casdonlink.connection.Diagnostics
import com.example.casdonlink.connection.TelnetClient
import com.example.casdonlink.device.DeviceRepository
import kotlinx.coroutines.launch

/**
 * 连接成功后的设备工作页：所有设备操作都在这里及其子页面完成，
 * 首页只负责输入地址并建立连接。设备断开时自动返回首页。
 */
class DeviceActivity : AppCompatActivity() {
    private var session: TelnetClient.Session? = null
    private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_device)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.deviceRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val active = DeviceSessionHolder.session()
        if (active == null || !active.isAlive()) {
            closing = true
            toast("设备连接已断开")
            finish()
            return
        }
        session = active
        findViewById<TextView>(R.id.deviceTitle).text = DeviceSessionHolder.host
        val account = DeviceSessionHolder.username
        findViewById<TextView>(R.id.deviceState).text = if (account.isBlank()) {
            "连接正常 · Telnet ${DeviceSessionHolder.port}"
        } else {
            "连接正常 · Telnet ${DeviceSessionHolder.port} · $account"
        }

        findViewById<Button>(R.id.refreshStatusButton).setOnClickListener { loadStatus(showToast = true) }
        findViewById<Button>(R.id.terminalButton).setOnClickListener {
            startActivity(Intent(this, TerminalActivity::class.java)
                .putExtra(TerminalActivity.EXTRA_HOST, DeviceSessionHolder.host))
        }
        findViewById<Button>(R.id.filesButton).setOnClickListener {
            startActivity(Intent(this, FilesActivity::class.java))
        }
        findViewById<Button>(R.id.importPackageButton).setOnClickListener {
            startActivity(Intent(this, PackageActivity::class.java))
        }
        findViewById<Button>(R.id.disconnectButton).setOnClickListener { disconnectAndReturn() }

        // 返回键等同于断开连接，确保回到首页时不会残留半开连接。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { disconnectAndReturn() }
        })

        loadStatus(showToast = false)
        monitorConnection()
    }

    private fun loadStatus(showToast: Boolean) {
        val active = session ?: return
        val button = findViewById<Button>(R.id.refreshStatusButton)
        button.isEnabled = false
        lifecycleScope.launch {
            try {
                val info = DeviceRepository(active).readOverview()
                findViewById<TextView>(R.id.deviceStatus).text =
                    "${info.system}\n运行时间 · ${info.uptime}\n${info.memory}"
            } catch (error: Exception) {
                Diagnostics.log("状态刷新失败：${error.javaClass.simpleName}: ${error.message}")
                if (!active.isAlive()) {
                    onDeviceLost()
                    return@launch
                }
                if (showToast) toast(error.message ?: "状态读取失败")
            } finally {
                button.isEnabled = true
            }
        }
    }

    /** 后台持续检测连接，设备断开时立即返回首页。 */
    private fun monitorConnection() {
        val active = session ?: return
        lifecycleScope.launch {
            active.monitorClosed { onDeviceLost() }
        }
    }

    private fun onDeviceLost() {
        if (closing) return
        closing = true
        DeviceSessionHolder.release()
        runOnUiThread {
            findViewById<TextView>(R.id.deviceState).text = "连接已断开"
            toast("设备连接已断开")
            finish()
        }
    }

    private fun disconnectAndReturn() {
        if (closing) return
        closing = true
        DeviceSessionHolder.disconnect()
        finish()
    }

    override fun onResume() {
        super.onResume()
        // 从子页面返回时确认连接仍然有效，否则回退首页。
        if (!closing && !DeviceSessionHolder.isActive()) onDeviceLost()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
