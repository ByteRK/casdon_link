package com.example.casdonlink

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.casdonlink.connection.Diagnostics
import com.example.casdonlink.connection.NetworkDiagnostics
import com.example.casdonlink.connection.TelnetClient
import com.example.casdonlink.data.DeviceRecord
import com.example.casdonlink.data.DeviceStore
import com.example.casdonlink.data.FeatureGate
import com.example.casdonlink.ui.applyDarkSystemBars
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val telnetClient = TelnetClient()
    private lateinit var deviceStore: DeviceStore
    private lateinit var recentAdapter: RecentDeviceAdapter
    private var connecting = false
    /** 标题区连击计数与上一次点击时间，用于识别隐藏的完整功能开关手势。 */
    private var titleTapCount = 0
    private var lastTitleTapAt = 0L
    /** 正在显示的开启密码弹窗；显示期间忽略手势，避免连点余波重新触发。 */
    private var fullModeDialog: AlertDialog? = null
    /** 手势触发后的冷却截止时间，用来吞掉同一串连点中多出来的点击。 */
    private var gestureCooldownUntil = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDarkSystemBars()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        deviceStore = DeviceStore(this)
        setUpTitleGesture()
        setUpRecentDevices()
        renderRecentDevices()
        renderAboutInfo()
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
        // 输入框留空时复用该地址已保存的登录信息；手动填写的值优先。
        val remembered = deviceStore.find(host, port)
        val loginName = usernameText.ifBlank { remembered?.username.orEmpty() }
        val loginPassword = passwordText.ifEmpty { remembered?.password.orEmpty() }
        // 用户名/密码都为空表示设备无需登录；只填密码时用户名按常见设备默认值 root 处理。
        val credentials = if (loginName.isBlank() && loginPassword.isEmpty()) null
        else TelnetClient.Credentials(loginName.ifBlank { "root" }, loginPassword)
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
                DeviceSessionHolder.store(session, host, port, loginName, credentials)
                // 保存实际生效的凭证（可能是从记录里复用的），否则自动登录会被清掉。
                deviceStore.save(DeviceRecord(host, port, loginName, loginPassword))
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

    /** 通用文本展示弹窗，支持一键复制。内容样式统一由 dialog_text.xml 维护。 */
    private fun showTextDialog(title: String, body: String) {
        val view = layoutInflater.inflate(R.layout.dialog_text, null)
        view.findViewById<TextView>(R.id.dialogText).text = body
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(view)
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

    private fun setUpRecentDevices() {
        val list = findViewById<RecyclerView>(R.id.recentDevices)
        list.layoutManager = LinearLayoutManager(this)
        list.itemAnimator = null
        recentAdapter = RecentDeviceAdapter(
            onClick = { record -> fillInputs(record); connect(record.ipAddress, record.port.toString(), record.username, record.password) },
            onLongClick = { record -> showRecordActions(record) }
        )
        list.adapter = recentAdapter
    }

    /** 点击最近设备时把记录回填到表单，便于在连接前修改地址或端口。 */
    private fun fillInputs(record: DeviceRecord) {
        findViewById<EditText>(R.id.ipInput).setText(record.ipAddress)
        findViewById<EditText>(R.id.portInput).setText(record.port.toString())
        findViewById<EditText>(R.id.usernameInput).setText(record.username)
        findViewById<EditText>(R.id.passwordInput).setText(record.password)
    }

    /**
     * 左上角标题区的隐藏手势：连续点击 [FeatureGate.TAP_COUNT] 下触发完整功能开关。
     * 点击间隔超过 [FeatureGate.TAP_INTERVAL_MS] 会重新计数，避免日常点击被累积误触。
     */
    private fun setUpTitleGesture() {
        findViewById<View>(R.id.mainTitle).setOnClickListener {
            // 弹窗显示中或刚触发过手势时，忽略剩余点击，不重新累计。
            if (fullModeDialog?.isShowing == true) return@setOnClickListener
            val now = SystemClock.elapsedRealtime()
            if (now < gestureCooldownUntil) {
                titleTapCount = 0
                return@setOnClickListener
            }
            if (now - lastTitleTapAt > FeatureGate.TAP_INTERVAL_MS) titleTapCount = 0
            lastTitleTapAt = now
            titleTapCount++
            if (titleTapCount < FeatureGate.TAP_COUNT) return@setOnClickListener
            titleTapCount = 0
            gestureCooldownUntil = now + GESTURE_COOLDOWN_MS
            toggleFullMode()
        }
    }

    /** 已开启时直接关闭；未开启时先要求输入密码，校验通过才写入本机开关。 */
    private fun toggleFullMode() {
        if (FeatureGate.isFullModeEnabled(this)) {
            FeatureGate.setFullModeEnabled(this, false)
            showMessage("完整功能已关闭")
            return
        }
        promptForFullMode()
    }

    private fun promptForFullMode() {
        if (fullModeDialog?.isShowing == true) return
        val view = layoutInflater.inflate(R.layout.dialog_input, null)
        val input = view.findViewById<EditText>(R.id.dialogInput)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        input.hint = "请输入开启密码"
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("开启完整功能")
            .setView(view)
            .setNegativeButton("取消", null)
            .setPositiveButton("确认", null)
            .create()
        // 连点手势的第 7 下会落在弹窗外，默认会立即取消弹窗（屏幕闪黑一次），
        // 这里禁止点击外部关闭，只能按「取消」或返回键退出。
        dialog.setCanceledOnTouchOutside(false)
        // 软键盘状态在 show 之前设定，避免弹窗已经可见后再改窗口参数造成重绘闪屏。
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.setOnShowListener {
            // 覆盖默认监听，密码错误时保留弹窗以便重新输入。
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                if (input.text.toString() == FeatureGate.UNLOCK_PASSWORD) {
                    FeatureGate.setFullModeEnabled(this, true)
                    dialog.dismiss()
                    showMessage("完整功能已开启")
                } else {
                    input.text.clear()
                    input.error = "密码错误"
                }
            }
            input.requestFocus()
        }
        dialog.setOnDismissListener { fullModeDialog = null }
        fullModeDialog = dialog
        dialog.show()
    }

    /**
     * 刷新最近设备列表。条目超过 [RECENT_VISIBLE_ROWS] 行时把列表高度固定为对应行数并在内部滚动，
     * 避免记录越攒越多把首页撑长；条目没超过时仍按内容自适应，不留下空白区域。
     */
    private fun renderRecentDevices() {
        val records = deviceStore.getAll()
        recentAdapter.submitList(records)
        findViewById<View>(R.id.recentTitle).isVisible = records.isNotEmpty()

        val list = findViewById<RecyclerView>(R.id.recentDevices)
        val params = list.layoutParams
        params.height = if (records.size > RECENT_VISIBLE_ROWS) {
            val rowHeight = resources.getDimensionPixelSize(R.dimen.recent_device_row_height)
            val rowGap = resources.getDimensionPixelSize(R.dimen.space_md)
            RECENT_VISIBLE_ROWS * (rowHeight + rowGap)
        } else {
            ViewGroup.LayoutParams.WRAP_CONTENT
        }
        list.layoutParams = params
    }

    /** 长按最近设备：清除已保存密码或删除整条记录。 */
    private fun showRecordActions(record: DeviceRecord) {
        val label = if (record.username.isBlank()) record.ipAddress else "${record.ipAddress} · ${record.username}"
        val actions = buildList {
            if (record.hasPassword) add("清除已保存密码")
            add("删除记录")
        }
        MaterialAlertDialogBuilder(this).setTitle(label).setItems(actions.toTypedArray()) { _, which ->
            when (actions[which]) {
                "清除已保存密码" -> {
                    deviceStore.save(record.copy(password = ""))
                    showMessage("已清除保存的密码")
                }
                "删除记录" -> {
                    deviceStore.remove(record.ipAddress, record.port)
                    showMessage("已删除记录")
                }
            }
            renderRecentDevices()
        }.show()
    }

    private fun showMessage(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    /** 首页底部声明：版本 / 编译时间 / 版权，与参考项目设置页的「关于」信息一致。 */
    private fun renderAboutInfo() {
        val info = getString(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE) +
            "\n" + getString(R.string.about_build_time, BuildConfig.BUILD_TIME) +
            "\n" + getString(R.string.about_copyright)
        findViewById<TextView>(R.id.aboutInfo).text = info
    }

    /**
     * 最近设备列表适配器。行外观由 row_device.xml + Widget.CasdonLink.RecentDeviceRow 维护，
     * 这里只负责把记录拼成一行文本并转发点击事件。
     */
    private class RecentDeviceAdapter(
        private val onClick: (DeviceRecord) -> Unit,
        private val onLongClick: (DeviceRecord) -> Unit
    ) : ListAdapter<DeviceRecord, RecentDeviceAdapter.Holder>(Diff) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = LayoutInflater.from(parent.context).inflate(R.layout.row_device, parent, false) as TextView
            return Holder(row)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

        inner class Holder(private val row: TextView) : RecyclerView.ViewHolder(row) {
            fun bind(record: DeviceRecord) {
                val displayName = if (record.username.isBlank()) record.ipAddress
                else "${record.ipAddress} · ${record.username}"
                val loginHint = if (record.hasPassword) "  ·  已保存密码" else ""
                row.text = "$displayName  ·  Telnet ${record.port}$loginHint"
                row.setOnClickListener { onClick(record) }
                row.setOnLongClickListener {
                    onLongClick(record)
                    true
                }
            }
        }

        private object Diff : DiffUtil.ItemCallback<DeviceRecord>() {
            override fun areItemsTheSame(oldItem: DeviceRecord, newItem: DeviceRecord): Boolean =
                oldItem.ipAddress == newItem.ipAddress && oldItem.port == newItem.port

            override fun areContentsTheSame(oldItem: DeviceRecord, newItem: DeviceRecord): Boolean =
                oldItem == newItem
        }
    }

    private companion object {
        /** 最近设备列表在不滚动时最多展示的行数。 */
        const val RECENT_VISIBLE_ROWS = 3
        /** 手势触发后的冷却时长，吞掉同一串连点中多出来的点击。 */
        const val GESTURE_COOLDOWN_MS = 800L
    }
}
