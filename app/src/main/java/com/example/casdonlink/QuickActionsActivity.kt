package com.example.casdonlink

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDarkSystemBars()
        setContentView(R.layout.activity_quick_actions)
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
                    DeviceSessionHolder.beginReboot()
                    session.reboot()
                    setStatus("等待设备重新上线…")
                    val restored = DeviceSessionHolder.reconnectAfterReboot(90_000, previousBootId)
                    if (restored == null) {
                        failure = "设备未在 90 秒内重新上线，可能已更换 IP"
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
                        setStatus("执行失败")
                        showResult("执行失败", output.toString().ifBlank { reason } + "\n\n$reason")
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

    private fun showResult(title: String, content: String) {
        val view = layoutInflater.inflate(R.layout.dialog_text, null)
        view.findViewById<TextView>(R.id.dialogText).text = content.take(20_000)
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(view)
            .setNegativeButton("关闭", null)
            .setPositiveButton("复制") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("CasdonLink $title", content))
                toast("已复制到剪贴板")
            }
            .show()
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
    }
}
