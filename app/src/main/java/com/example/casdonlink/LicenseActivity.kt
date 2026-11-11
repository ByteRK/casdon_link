package com.example.casdonlink

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.WindowManager
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
    /** 正在等待设备重启后上线；此时允许「取消等待」。 */
    private var waitingReboot = false
    /** 用户主动取消等待的标记，传给 reconnectAfterReboot 让它尽快返回。 */
    @Volatile private var cancelRebootWait = false
    /** 当前步骤条位置（-1 = 尚未开始）。 */
    private var currentStep = -1

    private val stepIds = intArrayOf(
        R.id.licenseStepApply,
        R.id.licenseStepWrite,
        R.id.licenseStepVerify,
        R.id.licenseStepSync,
        R.id.licenseStepReboot,
        R.id.licenseStepOnline,
        R.id.licenseStepRead
    )

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) exportTo(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDarkSystemBars()
        setContentView(R.layout.activity_license)
        // 补发包含写入后最长 90 秒的重启等待：保持屏幕常亮，并配合 Manifest 锁定竖屏，
        // 避免息屏/旋转销毁页面导致流程被取消（完整方案见 FLOW-01 前台服务）。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        findViewById<Button>(R.id.licenseReload).setOnClickListener {
            if (!busy && !running) loadDeviceState()
        }
        findViewById<Button>(R.id.licenseStart).setOnClickListener { confirmStart() }
        findViewById<Button>(R.id.licenseDone).setOnClickListener { finish() }
        findViewById<Button>(R.id.licenseAgain).setOnClickListener { prepareAnotherReissue() }
        findViewById<Button>(R.id.licenseRecords).setOnClickListener { showLicenseRecords() }
        findViewById<Button>(R.id.licenseReveal).setOnClickListener { revealLicense() }
        findViewById<Button>(R.id.licenseCancelWait).setOnClickListener { requestCancelWait() }
        findViewById<Button>(R.id.licenseExport).setOnClickListener { exportRecords() }
        findViewById<Button>(R.id.licenseCheckService).setOnClickListener { refreshServiceStatus(auto = false) }
        findViewById<CheckBox>(R.id.licenseConfirm).setOnCheckedChangeListener { _, _ -> updateStartButton() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })

        resetSteps()
        loadDeviceState()
        // 进入页面自动检测授权服务，结果只做提示，不阻塞设备读取与补发。
        refreshServiceStatus(auto = true)
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
                readLicenseFromDevice(active)
                val hint = when {
                    mac.isBlank() -> "未读取到 ${license.networkInterface} 的 MAC，无法补发"
                    completed -> "已从设备重新读取当前授权"
                    else -> "信息读取完成，请核对后开始补发"
                }
                setBusy(false, hint)
            } catch (error: Exception) {
                Diagnostics.log("读取设备授权信息失败：${error.javaClass.simpleName}: ${error.message}")
                findViewById<TextView>(R.id.licenseReadAt).text = "读取失败 · ${timeText()}"
                setBusy(false, "读取失败：${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    /** 读取设备上的授权文件并刷新「当前授权」；界面只以设备内容为准。 */
    private suspend fun readLicenseFromDevice(active: TelnetClient.Session) {
        currentLicenseRaw = active.execute(
            "cat ${ProfileProbe.shellQuote(license.targetPath)} 2>/dev/null || true",
            timeoutMs = 8_000,
            // 授权文件内容属于敏感信息，超时日志里也不能出现。
            secret = true
        ).trim()
        currentLicense = LicenseText.parse(currentLicenseRaw, license.read)
        findViewById<TextView>(R.id.licenseReadAt).text = "读取于 ${timeText()}"
        renderCurrentLicense()
    }

    /**
     * 设备重启并重新连接后，自动从设备读取授权，回填上方「当前授权」。
     * 成功结果不复用服务端返回的授权码；返回是否读到了可解析的授权。
     */
    private suspend fun refreshLicenseAfterReconnect(active: TelnetClient.Session): Boolean {
        return try {
            readLicenseFromDevice(active)
            if (currentLicense == null) {
                // 写后重启读不到可解析授权：明确提示，避免把“未授权/无法解析”当成补发前的旧状态。
                val current = findViewById<TextView>(R.id.licenseCurrent)
                current.text = current.text.toString() +
                    "\n\n补发返回成功，但设备上未读取到可解析的授权。\n请点击此处重新读取，或重新进入本页核对。"
                false
            } else {
                true
            }
        } catch (error: Exception) {
            Diagnostics.log("补发成功后读取设备授权失败：${error.javaClass.simpleName}: ${error.message}")
            findViewById<TextView>(R.id.licenseCurrent).text =
                "补发成功，但未能从设备读取当前授权。\n请点击此处重新读取。"
            false
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
                    append("\n授权码 · ").append(LicenseText.mask(currentLicense!!.license))
                }
            }
            // 不展示原始内容：旧格式/规则不匹配时文件里可能含完整 License，界面不应泄露。
            else -> "已存在授权文件，但无法按机型规则解析。\n请确认设备型号、PID 与授权文件格式是否匹配。"
        }
        val warning = if (currentLicense != null && currentLicense!!.pid != license.pid) {
            "\n\n警告：当前授权 PID 与规则不一致，请确认设备铭牌后再覆盖。"
        } else {
            ""
        }
        findViewById<TextView>(R.id.licenseCurrent).text = text + warning
        findViewById<Button>(R.id.licenseReveal).isVisible =
            currentLicense?.license?.isNotEmpty() == true
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
        val parsed = currentLicense
        // PID 与规则不一致时先阻止，用户确认后才能进入常规确认弹窗，避免误覆盖。
        if (parsed != null && parsed.pid != license.pid) {
            MaterialAlertDialogBuilder(this)
                .setTitle("PID 不一致")
                .setMessage(
                    "设备当前授权的 PID 为 ${parsed.pid}，与规则 PID ${license.pid} 不一致。\n\n" +
                        "继续补发会覆盖现有授权，可能影响设备使用。请先核对设备铭牌；" +
                        "若确认设备与规则匹配，可仍然继续。"
                )
                .setNegativeButton("取消", null)
                .setPositiveButton("仍然继续") { _, _ -> showStartConfirm() }
                .show()
            return
        }
        showStartConfirm()
    }

    private fun showStartConfirm() {
        if (running || busy) return
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
        cancelRebootWait = false
        setStep(if (retry == null) 0 else 1)
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
                setStep(1)

                setStatus("正在写入授权文件…")
                val content = LicenseText.render(license.contentTemplate, allocated.pid, allocated.did, allocated.license)
                val encoded = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                val writeCommand = "printf '%s' '$encoded' | base64 -d > ${ProfileProbe.shellQuote(license.targetPath)}"
                active.execute(writeCommand, timeoutMs = 15_000, secret = true)
                history.update(record.id) {
                    it.copy(status = LicenseStatus.WRITTEN, message = "授权文件已写入")
                }
                setStep(2)

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
                setStep(3)

                setStatus("正在同步文件系统…")
                active.execute("sync", timeoutMs = 10_000)

                setStatus("正在重启设备…")
                // 记录重启前的 boot_id，重连后必须看到不同的启动周期才算真正重启成功。
                val previousBootId = runCatching {
                    active.execute("cat /proc/sys/kernel/random/boot_id", timeoutMs = 3_000).trim()
                }.getOrNull()
                rebootBaselineId = previousBootId
                setStep(4)
                DeviceSessionHolder.beginReboot()
                active.reboot()
                setStep(5)
                waitingReboot = true
                cancelRebootWait = false
                findViewById<Button>(R.id.licenseCancelWait).apply {
                    isVisible = true
                    isEnabled = true
                    text = "取消等待"
                }
                setStatus("设备正在重启，等待重新上线（最长 90 秒，可取消等待）…")
                val restored = DeviceSessionHolder.reconnectAfterReboot(90_000, previousBootId) { cancelRebootWait }
                waitingReboot = false
                findViewById<Button>(R.id.licenseCancelWait).isVisible = false
                if (restored == null) {
                    if (cancelRebootWait) {
                        history.update(record.id) {
                            it.copy(status = LicenseStatus.FAILED, message = "已取消等待设备重新上线，授权文件可能已写入")
                        }
                        showWaitCancelled()
                    } else {
                        history.update(record.id) {
                            it.copy(status = LicenseStatus.FAILED, message = "设备未在 90 秒内重新上线，授权文件可能已写入")
                        }
                        showRebootFailure()
                    }
                } else {
                    history.update(record.id) {
                        it.copy(status = LicenseStatus.SUCCESS, message = "授权写入成功，设备已重新上线")
                    }
                    // 设备重新连接后自动读取设备内部的授权；成功结果不输出服务端返回的授权码。
                    setStep(6)
                    setStatus("设备已重新上线，正在读取当前授权…")
                    val readOk = refreshLicenseAfterReconnect(restored)
                    showSuccess(allocated, readOk)
                }
            } catch (error: Exception) {
                Diagnostics.log("补发授权码失败：${error.javaClass.simpleName}: ${error.message}")
                markStepFailed()
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

    private fun showSuccess(auth: Authorization, licenseRead: Boolean) {
        completed = true
        markStepsDone()
        setStatus(if (licenseRead) "补发成功" else "补发成功，但当前授权读取异常")
        findViewById<ProgressBar>(R.id.licenseProgress).isVisible = false
        findViewById<Button>(R.id.licenseStart).isVisible = false
        findViewById<Button>(R.id.licenseDone).isVisible = true
        findViewById<View>(R.id.licenseSuccessActions).isVisible = true
        findViewById<TextView>(R.id.licenseResult).apply {
            isVisible = true
            text = buildString {
                if (licenseRead) {
                    append("设备已重新上线，已从设备读取当前授权。")
                } else {
                    append("设备已重新上线，但未能从设备读取到有效授权。\n请点击上方「当前授权」重新读取。")
                }
                if (auth.mode == "reused") append("\n服务端返回已有授权，未重复分配。")
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
        // 用条目列表而不是固定三个按钮：重试写入/重新申请/复制诊断/关闭 都需要保留。
        val actions = buildList<Pair<String, (() -> Unit)?>> {
            if (hasAuthorization && pendingAuthorization != null) {
                add("重试写入" to { startFlow(pendingAuthorization) })
                add("重新申请" to {
                    pendingAuthorization = null
                    startFlow(null)
                })
            } else {
                add("重试" to { startFlow(null) })
            }
            add("复制诊断" to { copyDiagnostics(error) })
            add("关闭" to null)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("补发失败")
            .setMessage(error.message ?: "未知错误")
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second?.invoke() }
            .show()
    }

    private fun showRebootFailure() {
        markStepFailed()
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

    /** 用户在等待期间主动取消：授权文件可能已写入，明确提示并允许返回设备页。 */
    private fun showWaitCancelled() {
        setStatus("已取消等待设备重新上线")
        findViewById<TextView>(R.id.licenseResult).apply {
            isVisible = true
            text = "已停止等待设备重新上线。授权文件可能已写入成功，设备可能仍在重启。\n" +
                "可稍后返回首页重新连接核对。"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("已取消等待")
            .setMessage("授权文件可能已写入成功，但设备尚未确认上线。\n可留在本页重新读取，或返回设备页稍后重连。")
            .setNegativeButton("留在此页", null)
            .setPositiveButton("返回设备页") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun retryReconnect() {
        setBusy(true, "等待设备重新上线…")
        setStep(5)
        lifecycleScope.launch {
            val restored = DeviceSessionHolder.reconnectAfterReboot(60_000, rebootBaselineId)
            if (restored == null) {
                markStepFailed()
                setBusy(false)
                setStatus("设备仍未上线")
                showRebootFailure()
            } else {
                pendingAuthorization?.let { auth ->
                    currentRecordId?.let { id ->
                        history.update(id) { it.copy(status = LicenseStatus.SUCCESS, message = "授权写入成功，设备已重新上线") }
                    }
                    setStep(6)
                    setStatus("设备已重新上线，正在读取当前授权…")
                    val readOk = refreshLicenseAfterReconnect(restored)
                    setBusy(false)
                    showSuccess(auth, readOk)
                } ?: run {
                    setBusy(false)
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
        findViewById<Button>(R.id.licenseReload).isEnabled = !value
        findViewById<Button>(R.id.licenseReveal).isEnabled = !value
        findViewById<Button>(R.id.licenseAgain).isEnabled = !value
        findViewById<Button>(R.id.licenseRecords).isEnabled = !value
        findViewById<TextView>(R.id.licenseToolbarStatus).text = if (value) "执行中…" else ""
        if (status != null) setStatus(status)
        updateStartButton()
    }

    // ==================== 步骤条 ====================

    private fun resetSteps() {
        currentStep = -1
        renderSteps(-1, failed = false, done = false)
    }

    private fun setStep(index: Int) {
        currentStep = index
        renderSteps(index, failed = false, done = false)
    }

    private fun markStepFailed() {
        if (currentStep >= 0) renderSteps(currentStep, failed = true, done = false)
    }

    private fun markStepsDone() {
        renderSteps(stepIds.size, failed = false, done = true)
    }

    /** 已完成步骤用主文字色，当前步骤用强调色（失败为危险色），未执行步骤弱化。 */
    private fun renderSteps(index: Int, failed: Boolean, done: Boolean) {
        stepIds.forEachIndexed { position, id ->
            val view = findViewById<TextView>(id)
            val color = when {
                done -> R.color.text_primary
                failed && position == index -> R.color.danger
                position < index -> R.color.text_primary
                position == index -> R.color.secondary
                else -> R.color.text_hint
            }
            view.setTextColor(getColor(color))
            view.setTypeface(null, if (!done && position == index) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun requestCancelWait() {
        if (!waitingReboot) return
        cancelRebootWait = true
        findViewById<Button>(R.id.licenseCancelWait).apply {
            isEnabled = false
            text = "正在取消…"
        }
        setStatus("正在取消等待…设备可能仍在重启")
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

    /**
     * 检测授权平台 HTTP 可达性，不改设备、不申请授权。
     * 进入页面时自动执行一次；「重新检测」按钮可随时手动重试。
     */
    private fun refreshServiceStatus(auto: Boolean) {
        val status = findViewById<TextView>(R.id.licenseServiceStatus)
        status.setTextColor(getColor(R.color.text_secondary))
        status.text = "授权服务 · 检测中…"
        lifecycleScope.launch {
            try {
                val message = AuthClient().checkReachable()
                status.setTextColor(getColor(R.color.secondary))
                // checkReachable 已包含「授权服务可访问」，不再重复加前缀。
                status.text = "$message（${timeText()}）"
                if (!auto) toast(message)
            } catch (error: Exception) {
                val message = error.message ?: error.javaClass.simpleName
                status.setTextColor(getColor(R.color.danger))
                status.text = "授权服务 · 不可用：$message（${timeText()}）"
                if (!auto) toast("授权服务不可用：$message")
            }
        }
    }

    /** 导出前明确提示 JSON 含完整授权码，避免误把敏感文件发到公共位置。 */
    private fun exportRecords() {
        if (history.count() == 0) {
            toast("暂无授权记录可导出")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("导出授权记录？")
            .setMessage("导出的 JSON 包含完整授权码等敏感信息，请按敏感文件保管，不要上传到公共位置或聊天群。")
            .setNegativeButton("取消", null)
            .setPositiveButton("继续导出") { _, _ -> launchExport() }
            .show()
    }

    private fun launchExport() {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        exportLauncher.launch("CasdonLink_license_records_$stamp.json")
    }

    /** 成功页「再次补发」：清理本次记录状态并重新读取设备，不重复使用旧记录。 */
    private fun prepareAnotherReissue() {
        if (running || busy) return
        completed = false
        pendingAuthorization = null
        currentRecordId = null
        findViewById<TextView>(R.id.licenseResult).apply {
            isVisible = false
            text = ""
        }
        findViewById<Button>(R.id.licenseDone).isVisible = false
        findViewById<View>(R.id.licenseSuccessActions).isVisible = false
        findViewById<Button>(R.id.licenseStart).isVisible = true
        resetSteps()
        updateStartButton()
        loadDeviceState()
    }

    /**
     * 查看最近授权记录：列表只展示脱敏后的授权码与状态，完整数据仍通过导出获取。
     * 这是 LIC-01 记录中心落地前的轻量入口。
     */
    private fun showLicenseRecords() {
        val records = history.all()
        if (records.isEmpty()) {
            toast("暂无授权记录")
            return
        }
        val shown = records.take(RECORDS_DIALOG_LIMIT)
        val text = shown.joinToString("\n\n") { record ->
            buildString {
                append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(record.createdAt)))
                append(" · ").append(record.status.name)
                append("\n").append(record.profileName)
                append(" · ").append(record.host).append(':').append(record.port)
                append("\nPID ").append(record.pid)
                if (record.did.isNotBlank()) append(" · DID ").append(record.did)
                if (record.license.isNotEmpty()) append("\n授权码 ").append(LicenseText.mask(record.license))
                if (record.message.isNotBlank()) append("\n").append(record.message)
            }
        } + if (records.size > shown.size) "\n\n…（共 ${records.size} 条，仅显示最近 ${shown.size} 条）" else ""
        val view = layoutInflater.inflate(R.layout.dialog_text, null)
        view.findViewById<TextView>(R.id.dialogText).text = text
        MaterialAlertDialogBuilder(this)
            .setTitle("授权记录")
            .setView(view)
            .setNegativeButton("关闭", null)
            .setPositiveButton("复制") { _, _ ->
                copyToClipboard("CasdonLink 授权记录", text)
                toast("已复制到剪贴板")
            }
            .show()
    }

    /** 完整授权码默认脱敏；展示前二次确认，复制同样属于敏感操作。 */
    private fun revealLicense() {
        val parsed = currentLicense ?: return
        if (parsed.license.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle("显示完整授权码？")
            .setMessage("完整授权码属于敏感信息，仅用于与服务端核对。\n请勿截图、拍照或发送到公共渠道。")
            .setNegativeButton("取消", null)
            .setPositiveButton("显示") { _, _ -> showLicenseCode(parsed.license) }
            .show()
    }

    private fun showLicenseCode(code: String) {
        val view = layoutInflater.inflate(R.layout.dialog_text, null)
        view.findViewById<TextView>(R.id.dialogText).text = code
        MaterialAlertDialogBuilder(this)
            .setTitle("完整授权码")
            .setView(view)
            .setNegativeButton("关闭", null)
            .setPositiveButton("复制") { _, _ ->
                copyToClipboard("CasdonLink 授权码", code)
                toast("已复制到剪贴板")
            }
            .show()
    }

    /** 失败诊断：错误信息 + 本次连接日志，方便现场直接粘贴反馈。 */
    private fun copyDiagnostics(error: Exception? = null) {
        val body = buildString {
            if (error != null) append(error.javaClass.simpleName).append(": ").append(error.message).append("\n\n")
            append(Diagnostics.dump())
        }
        copyToClipboard("CasdonLink 授权诊断", body)
        toast("诊断信息已复制")
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun timeText(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

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
            if (waitingReboot) {
                // 等待重启期间允许停止等待并离开；设备可能仍在重启，文案要说明这一点。
                MaterialAlertDialogBuilder(this)
                    .setTitle("停止等待并返回？")
                    .setMessage("设备可能仍在重启，此时离开无法确认是否已重新上线。\n授权文件可能已经写入，可稍后重新连接核对。")
                    .setNegativeButton("继续等待", null)
                    .setPositiveButton("停止等待") { _, _ -> requestCancelWait() }
                    .show()
                return
            }
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

        /** 授权记录弹窗最多展示的条数；完整记录通过导出获取。 */
        private const val RECORDS_DIALOG_LIMIT = 30
    }
}
