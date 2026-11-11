package com.example.casdonlink

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.casdonlink.connection.Diagnostics
import com.example.casdonlink.connection.TelnetClient
import com.example.casdonlink.data.FeatureGate
import com.example.casdonlink.device.DeviceInfo
import com.example.casdonlink.device.DeviceRepository
import com.example.casdonlink.profile.DeviceProfile
import com.example.casdonlink.profile.FileCheck
import com.example.casdonlink.profile.LicenseText
import com.example.casdonlink.profile.ProfileMatcher
import com.example.casdonlink.profile.ProfileProbe
import com.example.casdonlink.profile.ProfileStore
import com.example.casdonlink.ui.applyDarkSystemBars
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 连接成功后的设备工作页：所有设备操作都在这里及其子页面完成，
 * 首页只负责输入地址并建立连接。设备断开时自动返回首页。
 */
class DeviceActivity : AppCompatActivity() {
    private var session: TelnetClient.Session? = null
    private var closing = false
    /** 重连进行中，避免多个入口重复触发。 */
    private var recovering = false
    /** 当前界面绑定的会话；重启后重连会生成新对象，用它判断是否需要重新识别。 */
    private var boundSession: TelnetClient.Session? = null
    private var profiles: List<DeviceProfile> = emptyList()
    private var profileState: ProfileState = ProfileState.Detecting
    /** 完整功能模式下人工选择的机型；重连后仍保留，重新进入设备页时自动识别。 */
    private var manualProfile: DeviceProfile? = null
    private var detecting = false
    /** 连接丢失后停留在本页，等用户重试或返回，不再直接退回首页。 */
    private var lost = false
    /** 设备当前授权摘要（已授权 + 脱敏授权码 / 未授权 / 无法解析）。 */
    private var licenseSummary: String? = null
    /** 正在读取授权摘要的机型，避免重复读取。 */
    private var licenseLoadingFor: String? = null

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
        boundSession = active
        profiles = ProfileStore(this).load()
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
        findViewById<Button>(R.id.licenseButton).setOnClickListener { openLicensePage() }
        findViewById<Button>(R.id.quickActionsButton).setOnClickListener { openQuickActions() }
        findViewById<TextView>(R.id.profileBadge).setOnClickListener { showProfileDetails() }
        findViewById<Button>(R.id.disconnectButton).setOnClickListener { disconnectAndReturn() }
        // 断线卡片：重试只重建会话，不离开本页；返回首页才主动断开并清空目标。
        findViewById<Button>(R.id.retryConnectionButton).setOnClickListener { retryAfterLost() }
        findViewById<Button>(R.id.returnHomeButton).setOnClickListener { returnHome() }

