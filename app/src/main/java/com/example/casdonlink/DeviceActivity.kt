package com.example.casdonlink

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.casdonlink.connection.Diagnostics
import com.example.casdonlink.connection.TelnetClient
import com.example.casdonlink.data.FeatureGate
import com.example.casdonlink.device.DeviceRepository
import com.example.casdonlink.ui.applyDarkSystemBars
import kotlinx.coroutines.launch

/**
 * 连接成功后的设备工作页：所有设备操作都在这里及其子页面完成，
 * 首页只负责输入地址并建立连接。设备断开时自动返回首页。
 */
class DeviceActivity : AppCompatActivity() {
    private var session: TelnetClient.Session? = null
    private var closing = false
    /** 重连进行中，避免多个入口重复触发。 */
    private var recovering = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDarkSystemBars()
        setContentView(R.layout.activity_device)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.deviceRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val active = DeviceSessionHolder.session()
        if (active == null && !DeviceSessionHolder.hasTarget()) {
            closing = true
            toast("设备连接已断开")
            finish()
            return
        }
        session = active
        findViewById<TextView>(R.id.deviceTitle).text = DeviceSessionHolder.host
        renderConnectionState("连接正常")
        applyFeatureGate()

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

        if (DeviceSessionHolder.isActive()) {
            loadStatus(showToast = false)
            monitorConnection()
        } else {
            // 会话已在后台失效，先尝试恢复，不直接退回首页。
            recoverSession(showToast = false)
        }
    }

    /** 统一渲染连接状态，重连成功后复用。 */
    private fun renderConnectionState(prefix: String) {
        val account = DeviceSessionHolder.username
        findViewById<TextView>(R.id.deviceState).text = if (account.isBlank()) {
            "$prefix · Telnet ${DeviceSessionHolder.port}"
        } else {
            "$prefix · Telnet ${DeviceSessionHolder.port} · $account"
        }
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
                    recoverSession(showToast = false)
                    return@launch
                }
                if (showToast) toast(error.message ?: "状态读取失败")
            } finally {
                button.isEnabled = true
            }
        }
    }

    /** 后台持续检测连接，设备断开时先尝试重连，失败才返回首页。 */
    private fun monitorConnection() {
        val active = session ?: return
        lifecycleScope.launch {
            active.monitorClosed { recoverSession(showToast = true) }
        }
    }

    /**
     * 恢复会话：ROM 可能在应用退到后台期间中止既有连接，这里按原地址静默重连，
     * 成功后继续停留在设备页；只有重连失败才真正视为断开。
     */
    private fun recoverSession(showToast: Boolean) {
        if (closing || recovering) return
        recovering = true
        lifecycleScope.launch {
            try {
                val restored = DeviceSessionHolder.ensure()
                if (restored == null) {
                    onDeviceLost()
                    return@launch
                }
                session = restored
                renderConnectionState("连接已恢复")
                if (showToast) toast("连接已自动恢复")
                loadStatus(showToast = false)
                monitorConnection()
            } finally {
                recovering = false
            }
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
        // 从系统文件选择器等页面返回时，后台期间的连接可能已被中止，先确认并按需恢复。
        if (!closing && !DeviceSessionHolder.isActive()) recoverSession(showToast = true)
    }

    /**
     * 默认只开放「修复包」；交互终端、文件管理等高级操作需要先在首页用手势
     * 开启完整功能。受限控件统一放在 advancedActions 容器里，新增功能只需加入该容器。
     */
    private fun applyFeatureGate() {
        findViewById<View>(R.id.advancedActions).isVisible = FeatureGate.isFullModeEnabled(this)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
