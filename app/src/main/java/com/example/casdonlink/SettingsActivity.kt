package com.example.casdonlink

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.casdonlink.connection.Diagnostics
import com.example.casdonlink.data.DeviceStore
import com.example.casdonlink.data.TerminalHistoryStore
import com.example.casdonlink.data.TerminalSettingsStore
import com.example.casdonlink.files.formatByteSize
import com.example.casdonlink.packages.PackageCacheStore
import com.example.casdonlink.packages.PackageCrypto
import com.example.casdonlink.packages.PackageVerifier
import com.example.casdonlink.profile.AuthClient
import com.example.casdonlink.profile.LicenseHistoryStore
import com.example.casdonlink.profile.LicenseRecord
import com.example.casdonlink.profile.LicenseText
import com.example.casdonlink.ui.applyDarkSystemBars
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置页：集中放置全局维护项，避免首页堆叠和“藏起来”的操作。
 *
 * 这里只放跨设备的维护/诊断/关于内容；补发授权、清除设备授权、修复包执行等
 * 强上下文操作仍留在各自页面。完整功能开关继续保持标题 6 连击手势，不在此暴露解锁入口。
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var deviceStore: DeviceStore
    private lateinit var terminalSettings: TerminalSettingsStore
    private lateinit var terminalHistory: TerminalHistoryStore
    private val licenseHistory by lazy { LicenseHistoryStore(this) }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) exportLicenseRecords(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDarkSystemBars()
        setContentView(R.layout.activity_settings)
        applySystemBarInsets()

        deviceStore = DeviceStore(this)
        terminalSettings = TerminalSettingsStore(this)
        terminalHistory = TerminalHistoryStore(this)

        findViewById<Button>(R.id.settingsBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.settingsFontButton).setOnClickListener { showFontSizePicker() }
        findViewById<Button>(R.id.settingsClearHistory).setOnClickListener { confirmClearHistory() }
        findViewById<Button>(R.id.settingsClearDevices).setOnClickListener { confirmClearDevices() }
        findViewById<Button>(R.id.settingsClearPasswords).setOnClickListener { confirmClearPasswords() }
        findViewById<Button>(R.id.settingsClearCache).setOnClickListener { confirmClearCache() }
        findViewById<Button>(R.id.settingsViewLicense).setOnClickListener { showLicenseRecords() }
        findViewById<Button>(R.id.settingsExportLicense).setOnClickListener { confirmExportLicenseRecords() }
        findViewById<Button>(R.id.settingsClearLicense).setOnClickListener { confirmClearLicenseRecords() }
        findViewById<Button>(R.id.settingsViewDiagnostics).setOnClickListener { showDiagnostics() }
        findViewById<Button>(R.id.settingsClearDiagnostics).setOnClickListener { confirmClearDiagnostics() }
        findViewById<Button>(R.id.settingsCheckAuth).setOnClickListener { checkAuthService() }
        findViewById<Button>(R.id.settingsCopyVersion).setOnClickListener { copyVersionInfo() }
        findViewById<Button>(R.id.settingsPrivacy).setOnClickListener { showPrivacy() }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.settingsRoot)
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

    /** 刷新所有计数/摘要；清空或修改后调用。 */
    private fun refresh() {
        val fontSize = terminalSettings.fontSize()
        findViewById<TextView>(R.id.settingsFontValue).text = fontSize.label

        val historyCount = terminalHistory.load().size
        findViewById<TextView>(R.id.settingsHistoryValue).text =
            if (historyCount == 0) "暂无命令历史" else "$historyCount 条命令"
        findViewById<Button>(R.id.settingsClearHistory).isEnabled = historyCount > 0

        val devices = deviceStore.getAll()
        findViewById<TextView>(R.id.settingsDeviceValue).text =
            if (devices.isEmpty()) "暂无设备记录" else "${devices.size} 条记录"
        findViewById<Button>(R.id.settingsClearDevices).isEnabled = devices.isNotEmpty()

        val passwordCount = devices.count { it.hasPassword }
        findViewById<TextView>(R.id.settingsPasswordValue).text =
            if (passwordCount == 0) "没有已保存的密码" else "$passwordCount 个密码"
        findViewById<Button>(R.id.settingsClearPasswords).isEnabled = passwordCount > 0

        val cache = PackageCacheStore.info(this)
        findViewById<TextView>(R.id.settingsCacheValue).text =
            if (cache.count == 0) "暂无设备修复缓存"
            else "${cache.count} 个缓存 · ${formatByteSize(cache.bytes)}"
        findViewById<Button>(R.id.settingsClearCache).isEnabled = cache.count > 0

        val recordCount = licenseHistory.count()
        findViewById<TextView>(R.id.settingsLicenseValue).text =
            if (recordCount == 0) "暂无授权记录"
            else "$recordCount 条记录"
        findViewById<Button>(R.id.settingsViewLicense).isEnabled = recordCount > 0
        findViewById<Button>(R.id.settingsExportLicense).isEnabled = recordCount > 0
        findViewById<Button>(R.id.settingsClearLicense).isEnabled = recordCount > 0

        findViewById<TextView>(R.id.settingsAbout).text = buildString {
            append(getString(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
            append('\n').append(getString(R.string.about_build_time, BuildConfig.BUILD_TIME))
            append('\n').append(getString(R.string.about_copyright))
        }
        findViewById<TextView>(R.id.settingsPackageKey).text = packageKeyText()
    }

    private fun showFontSizePicker() {
        val options = TerminalSettingsStore.FontSize.values()
        MaterialAlertDialogBuilder(this)
            .setTitle("终端字号")
            .setSingleChoiceItems(
                options.map { it.label }.toTypedArray(),
                terminalSettings.fontSize().ordinal
            ) { dialog, which ->
                terminalSettings.setFontSize(options[which])
                dialog.dismiss()
                refresh()
                toast("字号：${options[which].label}")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmClearHistory() {
        val count = terminalHistory.load().size
        if (count == 0) return
        confirm(
            "清空命令历史？",
            "删除 $count 条命令历史，不影响设备。",
            "清空"
        ) {
            terminalHistory.save(emptyList())
            refresh()
            toast("已清空命令历史")
        }
    }

    private fun confirmClearDevices() {
        val count = deviceStore.getAll().size
        if (count == 0) return
        confirm(
            "清空设备记录？",
            "删除 $count 条记录及密码，无法恢复。",
            "清空"
        ) {
            deviceStore.clear()
            refresh()
            toast("已清空设备记录")
        }
    }

    private fun confirmClearPasswords() {
        val count = deviceStore.getAll().count { it.hasPassword }
        if (count == 0) return
        confirm(
            "清除已保存密码？",
            "清除 $count 个设备的密码，记录与别名保留。",
            "清除"
        ) {
            deviceStore.clearPasswords()
            refresh()
            toast("已清除全部保存的密码")
        }
    }

    private fun confirmClearCache() {
        val info = PackageCacheStore.info(this)
        if (info.count == 0) return
        confirm(
            "清理设备修复缓存？",
            "删除 ${info.count} 个缓存（${formatByteSize(info.bytes)}），不影响 .clpkg 文件。",
            "清理"
        ) {
            PackageCacheStore.clear(this)
            refresh()
            toast("已清理设备修复缓存")
        }
    }

    private fun showLicenseRecords() {
        val records = licenseHistory.all()
        if (records.isEmpty()) {
            toast("暂无授权记录")
            return
        }
        showTextDialog("授权记录", licenseRecordsText(records))
    }

    private fun licenseRecordsText(records: List<LicenseRecord>): String {
        val shown = records.take(MAX_RECORDS_SHOWN)
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return shown.joinToString("\n\n") { record ->
            buildString {
                append(format.format(Date(record.createdAt))).append(" · ").append(record.status.name)
                append('\n').append(record.profileName)
                append(" · ").append(record.host).append(':').append(record.port)
                append("\nPID ").append(record.pid)
                if (record.did.isNotBlank()) append(" · DID ").append(record.did)
                if (record.license.isNotEmpty()) {
                    append("\n授权码 ").append(LicenseText.mask(record.license))
                }
                if (record.message.isNotBlank()) append('\n').append(record.message)
            }
        } + if (records.size > shown.size) {
            "\n\n…共 ${records.size} 条，仅显示最近 ${shown.size} 条"
        } else {
            ""
        }
    }

    private fun confirmExportLicenseRecords() {
        if (licenseHistory.count() == 0) {
            toast("暂无记录可导出")
            return
        }
        confirm(
            "导出记录？",
            "JSON 含完整授权码，请妥善保管，勿上传公共渠道。",
            "导出"
        ) {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            exportLauncher.launch("CasdonLink_license_records_$stamp.json")
        }
    }

    private fun exportLicenseRecords(uri: Uri) {
        lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { licenseHistory.exportJson() }
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                    } ?: error("无法写入所选文件")
                }
                toast("已导出")
            } catch (error: Exception) {
                toast("导出失败：${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun confirmClearLicenseRecords() {
        val count = licenseHistory.count()
        if (count == 0) return
        confirm(
            "清空授权记录？",
            "删除 $count 条记录，无法恢复；设备授权不受影响。",
            "清空"
        ) {
            licenseHistory.clear()
            refresh()
            toast("已清空授权记录")
        }
    }

    private fun showDiagnostics() {
        showTextDialog("连接诊断日志", Diagnostics.dump())
    }

    private fun confirmClearDiagnostics() {
        if (Diagnostics.dump() == "（无日志）") {
            toast("暂无诊断日志")
            return
        }
        confirm(
            "清空诊断日志？",
            "仅清空本机诊断日志。",
            "清空"
        ) {
            Diagnostics.clear()
            toast("已清空诊断日志")
        }
    }

    private fun checkAuthService() {
        val button = findViewById<Button>(R.id.settingsCheckAuth)
        val status = findViewById<TextView>(R.id.settingsAuthStatus)
        button.isEnabled = false
        status.text = "检测中…"
        lifecycleScope.launch {
            try {
                val message = withContext(Dispatchers.IO) { AuthClient().checkReachable() }
                status.text = "$message · ${timeText()}"
            } catch (error: Exception) {
                status.text = "不可用：${error.message ?: error.javaClass.simpleName}"
            } finally {
                if (!isFinishing && !isDestroyed) button.isEnabled = true
            }
        }
    }

    private fun copyVersionInfo() {
        val key = packageKeyId() ?: "不可用"
        val text = buildString {
            append("CasdonLink ").append(BuildConfig.VERSION_NAME)
            append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            append("Built ").append(BuildConfig.BUILD_TIME).append('\n')
            append("修复包签名公钥 ").append(key)
            append(" · 容器 v").append(PackageCrypto.FORMAT_VERSION)
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("CasdonLink 版本信息", text))
        toast("已复制")
    }

    private fun showPrivacy() {
        showTextDialog(
            "隐私说明",
            """
            1. 设备登录密码使用 Android Keystore 加密后保存在本机，明文只在内存中使用，不写入日志。
            2. 授权记录中的授权码使用 Keystore 加密；导出 JSON 会还原完整授权码，请按敏感文件保管。
            3. 设备记录、授权记录和终端命令历史仅在本机保存；终端历史不保存输出。
            4. 修复包必须通过官方签名校验才会执行；未签名或被篡改的包会被拒绝。
            5. 清空设备记录、授权记录、命令历史和修复包缓存后无法恢复。
            """.trimIndent(),
            allowShare = false
        )
    }

    private fun packageKeyText(): String {
        val keyId = packageKeyId()
        return if (keyId == null) {
            "修复包签名 · 公钥不可用"
        } else {
            "修复包签名 · 公钥 $keyId · 容器 v${PackageCrypto.FORMAT_VERSION}"
        }
    }

    private fun packageKeyId(): String? =
        runCatching { PackageVerifier.fromAsset(this).keyIdHex }.getOrNull()

    private fun timeText(): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    private fun confirm(title: String, message: String, positive: String, action: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton(positive) { _, _ -> action() }
            .show()
    }

    private fun showTextDialog(title: String, body: String, allowShare: Boolean = true) {
        val view = layoutInflater.inflate(R.layout.dialog_text, null)
        view.findViewById<TextView>(R.id.dialogText).text = body
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(view)
            .setPositiveButton("复制") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("CasdonLink $title", body))
                toast("已复制到剪贴板")
            }
            .setNegativeButton("关闭", null)
        if (allowShare) {
            builder.setNeutralButton("分享") { _, _ ->
                runCatching {
                    val share = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, body)
                    startActivity(Intent.createChooser(share, "分享 $title"))
                }.onFailure { toast("无法分享：${it.message}") }
            }
        }
        builder.show()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val MAX_RECORDS_SHOWN = 20
    }
}
