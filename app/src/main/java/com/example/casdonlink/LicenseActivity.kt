package com.example.casdonlink

import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.casdonlink.connection.Diagnostics
import com.example.casdonlink.connection.TelnetClient
import com.example.casdonlink.profile.AuthClient
import com.example.casdonlink.profile.Authorization
import com.example.casdonlink.profile.DeviceProfile
import com.example.casdonlink.profile.LicenseConfig
import com.example.casdonlink.profile.LicenseHistoryStore
import com.example.casdonlink.profile.LicenseRecord
import com.example.casdonlink.profile.LicenseStatus
import com.example.casdonlink.profile.LicenseText
import com.example.casdonlink.profile.ProfileProbe
import com.example.casdonlink.profile.ProfileStore
import com.example.casdonlink.ui.applyDarkSystemBars
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 独立的补发授权码页面：读取当前授权与 MAC，确认后向授权服务申请、写入设备、
 * 读回校验、sync 并重启，最后等待设备重新上线。所有结果写入本地授权记录，
 * 可在本页导出完整 JSON。
 */
class LicenseActivity : AppCompatActivity() {
    private lateinit var profile: DeviceProfile
    private lateinit var license: LicenseConfig
    private val history by lazy { LicenseHistoryStore(this) }
    private var mac = ""
    private var currentLicense: LicenseText.Parsed? = null
    private var currentLicenseRaw = ""
    private var busy = false
    private var running = false
    private var completed = false
    /** 服务端已分配但设备写入/重启尚未完成时保留，供「只重写」使用。 */
    private var pendingAuthorization: Authorization? = null
    private var currentRecordId: String? = null
    /** 重启前的 boot_id；重试连接时也要用它确认设备确实完成了重启。 */
    private var rebootBaselineId: String? = null

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) exportTo(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDarkSystemBars()
        setContentView(R.layout.activity_license)
        applySystemBarInsets()

        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
        val loaded = profileId?.let { ProfileStore(this).find(it) }
        val config = loaded?.license
        if (loaded == null || config == null) {
            toast("机型规则不存在")
            finish()
            return
        }
        profile = loaded
        license = config

        findViewById<TextView>(R.id.licenseTitle).apply {
            text = "补发授权码 · ${profile.name}"
            isSelected = true
        }
        renderDeviceHeader()
        renderHistorySummary()

        findViewById<Button>(R.id.licenseBack).setOnClickListener { handleBack() }
        // 当前授权区域点击可重新读取：现场常见的“第一次读失败”可以直接重试。
        findViewById<TextView>(R.id.licenseCurrent).setOnClickListener {
            if (!busy && !running) loadDeviceState()
        }
        findViewById<Button>(R.id.licenseStart).setOnClickListener { confirmStart() }
        findViewById<Button>(R.id.licenseDone).setOnClickListener { finish() }
        findViewById<Button>(R.id.licenseExport).setOnClickListener { exportRecords() }
        findViewById<Button>(R.id.licenseCheckService).setOnClickListener { checkAuthService() }
        findViewById<CheckBox>(R.id.licenseConfirm).setOnCheckedChangeListener { _, _ -> updateStartButton() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })

        loadDeviceState()
    }

    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.licenseRoot)
        val base = intArrayOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                base[0] + bars.left,
                base[1] + bars.top,
                base[2] + bars.right,
                base[3] + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun renderDeviceHeader() {
        findViewById<TextView>(R.id.licenseDevice).text = buildString {
            append("机型 · ").append(profile.name)
            append("\nPID · ").append(license.pid)
            append("\n地址 · ").append(DeviceSessionHolder.host).append(':').append(DeviceSessionHolder.port)
            append("\n网卡 · ").append(license.networkInterface)
            if (mac.isNotBlank()) append(" · ").append(mac)
            append("\n授权文件 · ").append(license.targetPath)
            append("\n规则 · ").append(profile.id)
        }
    }

    /** 读取 MAC 与当前授权文件；两者都只读，不改设备。 */
    private fun loadDeviceState() {
        setBusy(true, "正在读取设备信息…")
        lifecycleScope.launch {
            try {
                val active = DeviceSessionHolder.ensure() ?: error("设备连接已断开")
                val address = active.execute(
                    "cat /sys/class/net/${ProfileProbe.shellQuote(license.networkInterface)}/address 2>/dev/null || true",
                    timeoutMs = 8_000
                )
                mac = Regex("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}")
                    .find(address)?.value?.uppercase(Locale.US).orEmpty()
                renderDeviceHeader()
                currentLicenseRaw = active.execute(
                    "cat ${ProfileProbe.shellQuote(license.targetPath)} 2>/dev/null || true",
                    timeoutMs = 8_000,
                    // 授权文件内容属于敏感信息，超时日志里也不能出现。
                    secret = true
                ).trim()
                currentLicense = LicenseText.parse(currentLicenseRaw, license.read)
                renderCurrentLicense()
                val hint = if (mac.isBlank()) {
                    "未读取到 ${license.networkInterface} 的 MAC，无法补发"
                } else {
                    "信息读取完成，请核对后开始补发"
                }
                setBusy(false, hint)
            } catch (error: Exception) {
                Diagnostics.log("读取设备授权信息失败：${error.javaClass.simpleName}: ${error.message}")
                setBusy(false, "读取失败：${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun renderCurrentLicense() {
        val text = when {
            currentLicenseRaw.isBlank() -> "未授权（设备上不存在授权文件）"
            currentLicense != null -> buildString {
                append("已授权\n")
                append("PID · ").append(currentLicense!!.pid).append('\n')
                append("DID · ").append(currentLicense!!.did)
                if (currentLicense!!.license.isNotEmpty()) {
                    append("\nLicense · ").append(LicenseText.mask(currentLicense!!.license))
                }
            }
            else -> "已存在授权文件，但无法按规则解析：\n${currentLicenseRaw.take(500)}"
        }
        val warning = if (currentLicense != null && currentLicense!!.pid != license.pid) {
            "\n\n警告：当前授权 PID 与规则不一致，请确认设备铭牌后再覆盖。"
        } else {
            ""
        }
        findViewById<TextView>(R.id.licenseCurrent).text = text + warning
    }

    private fun confirmStart() {
        if (running || busy) return
        if (!findViewById<CheckBox>(R.id.licenseConfirm).isChecked) {
            toast("请先勾选「已核对设备铭牌与 PID 一致」")
            return
        }
        if (mac.isBlank()) {
            toast("未读取到 MAC，无法补发")
            return
        }
        val current = when {
            currentLicenseRaw.isBlank() -> "未授权"
            currentLicense != null -> "PID=${currentLicense!!.pid} · DID=${currentLicense!!.did}"
            else -> "已有授权文件但无法解析"
        }
        val message = buildString {
            append("机型：").append(profile.name).append('\n')
            append("PID：").append(license.pid).append('\n')
            append("MAC：").append(mac).append('\n')
            append("目标文件：").append(license.targetPath).append('\n')
            append("当前授权：").append(current).append('\n')
            append("设备重启：是\n\n")
            append("授权写入后设备会立即重启；若设备更换 IP，将无法自动找回。")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("确认补发授权码？")
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("开始补发") { _, _ -> startFlow(null) }
            .show()
    }

    /**
     * 完整补发流程。retry 不为空时表示服务端已分配、只重写设备文件，
     * 不再向服务端申请，避免无意义的重复请求。
     */
    private fun startFlow(retry: Authorization?) {
        if (running) return
        running = true
        completed = false
        pendingAuthorization = retry
        val record = ensureRecord()
        history.update(record.id) {
            it.copy(status = LicenseStatus.PENDING, message = if (retry == null) "开始补发" else "重试写入")
        }
        setBusy(true, if (retry == null) "正在申请授权…" else "正在重写授权文件…")

        lifecycleScope.launch {
            var authorized = retry
            try {
                val active = DeviceSessionHolder.ensure() ?: error("设备连接已断开")
                if (authorized == null) {
                    authorized = AuthClient().authorize(license.pid, mac)
                    pendingAuthorization = authorized
                    val allocated = authorized!!
                    history.update(record.id) {
                        it.copy(
                            status = LicenseStatus.AUTHORIZED,
                            did = allocated.did,
                            license = allocated.license,
                            mode = allocated.mode,
                            message = if (allocated.mode == "reused") "服务端返回已有授权" else "授权已分配"
                        )
                    }
                }
                val allocated = authorized ?: error("授权数据为空")

                setStatus("正在写入授权文件…")
                val content = LicenseText.render(license.contentTemplate, allocated.pid, allocated.did, allocated.license)
                val encoded = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                val writeCommand = "printf '%s' '$encoded' | base64 -d > ${ProfileProbe.shellQuote(license.targetPath)}"
                active.execute(writeCommand, timeoutMs = 15_000, secret = true)
                history.update(record.id) {
                    it.copy(status = LicenseStatus.WRITTEN, message = "授权文件已写入")
                }

                setStatus("正在校验写入结果…")
                val written = active.execute(
                    "cat ${ProfileProbe.shellQuote(license.targetPath)}",
                    timeoutMs = 8_000,
                    secret = true
                )
                val parsed = LicenseText.parse(written, license.read)
                    ?: error("写入后无法解析授权文件")
                if (parsed.pid != allocated.pid) error("校验失败：文件内 PID 与规则不一致")
                if (parsed.did != allocated.did) error("校验失败：文件内 DID 与服务端不一致")
                if (parsed.license.isNotEmpty() && parsed.license != allocated.license) {
                    error("校验失败：文件内 License 与服务端不一致")
                }
                history.update(record.id) {
                    it.copy(status = LicenseStatus.WRITTEN, message = "写入校验通过")
                }

                setStatus("正在同步文件系统…")
                active.execute("sync", timeoutMs = 10_000)

                setStatus("正在重启设备…")
                // 记录重启前的 boot_id，重连后必须看到不同的启动周期才算真正重启成功。
                val previousBootId = runCatching {
                    active.execute("cat /proc/sys/kernel/random/boot_id", timeoutMs = 3_000).trim()
                }.getOrNull()
                rebootBaselineId = previousBootId
                DeviceSessionHolder.beginReboot()
                active.reboot()
                setStatus("设备正在重启，等待重新上线…")
                val restored = DeviceSessionHolder.reconnectAfterReboot(90_000, previousBootId)
                if (restored == null) {
                    history.update(record.id) {
                        it.copy(status = LicenseStatus.FAILED, message = "设备未在 90 秒内重新上线，授权文件可能已写入")
                    }
                    showRebootFailure()
                } else {
                    history.update(record.id) {
                        it.copy(status = LicenseStatus.SUCCESS, message = "授权写入成功，设备已重新上线")
                    }
                    showSuccess(allocated)
                }
            } catch (error: Exception) {
                Diagnostics.log("补发授权码失败：${error.javaClass.simpleName}: ${error.message}")
                history.update(record.id) {
                    it.copy(status = LicenseStatus.FAILED, message = error.message ?: "补发失败")
                }
                showFailure(error, hasAuthorization = authorized != null)
            } finally {
                running = false
                setBusy(false)
                renderHistorySummary()
            }
        }
    }

    private fun ensureRecord(): LicenseRecord {
        currentRecordId?.let { id ->
            history.all().firstOrNull { it.id == id }?.let { return it }
        }
        val record = LicenseRecord(
            host = DeviceSessionHolder.host,
            port = DeviceSessionHolder.port,
            profileId = profile.id,
            profileName = profile.name,
            pid = license.pid,
            mac = mac,
            targetPath = license.targetPath
        )
        history.add(record)
        currentRecordId = record.id
        return record
    }

    private fun showSuccess(auth: Authorization) {
        completed = true
        setStatus("补发成功")
        findViewById<ProgressBar>(R.id.licenseProgress).isVisible = false
        findViewById<Button>(R.id.licenseStart).isVisible = false
        findViewById<Button>(R.id.licenseDone).isVisible = true
        findViewById<TextView>(R.id.licenseResult).apply {
            isVisible = true
            text = buildString {
                append("机型 · ").append(profile.name)
                append("\nPID · ").append(auth.pid)
                append("\nDID · ").append(auth.did)
                append("\nMAC · ").append(auth.mac.ifBlank { mac })
                append("\n授权码 · ").append(LicenseText.mask(auth.license))
                if (auth.mode == "reused") append("\n\n服务端返回已有授权，未重复分配。")
            }
        }
        updateStartButton()
    }

    private fun showFailure(error: Exception, hasAuthorization: Boolean) {
        setStatus("补发失败：${error.message ?: error.javaClass.simpleName}")
        findViewById<TextView>(R.id.licenseResult).apply {
            isVisible = true
            text = error.message ?: error.javaClass.simpleName
        }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle("补发失败")
            .setMessage(error.message ?: "未知错误")
            .setNegativeButton("关闭", null)
        if (hasAuthorization && pendingAuthorization != null) {
            builder.setPositiveButton("重试写入") { _, _ -> startFlow(pendingAuthorization) }
            builder.setNeutralButton("重新申请") { _, _ ->
                pendingAuthorization = null
                startFlow(null)
            }
        } else {
            builder.setPositiveButton("重试") { _, _ -> startFlow(null) }
        }
        builder.show()
    }

    private fun showRebootFailure() {
        setStatus("授权文件已写入，但设备未重新上线")
        findViewById<TextView>(R.id.licenseResult).apply {
            isVisible = true
            text = "授权文件已写入并同步，但设备在 90 秒内没有重新上线。\n可能已更换 IP，或重启尚未完成。请重试连接或返回后手动重新连接。"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("设备未重新上线")
            .setMessage("授权可能已经写入成功。可重试连接当前地址；若设备已更换 IP，请返回首页重新连接。")
            .setNegativeButton("返回设备页") { _, _ -> finish() }
            .setPositiveButton("重试连接") { _, _ -> retryReconnect() }
            .setCancelable(false)
            .show()
    }

    private fun retryReconnect() {
        setBusy(true, "等待设备重新上线…")
        lifecycleScope.launch {
            val restored = DeviceSessionHolder.reconnectAfterReboot(60_000, rebootBaselineId)
            setBusy(false)
            if (restored == null) {
                setStatus("设备仍未上线")
                showRebootFailure()
            } else {
                pendingAuthorization?.let { auth ->
                    currentRecordId?.let { id ->
                        history.update(id) { it.copy(status = LicenseStatus.SUCCESS, message = "授权写入成功，设备已重新上线") }
                    }
                    showSuccess(auth)
                } ?: run {
                    toast("设备已重新上线")
                    finish()
                }
            }
            renderHistorySummary()
        }
    }

    private fun updateStartButton() {
        val start = findViewById<Button>(R.id.licenseStart)
        start.isEnabled = !busy && !running && !completed && mac.isNotEmpty() &&
            findViewById<CheckBox>(R.id.licenseConfirm).isChecked
    }

    private fun setBusy(value: Boolean, status: String? = null) {
        busy = value
        findViewById<ProgressBar>(R.id.licenseProgress).isVisible = value
        findViewById<CheckBox>(R.id.licenseConfirm).isEnabled = !value
        findViewById<Button>(R.id.licenseExport).isEnabled = !value
        findViewById<Button>(R.id.licenseCheckService).isEnabled = !value
        findViewById<TextView>(R.id.licenseToolbarStatus).text = if (value) "执行中…" else ""
        if (status != null) setStatus(status)
        updateStartButton()
    }

    private fun setStatus(text: String) {
        findViewById<TextView>(R.id.licenseStatus).text = text
    }

    private fun renderHistorySummary() {
        val count = history.count()
        findViewById<TextView>(R.id.licenseHistorySummary).text = if (count == 0) {
            "本机尚未记录授权操作。"
        } else {
            "本机已记录 $count 条授权操作。导出文件包含完整字段与授权码，请按敏感文件保管。"
        }
    }

    /** 只检查授权平台 HTTP 可达性，不改设备、不申请授权；用于现场区分“平台不可达”和“库存不足”。 */
    private fun checkAuthService() {
        if (busy || running) return
        lifecycleScope.launch {
            setBusy(true, "正在检测授权服务…")
            try {
                val message = AuthClient().checkReachable()
                setBusy(false, message)
                toast(message)
            } catch (error: Exception) {
                val message = "授权服务不可用：${error.message ?: error.javaClass.simpleName}"
                setBusy(false, message)
                toast(message)
            }
        }
    }

    private fun exportRecords() {
        if (history.count() == 0) {
            toast("暂无授权记录可导出")
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        exportLauncher.launch("CasdonLink_license_records_$stamp.json")
    }

    private fun exportTo(uri: Uri) {
        lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { history.exportJson() }
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: error("无法写入所选文件")
                }
                toast("授权记录已导出")
            } catch (error: Exception) {
                toast("导出失败：${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun handleBack() {
        if (running) {
            toast("操作进行中，请等待完成")
            return
        }
        finish()
    }

    override fun onDestroy() {
        // 旋转/系统回收会取消 lifecycleScope 里的重启等待；必须解除标记，
        // 否则回到设备页时会一直认为“正在重启”，无法自动恢复连接。
        if (running) {
            Diagnostics.log("授权页在操作中被销毁，解除重启等待标记")
            DeviceSessionHolder.endReboot()
        }
        super.onDestroy()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
    }
}
