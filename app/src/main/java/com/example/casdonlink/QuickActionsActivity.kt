package com.example.casdonlink

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.casdonlink.connection.Diagnostics
import com.example.casdonlink.data.FeatureGate
import com.example.casdonlink.profile.DeviceProfile
import com.example.casdonlink.profile.ProfileStore
import com.example.casdonlink.profile.QuickAction
import com.example.casdonlink.ui.applyDarkSystemBars
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * 规则驱动的快捷操作页：按钮与命令队列全部来自机型规则，页面只负责执行、确认与展示输出。
 * 本页受完整功能开关限制，只有调试/授权人员可以使用。
 */
class QuickActionsActivity : AppCompatActivity() {
    private lateinit var profile: DeviceProfile
    private lateinit var adapter: ActionAdapter
    private var running = false
    private var cancelled = false
    /** 重启前 boot_id，用于「重试连接」时确认设备确实完成了重启。 */
    private var rebootBaselineId: String? = null
    /** 等待 SAF 保存的结果文本。 */
    private var pendingSaveText: String? = null
    private val saveLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val text = pendingSaveText
        pendingSaveText = null
        if (uri == null || text == null) return@registerForActivityResult
        lifecycleScope.launch {
            runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: error("无法写入所选文件")
                }
            }.onSuccess { toast("结果已保存") }
                .onFailure { toast("保存失败：${it.message ?: "未知错误"}") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDarkSystemBars()
        setContentView(R.layout.activity_quick_actions)
        // 动作可能包含重启并等待设备上线（最长 90 秒）：保持屏幕常亮并锁定竖屏，
        // 避免息屏/旋转销毁页面后流程被取消（完整方案见 FLOW-01 前台服务）。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applySystemBarInsets()

        if (!FeatureGate.isFullModeEnabled(this)) {
            toast("快捷操作需要先开启完整功能")
            finish()
            return
        }
        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
        val loaded = profileId?.let { ProfileStore(this).find(it) }
        if (loaded == null || !loaded.hasQuickActions) {
            toast("该机型没有配置快捷操作")
            finish()
            return
        }
        profile = loaded

        findViewById<TextView>(R.id.quickTitle).apply {
            text = profile.name
            isSelected = true
        }
        val pid = profile.license?.pid.orEmpty()
        findViewById<TextView>(R.id.quickSubtitle).text = buildString {
            append("地址 · ").append(DeviceSessionHolder.host)
            if (pid.isNotBlank()) append(" · PID · ").append(pid)
            append(" · ").append(profile.actionCount).append(" 项操作")
        }
        findViewById<Button>(R.id.quickBack).setOnClickListener { handleBack() }
        findViewById<Button>(R.id.quickStop).setOnClickListener { requestStop() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })

        adapter = ActionAdapter { action -> confirmAction(action) }
        findViewById<RecyclerView>(R.id.quickActionList).apply {
            layoutManager = LinearLayoutManager(this@QuickActionsActivity)
            adapter = this@QuickActionsActivity.adapter
        }
        adapter.submitList(buildRows())
        setStatus("选择一项操作")
    }

    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.quickRoot)
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

    private fun buildRows(): List<ActionRow> = buildList {
        profile.quickActions.forEach { group ->
            add(ActionRow.Header(group.title))
            group.actions.forEach { add(ActionRow.Item(it)) }
        }
    }

    private fun confirmAction(action: QuickAction) {
        if (running) {
            toast("请等待当前操作完成")
            return
        }
        if (!action.confirm && !action.danger) {
            execute(action)
            return
        }
        val message = buildString {
            if (action.description.isNotBlank()) append(action.description).append("\n\n")
            append("将执行该动作配置的命令队列")
            append(if (action.commands.size > 1) "（共 ${action.commands.size} 条）" else "")
            append("。")
            if (action.danger) append("\n\n这是危险操作，执行后可能不可恢复。")
            if (action.reboot) append("\n\n动作结束会重启设备。")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(action.label)
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("执行") { _, _ -> execute(action) }
            .show()
    }

    private fun execute(action: QuickAction) {
        if (running) return
        running = true
        cancelled = false
        setRunning(true)
        setStatus("执行中…")

        lifecycleScope.launch {
            val output = StringBuilder()
            var failure: String? = null
            var rebooted = false
            var rebootTimedOut = false
            try {
                val session = DeviceSessionHolder.ensure() ?: error("设备连接已断开")
                for ((index, command) in action.commands.withIndex()) {
                    if (cancelled) {
                        output.append("已停止，未执行后续命令。\n")
                        break
                    }
                    setStatus("执行中 · ${index + 1}/${action.commands.size}")
                    try {
                        val result = session.execute(command, timeoutMs = action.timeoutSeconds * 1000)
                        if (result.isNotBlank()) output.append(result.trimEnd()).append('\n')
                    } catch (error: Exception) {
                        failure = error.message ?: "命令执行失败"
                        output.append("命令失败：").append(failure).append('\n')
                        if (!action.continueOnError) break
                    }
                }
                if (failure == null && action.reboot && !cancelled) {
                    setStatus("设备正在重启…")
                    val previousBootId = runCatching {
                        session.execute("cat /proc/sys/kernel/random/boot_id", timeoutMs = 3_000).trim()
                    }.getOrNull()
                    rebootBaselineId = previousBootId
                    DeviceSessionHolder.beginReboot()
                    session.reboot()
                    setStatus("等待设备重新上线…")
                    val restored = DeviceSessionHolder.reconnectAfterReboot(90_000, previousBootId)
                    if (restored == null) {
                        failure = "设备未在 90 秒内重新上线，可能已更换 IP"
                        rebootTimedOut = true
                    } else {
                        rebooted = true
                        toast("设备已重新上线")
                    }
                }
            } catch (error: Exception) {
                failure = error.message ?: error.javaClass.simpleName
            } finally {
                running = false
                setRunning(false)
                val reason = failure
                val stopped = cancelled
                when {
                    reason != null -> {
                        setStatus(if (rebootTimedOut) "设备未重新上线" else "执行失败")
                        val title = if (rebootTimedOut) "设备未重新上线" else "执行失败"
                        val hint = if (rebootTimedOut) "\n\n可点击下方「重试连接」继续等待设备上线。" else ""
                        showResult(
                            title,
                            output.toString().ifBlank { reason } + "\n\n$reason$hint",
                            onRetry = if (rebootTimedOut) ({ retryReconnectAfterReboot() }) else null
                        )
                    }
                    stopped -> {
                        setStatus("已停止")
                        if (output.isNotBlank()) showResult("执行已停止", output.toString())
                        else toast("已停止")
                    }
                    rebooted -> {
                        setStatus("已重启并重新上线")
                        finish()
                    }
                    action.output || output.isNotBlank() -> {
                        setStatus("执行完成")
                        showResult("执行结果", output.toString().ifBlank { "（无输出）" })
                    }
                    else -> {
                        setStatus("执行完成")
                        toast("执行完成")
                    }
                }
            }
        }
    }

    /** 重启等待超时后的重试入口：按原地址再等 60 秒，成功直接回到设备页。 */
    private fun retryReconnectAfterReboot() {
        if (running) return
        running = true
        // 重试等待期间不显示「停止」：这里没有可中断的设备命令，停止按钮会误导。
        setRunning(false)
        findViewById<TextView>(R.id.quickToolbarStatus).text = "等待上线…"
        setStatus("等待设备重新上线…")
        lifecycleScope.launch {
            try {
                val restored = DeviceSessionHolder.reconnectAfterReboot(60_000, rebootBaselineId)
                if (restored == null) {
                    setStatus("设备仍未上线")
                    showResult(
                        "设备仍未上线",
                        "设备在 60 秒内仍未重新上线。\n请返回首页检查设备网络后再连接。",
                        onRetry = { retryReconnectAfterReboot() }
                    )
                } else {
                    setStatus("设备已重新上线")
                    toast("设备已重新上线")
                    finish()
                }
            } finally {
                running = false
                setRunning(false)
            }
        }
    }

    private fun requestStop() {
        if (!running || cancelled) return
        cancelled = true
        findViewById<Button>(R.id.quickStop).isEnabled = false
        setStatus("将在当前命令结束后停止")
    }

    private fun setRunning(value: Boolean) {
        findViewById<Button>(R.id.quickStop).isVisible = value
        findViewById<Button>(R.id.quickStop).isEnabled = !cancelled
        findViewById<TextView>(R.id.quickToolbarStatus).text = if (value) "执行中…" else ""
    }

    private fun setStatus(text: String) {
        findViewById<TextView>(R.id.quickStatus).text = text
    }

    /**
     * 结果弹窗：输出可滚动/可选中，提供复制、分享、保存；重启超时时额外显示「重试连接」。
     * 设备端 ls/echo 可能带颜色控制序列（如 ESC[0;0m），展示与导出前统一去掉。
     */
    private fun showResult(title: String, content: String, onRetry: (() -> Unit)? = null) {
        val clean = content.replace(ANSI_ESCAPE, "").take(20_000)
        val view = layoutInflater.inflate(R.layout.dialog_result, null)
        view.findViewById<TextView>(R.id.dialogResultText).text = clean
        val retryButton = view.findViewById<Button>(R.id.dialogResultRetry)
        retryButton.isVisible = onRetry != null

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(view)
            .setNegativeButton("关闭", null)
            .create()
        dialog.show()

        view.findViewById<Button>(R.id.dialogResultCopy).setOnClickListener {
            copyToClipboard("CasdonLink $title", clean)
            toast("已复制到剪贴板")
        }
        view.findViewById<Button>(R.id.dialogResultShare).setOnClickListener {
            shareText(title, clean)
        }
        view.findViewById<Button>(R.id.dialogResultSave).setOnClickListener {
            pendingSaveText = clean
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            saveLauncher.launch("CasdonLink_result_$stamp.txt")
        }
        retryButton.setOnClickListener {
            dialog.dismiss()
            onRetry?.invoke()
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun shareText(title: String, text: String) {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(intent, "分享结果"))
    }

    private fun handleBack() {
        if (running) {
            toast("操作进行中，请等待完成")
            return
        }
        finish()
    }

    override fun onDestroy() {
        if (running) {
            // 与授权页一致：页面销毁时解除“正在重启”，避免设备页永久跳过自动恢复。
            Diagnostics.log("快捷操作页在操作中被销毁，解除重启等待标记")
            DeviceSessionHolder.endReboot()
        }
        super.onDestroy()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    // ==================== 列表 ====================

    private sealed interface ActionRow {
        data class Header(val title: String) : ActionRow
        data class Item(val action: QuickAction) : ActionRow
    }

    private class ActionAdapter(
        private val onClick: (QuickAction) -> Unit
    ) : ListAdapter<ActionRow, RecyclerView.ViewHolder>(Diff) {

        override fun getItemViewType(position: Int): Int = when (getItem(position)) {
            is ActionRow.Header -> TYPE_HEADER
            is ActionRow.Item -> TYPE_ACTION
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                HeaderHolder(inflater.inflate(R.layout.row_action_header, parent, false) as TextView)
            } else {
                ActionHolder(inflater.inflate(R.layout.row_action, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = getItem(position)) {
                is ActionRow.Header -> (holder as HeaderHolder).bind(row.title)
                is ActionRow.Item -> (holder as ActionHolder).bind(row.action, onClick)
            }
        }

        private class HeaderHolder(private val view: TextView) : RecyclerView.ViewHolder(view) {
            fun bind(title: String) {
                view.text = title
            }
        }

        private class ActionHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val label = view.findViewById<TextView>(R.id.actionLabel)
            private val description = view.findViewById<TextView>(R.id.actionDescription)

            fun bind(action: QuickAction, onClick: (QuickAction) -> Unit) {
                label.text = action.label
                label.setTextColor(ContextCompat.getColor(
                    itemView.context,
                    if (action.danger) R.color.danger else R.color.text_primary
                ))
                description.isVisible = action.description.isNotBlank()
                description.text = action.description
                itemView.setOnClickListener { onClick(action) }
            }
        }

        private object Diff : DiffUtil.ItemCallback<ActionRow>() {
            override fun areItemsTheSame(oldItem: ActionRow, newItem: ActionRow): Boolean = when {
                oldItem is ActionRow.Header && newItem is ActionRow.Header -> oldItem.title == newItem.title
                oldItem is ActionRow.Item && newItem is ActionRow.Item -> oldItem.action.id == newItem.action.id
                else -> false
            }

            override fun areContentsTheSame(oldItem: ActionRow, newItem: ActionRow): Boolean =
                oldItem == newItem
        }

        private companion object {
            const val TYPE_HEADER = 0
            const val TYPE_ACTION = 1
        }
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        private val ANSI_ESCAPE = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
    }
}
