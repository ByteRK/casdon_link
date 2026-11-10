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
import com.example.casdonlink.device.DeviceRepository
import com.example.casdonlink.profile.DeviceProfile
import com.example.casdonlink.profile.FileCheck
import com.example.casdonlink.profile.ProfileMatcher
import com.example.casdonlink.profile.ProfileProbe
import com.example.casdonlink.profile.ProfileStore
import com.example.casdonlink.ui.applyDarkSystemBars
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
    /** 当前界面绑定的会话；重启后重连会生成新对象，用它判断是否需要重新识别。 */
    private var boundSession: TelnetClient.Session? = null
    private var profiles: List<DeviceProfile> = emptyList()
    private var profileState: ProfileState = ProfileState.Detecting
    /** 完整功能模式下人工选择的机型；重连后仍保留，重新进入设备页时自动识别。 */
    private var manualProfile: DeviceProfile? = null
    private var detecting = false

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
                session = restored
                boundSession = restored
                renderConnectionState("连接已恢复")
                if (showToast) toast("连接已自动恢复")
                loadStatus(showToast = false)
                monitorConnection()
                detectProfile()
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
        if (closing) return
        // 从系统文件选择器、授权页或快捷操作页返回时，后台期间的连接可能已被中止；
        // 如果操作页已经完成重启重连，这里直接接管新会话并重新识别。
        val current = DeviceSessionHolder.session()
        if (current != null && current !== boundSession) {
            session = current
            boundSession = current
            renderConnectionState("连接已恢复")
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
        badge.text = when (state) {
            ProfileState.Detecting -> "识别中…"
            is ProfileState.Matched -> if (state.manual) "手动 · ${state.profile.name}" else state.profile.name
            ProfileState.Unknown -> "未知机型"
            is ProfileState.Failed -> "识别失败"
        }
        badge.setTextColor(ContextCompat.getColor(
            this,
            if (profile != null) R.color.secondary else R.color.text_secondary
        ))

        val showLicense = profile?.license != null
        val showQuick = profile != null &&
            profile.hasQuickActions &&
            FeatureGate.isFullModeEnabled(this)
        findViewById<View>(R.id.profileActions).isVisible = profile != null && (showLicense || showQuick)
        findViewById<Button>(R.id.licenseButton).isVisible = showLicense
        findViewById<Button>(R.id.quickActionsButton).isVisible = showQuick
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
                "未匹配到内置机型规则。\n\n可点击「重新识别」重试；完整功能模式下可手动选择机型。"
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
                detectProfile()
            }
        if (FeatureGate.isFullModeEnabled(this) && profiles.isNotEmpty()) {
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