        // 返回键等同于断开连接，确保回到首页时不会残留半开连接。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { disconnectAndReturn() }
        })

        if (DeviceSessionHolder.isActive()) {
            loadStatus(showToast = false)
            monitorConnection()
            detectProfile()
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
        findViewById<TextView>(R.id.deviceUpdated).text = "正在刷新…"
        lifecycleScope.launch {
            try {
                val info = DeviceRepository(active).readOverview()
                renderDeviceInfo(info)
                findViewById<TextView>(R.id.deviceUpdated).text = "更新于 ${timeText()}"
            } catch (error: Exception) {
                Diagnostics.log("状态刷新失败：${error.javaClass.simpleName}: ${error.message}")
                findViewById<TextView>(R.id.deviceUpdated).text = "刷新失败 · ${timeText()}"
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

    /** 状态卡按「系统 / 运行时间 / CPU / 内存 / 存储 / 网络」逐行展示，缺项自动省略。 */
    private fun renderDeviceInfo(info: DeviceInfo) {
        val text = buildList {
            add(if (info.host.isBlank()) info.system else "${info.host} · ${info.system}")
            add("运行时间 · ${info.uptime}")
            if (info.cpu.isNotBlank() || info.load.isNotBlank()) {
                add(buildString {
                    append("CPU · ").append(info.cpu.ifBlank { "未知" })
                    if (info.load.isNotBlank()) append(" · 负载 ").append(info.load)
                })
            }
            add("内存 · ${info.memory}")
            if (info.storage.isNotBlank()) add("存储 · ${info.storage}")
            if (info.network.isNotBlank()) add("网络 · ${info.network}")
        }
        findViewById<TextView>(R.id.deviceStatus).text = text.joinToString("\n")
    }

    private fun timeText(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    /** 后台持续检测连接，设备断开时先尝试重连，失败则停留在本页展示断线卡片。 */
    private fun monitorConnection() {
        val active = session ?: return
        lifecycleScope.launch {
            active.monitorClosed { recoverSession(showToast = true) }
        }
    }

    /**
     * 恢复会话：ROM 可能在应用退到后台期间中止既有连接，这里按原地址静默重连，
     * 成功后继续停留在设备页；只有重连失败才进入「连接已断开」状态。
     */
    private fun recoverSession(showToast: Boolean) {
        if (closing || recovering) return
        // 授权/快捷操作主动重启期间，断开是预期行为，由操作页面负责等待设备重新上线。
        if (DeviceSessionHolder.isRebooting()) return
        recovering = true
        lifecycleScope.launch {
            try {
                val restored = DeviceSessionHolder.ensure()
                if (restored == null) {
                    onDeviceLost()
                    return@launch
                }
                lost = false
                session = restored
                boundSession = restored
                renderConnectionState("连接已恢复")
                showLostState(false)
                resetLicenseSummary()
                if (showToast) toast("连接已自动恢复")
                loadStatus(showToast = false)
                monitorConnection()
                detectProfile()
            } finally {
                recovering = false
            }
        }
    }

    /**
     * 设备断开：不直接退回首页，保留原地址与凭证，展示「连接已断开 + 重试/返回」卡片。
     * 目标地址不清空，用户点重试时 [DeviceSessionHolder.ensure] 才能按原地址重连。
     */
    private fun onDeviceLost() {
        if (closing || lost) return
        lost = true
        session = null
        boundSession = null
        runOnUiThread {
            findViewById<TextView>(R.id.deviceState).text = "连接已断开"
            showLostState(true)
            toast("设备连接已断开")
        }
    }

    /** 断线卡片的重试入口：直接复用原地址与凭证重建会话，不离开本页。 */
    private fun retryAfterLost() {
        if (recovering) return
        recovering = true
        val button = findViewById<Button>(R.id.retryConnectionButton)
        button.isEnabled = false
        button.text = "连接中…"
        lifecycleScope.launch {
            try {
                val restored = DeviceSessionHolder.ensure()
                if (restored == null) {
                    toast("仍无法连接设备，请检查网络后重试或返回首页")
                    return@launch
                }
                lost = false
                session = restored
                boundSession = restored
                renderConnectionState("连接已恢复")
                showLostState(false)
                resetLicenseSummary()
                loadStatus(showToast = false)
                monitorConnection()
                detectProfile()
                toast("连接已恢复")
            } finally {
                recovering = false
                button.isEnabled = true
                button.text = "重试连接"
            }
        }
    }

    /** 返回首页：主动断开并清空目标，避免首页继续指向已失效的地址。 */
    private fun returnHome() {
        if (closing) return
        closing = true
        DeviceSessionHolder.disconnect()
        finish()
    }

    /** 断线时隐藏全部设备操作，只保留重试/返回入口；恢复后按机型状态重新渲染。 */
    private fun showLostState(show: Boolean) {
        findViewById<View>(R.id.deviceLostCard).isVisible = show
        findViewById<View>(R.id.statusCard).isVisible = !show
        findViewById<View>(R.id.deviceActionsTitle).isVisible = !show
        findViewById<Button>(R.id.importPackageButton).isVisible = !show
        findViewById<Button>(R.id.disconnectButton).isVisible = !show
        findViewById<View>(R.id.advancedActions).isVisible =
            !show && FeatureGate.isFullModeEnabled(this)
        if (show) {
            findViewById<View>(R.id.profileActions).isVisible = false
            findViewById<TextView>(R.id.deviceLicense).isVisible = false
        } else {
            renderProfileState()
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
        if (closing) return
        // 从系统文件选择器、授权页或快捷操作页返回时，后台期间的连接可能已被中止；
        // 如果操作页已经完成重启重连，这里直接接管新会话并重新识别。
        val current = DeviceSessionHolder.session()
        // 只接管真正存活的新会话；会话已死时交给下面的 recoverSession 统一重连，
        // 否则断线卡片会被一个「看似新、实际已关闭」的会话覆盖。
        if (current != null && current.isAlive() && current !== boundSession) {
            lost = false
            session = current
            boundSession = current
            renderConnectionState("连接已恢复")
            showLostState(false)
            resetLicenseSummary()
            loadStatus(showToast = false)
            monitorConnection()
            detectProfile()
            return
        }
        if (!DeviceSessionHolder.isActive() && !DeviceSessionHolder.isRebooting()) {
            recoverSession(showToast = true)
        }
    }

    /**
     * 默认只开放「修复包」；交互终端、文件管理等高级操作需要先在首页用手势
     * 开启完整功能。受限控件统一放在 advancedActions 容器里，新增功能只需加入该容器。
     */
    /** 会话重建后清空旧授权摘要，重新读取当前设备的授权。 */
    private fun resetLicenseSummary() {
        licenseSummary = null
        licenseLoadingFor = null
    }

    private fun applyFeatureGate() {
        findViewById<View>(R.id.advancedActions).isVisible = FeatureGate.isFullModeEnabled(this)
        renderProfileState()
    }

    // ==================== 机型识别 ====================

    /**
     * 用一次只读探测匹配全部内置规则。识别不阻塞连接：页面先显示「识别中」，
     * 失败只影响徽标与机型按钮，不影响终端、文件、修复包等既有功能。
     */
    private fun detectProfile() {
        val manual = manualProfile
        if (manual != null) {
            profileState = ProfileState.Matched(manual, manual = true, matched = listOf(manual))
            renderProfileState()
            return
        }
        val active = session ?: return
        if (detecting) return
        // 重新识别时清空旧摘要，避免显示上一机型/上一次连接的授权状态。
        licenseSummary = null
        licenseLoadingFor = null
        if (profiles.isEmpty()) {
            profileState = ProfileState.Unknown
            renderProfileState()
            return
        }
        val plan = ProfileProbe.build(profiles)
        if (plan.checks.isEmpty()) {
            profileState = ProfileState.Unknown
            renderProfileState()
            return
        }
        detecting = true
        profileState = ProfileState.Detecting
        renderProfileState()
        lifecycleScope.launch {
            try {
                val raw = active.execute(plan.command, timeoutMs = 10_000)
                val checks = ProfileProbe.parse(raw, plan.checks)
                if (checks.isEmpty()) throw IllegalStateException("未读取到机型特征")
                // 探测输出不完整时不能按“缺失即 false”继续匹配，否则可能把设备判成错误机型。
                if (checks.size != plan.checks.size) {
                    throw IllegalStateException("机型特征读取不完整（${checks.size}/${plan.checks.size}）")
                }
                val selection = ProfileMatcher.select(profiles, checks)
                // 探测期间用户可能手动选了机型，人工选择优先，不被后台结果覆盖。
                if (manualProfile == null) {
                    profileState = selection.selected?.let {
                        ProfileState.Matched(it, manual = false, matched = selection.matched)
                    } ?: ProfileState.Unknown
                }
                Diagnostics.log(
                    "机型识别完成：${selection.selected?.name ?: "未匹配"}" +
                        if (selection.matched.size > 1) "（命中 ${selection.matched.size} 条，已按优先级选择）" else ""
                )
            } catch (error: Exception) {
                if (manualProfile == null) {
                    profileState = ProfileState.Failed(error.message ?: "机型识别失败")
                }
                Diagnostics.log("机型识别失败：${error.javaClass.simpleName}: ${error.message}")
            } finally {
                detecting = false
                renderProfileState()
            }
        }
    }

    private fun renderProfileState() {
        val state = profileState
        val profile = (state as? ProfileState.Matched)?.profile
        val badge = findViewById<TextView>(R.id.profileBadge)
        badge.isVisible = true
        val baseText = when (state) {
            ProfileState.Detecting -> "识别中…"
            is ProfileState.Matched -> if (state.manual) "手动 · ${state.profile.name}" else state.profile.name
            ProfileState.Unknown -> "未知机型"
            is ProfileState.Failed -> "识别失败"
        }
        // 徽标只放机型与授权状态；完整脱敏授权码放在下方独立一行，避免徽标过长。
        val licenseState = licenseSummary?.substringBefore(" ·")
        badge.text = if (profile != null && licenseState != null) "$baseText · $licenseState" else baseText
        badge.setTextColor(ContextCompat.getColor(
            this,
            if (profile != null) R.color.secondary else R.color.text_secondary
        ))

        val licenseLine = findViewById<TextView>(R.id.deviceLicense)
        licenseLine.isVisible = profile != null && licenseSummary != null && !lost
        licenseLine.text = licenseSummary?.let { "设备授权 · $it" }.orEmpty()

        val showLicense = profile?.license != null
        val showQuick = profile != null &&
            profile.hasQuickActions &&
            FeatureGate.isFullModeEnabled(this)
        findViewById<View>(R.id.profileActions).isVisible =
            !lost && profile != null && (showLicense || showQuick)
        findViewById<Button>(R.id.licenseButton).isVisible = showLicense
        findViewById<Button>(R.id.quickActionsButton).isVisible = showQuick

        // 命中机型且配置了授权文件时，只读一次文件生成授权摘要。
        if (!lost && profile?.license != null && licenseSummary == null && licenseLoadingFor == null) {
            loadLicenseSummary(profile)
        }
    }

    /**
     * 读取设备授权摘要：只读一次授权文件，解析出 PID/DID/授权码后只展示脱敏摘要，
     * 文件内容不写日志（secret = true）。读取失败不让设备页整体失败。
     */
    private fun loadLicenseSummary(profile: DeviceProfile) {
        val config = profile.license ?: return
        val active = session ?: return
        if (licenseLoadingFor != null) return
        licenseLoadingFor = profile.id
        lifecycleScope.launch {
            try {
                val raw = active.execute(
                    "cat ${ProfileProbe.shellQuote(config.targetPath)} 2>/dev/null || true",
                    timeoutMs = 8_000,
                    secret = true
                )
                val parsed = LicenseText.parse(raw, config.read)
                licenseSummary = when {
                    parsed == null && raw.isBlank() -> "未授权"
                    parsed == null -> "授权文件无法解析"
                    parsed.license.isEmpty() -> "已授权 · 无授权码字段"
                    else -> "已授权 · ${LicenseText.mask(parsed.license)}"
                }
            } catch (error: Exception) {
                Diagnostics.log("读取设备授权摘要失败：${error.javaClass.simpleName}: ${error.message}")
                licenseSummary = "授权状态未知"
            } finally {
                licenseLoadingFor = null
                renderProfileState()
            }
        }
    }

    /** 徽标点击：已识别看规则详情，未知/失败看原因与重试入口。 */
    private fun showProfileDetails() {
        when (val state = profileState) {
            ProfileState.Detecting -> toast("正在识别机型…")
            is ProfileState.Matched -> {
                val profile = state.profile
                val license = profile.license
                val message = buildString {
                    append("规则：").append(profile.name)
                    if (state.manual) append("（手动选择）")
                    append("\nID：").append(profile.id)
                    append("\n优先级：").append(profile.priority)
                    if (license != null) {
                        append("\nPID：").append(license.pid)
                        append("\n网卡：").append(license.networkInterface)
                        append("\n授权文件：").append(license.targetPath)
                    } else {
                        append("\n授权配置：未配置")
                    }
                    licenseSummary?.let { append("\n当前授权：").append(it) }
                    append("\n匹配条件：")
                    profile.match.all.forEach { append("\n· ").append(checkText(it)) }
                    profile.match.none.forEach { append("\n· 不存在 ").append(it.path) }
                    if (state.matched.size > 1) {
                        append("\n\n注意：同时命中 ")
                        append(state.matched.joinToString("、") { it.name })
                        append("，已按优先级选择。")
                    }
                }
                showProfileDialog("机型规则", message)
            }
            ProfileState.Unknown -> showProfileDialog(
                "未知机型",
                "未匹配到内置机型规则。\n\n可点击「重新识别」重试，或点击「手动选择」指定机型；" +
                    "手动指定后仍可正常补发授权码。"
            )
            is ProfileState.Failed -> showProfileDialog(
                "识别失败",
                "${state.message}\n\n设备连接正常时可直接重试；超时通常说明设备 shell 响应较慢。"
            )
        }
    }

    private fun checkText(check: FileCheck): String {
        val operation = when (check.operation) {
            com.example.casdonlink.profile.FileOperation.EXISTS -> "存在"
            com.example.casdonlink.profile.FileOperation.REGULAR -> "是普通文件"
            com.example.casdonlink.profile.FileOperation.EXECUTABLE -> "可执行"
        }
        return "${check.path} $operation"
    }

    private fun showProfileDialog(title: String, message: String) {
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("关闭", null)
            .setPositiveButton("重新识别") { _, _ ->
                manualProfile = null
                licenseSummary = null
                licenseLoadingFor = null
                detectProfile()
            }
        // 手动选择机型不依赖完整功能：补发授权码本身不需要完整功能，
        // 未知机型时也应允许人工指定，否则现场无法继续操作。
        if (profiles.isNotEmpty()) {
            builder.setNeutralButton("手动选择") { _, _ -> showProfilePicker() }
        }
        builder.show()
    }

    /** 完整功能模式下的兜底：识别失败或规则暂未内置时，人工指定机型。 */
    private fun showProfilePicker() {
        if (profiles.isEmpty()) {
            toast("没有可用的内置机型规则")
            return
        }
        val names = buildList {
            add("自动识别")
            profiles.forEach { add(it.name) }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("选择机型")
            .setItems(names.toTypedArray()) { _, which ->
                licenseSummary = null
                licenseLoadingFor = null
                if (which == 0) {
                    manualProfile = null
                    detectProfile()
                } else {
                    manualProfile = profiles[which - 1]
                    profileState = ProfileState.Matched(manualProfile!!, manual = true, matched = listOf(manualProfile!!))
                    renderProfileState()
                    toast("已手动选择 ${manualProfile!!.name}")
                }
            }
            .show()
    }

    private fun openLicensePage() {
        val profile = (profileState as? ProfileState.Matched)?.profile ?: return
        if (profile.license == null) return
        startActivity(
            Intent(this, LicenseActivity::class.java)
                .putExtra(LicenseActivity.EXTRA_PROFILE_ID, profile.id)
        )
    }

    private fun openQuickActions() {
        val profile = (profileState as? ProfileState.Matched)?.profile ?: return
        if (!profile.hasQuickActions) return
        startActivity(
            Intent(this, QuickActionsActivity::class.java)
                .putExtra(QuickActionsActivity.EXTRA_PROFILE_ID, profile.id)
        )
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private sealed interface ProfileState {
        data object Detecting : ProfileState
        data class Matched(
            val profile: DeviceProfile,
            val manual: Boolean,
            val matched: List<DeviceProfile>
        ) : ProfileState
        data object Unknown : ProfileState
        data class Failed(val message: String) : ProfileState
    }
}
