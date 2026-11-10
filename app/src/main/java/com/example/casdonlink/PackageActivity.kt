package com.example.casdonlink

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.casdonlink.files.PhoneHttpServer
import com.example.casdonlink.files.LocalAddress
import com.example.casdonlink.files.FileRepository
import com.example.casdonlink.files.formatByteSize
import com.example.casdonlink.connection.TelnetClient
import com.example.casdonlink.packages.PackageImporter
import com.example.casdonlink.packages.RepairStep
import com.example.casdonlink.ui.applyDarkSystemBars
import com.example.casdonlink.ui.configureAsList
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 执行中圆点的呼吸动效周期（单程）。 */
private const val PULSE_DURATION_MS = 750L

class PackageActivity : AppCompatActivity() {
    private val picker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importPackage(uri)
    }
    private var imported: PackageImporter.ImportedPackage? = null
    private var running = false
    private val states = mutableListOf<StepState>()
    private lateinit var stepAdapter: StepAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDarkSystemBars()
        setContentView(R.layout.activity_package)
        // 一次修复可能持续数分钟，保持屏幕常亮，避免息屏后 Telnet 会话被系统回收。
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applySystemBarInsets()
        setUpStepList()
        findViewById<Button>(R.id.packageBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.runPackage).setOnClickListener { confirmRun() }
        picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
    }

    /**
     * targetSdk 35+ 强制 edge-to-edge，内容会画到状态栏/导航栏下面。
     * 这里把系统栏高度叠加到根布局原有 padding 上，避免标题栏贴顶、底部按钮贴边。
     */
    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.packageRoot)
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

    private fun setUpStepList() {
        val list = findViewById<RecyclerView>(R.id.stepList)
        list.configureAsList()
        stepAdapter = StepAdapter()
        list.adapter = stepAdapter
    }

    private fun importPackage(uri: android.net.Uri) {
        lifecycleScope.launch {
            try {
                val result = PackageImporter(this@PackageActivity).import(uri) { contentResolver.openInputStream(it) }
                imported = result
                states.clear(); states.addAll(result.manifest.steps.map { StepState(StepStatus.PENDING) })
                findViewById<TextView>(R.id.packageTitle).text = "${result.manifest.name} · ${result.manifest.version}"
                // 标题可能超出工具栏宽度，选中后由 marquee 横向滚动显示完整内容。
                findViewById<TextView>(R.id.packageTitle).isSelected = true
                findViewById<TextView>(R.id.packageDescription).text = "${result.manifest.description}\n共 ${result.manifest.steps.size} 个步骤"
                renderSteps()
            } catch (error: Exception) { toast(error.message ?: "导入失败"); finish() }
        }
    }

    /** 状态变化时整份重新提交，DiffUtil 会把更新定位到具体行，不重建整个列表。 */
    private fun renderSteps() {
        val result = imported ?: return
        stepAdapter.submitList(
            result.manifest.steps.mapIndexed { index, step ->
                StepRow(index, step, states.getOrElse(index) { StepState(StepStatus.PENDING) })
            }
        )
        findViewById<TextView>(R.id.packageProgress).text = "${result.manifest.steps.size} 步"
    }

    /** 步骤状态：圆点资源与读屏文案随状态变化，列表不展示步骤内容。 */
    private enum class StepStatus(val dotRes: Int, val label: String) {
        PENDING(R.drawable.dot_step_pending, "待执行"),
        RUNNING(R.drawable.dot_step_running, "执行中"),
        SUCCESS(R.drawable.dot_step_success, "成功"),
        FAILED(R.drawable.dot_step_failed, "失败")
    }

    /** message 只在失败时携带原因；meta 展示传输进度等中性信息，两者互斥出现。 */
    private data class StepState(
        val status: StepStatus,
        val message: String? = null,
        val meta: String? = null
    )

    /** 修复步骤行：序号、标题与状态圆点，不携带命令与传输路径。 */
    private data class StepRow(val index: Int, val step: RepairStep, val state: StepState)

    private class StepAdapter : ListAdapter<StepRow, StepAdapter.Holder>(StepDiff) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.row_step, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

        override fun onViewRecycled(holder: Holder) {
            holder.stopPulse()
            super.onViewRecycled(holder)
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val dot = view.findViewById<View>(R.id.stepDot)
            private val name = view.findViewById<TextView>(R.id.stepName)
            private val meta = view.findViewById<TextView>(R.id.stepMeta)
            private val detail = view.findViewById<TextView>(R.id.stepDetail)
            private val dotRadius = view.resources.getDimension(R.dimen.step_dot_size) / 2f
            private var pulse: Animator? = null

            fun bind(row: StepRow) {
                name.text = "${row.index + 1}. ${row.step.name}"
                // selected 让超长标题跑马灯滚动；复用行时重置一次，保证重新绑定的文本也会滚动。
                name.isSelected = false
                name.isSelected = true
                meta.text = row.state.meta
                meta.visibility = if (row.state.meta.isNullOrBlank()) View.GONE else View.VISIBLE
                detail.text = row.state.message
                detail.visibility = if (row.state.message.isNullOrBlank()) View.GONE else View.VISIBLE
                dot.setBackgroundResource(row.state.status.dotRes)
                dot.contentDescription = row.state.status.label
                stopPulse()
                if (row.state.status == StepStatus.RUNNING) startPulse()
            }

            /** 执行中的圆点做缩放 + 透明度呼吸，反向无限循环；离开执行中即复位。 */
            private fun startPulse() {
                // bind 时控件还没布局，宽高为 0，默认轴心也会是 (0,0)；这里直接把轴心钉在圆心，
                // 保证放大时向四周均匀生长，而不是只向右下长。
                dot.pivotX = dotRadius
                dot.pivotY = dotRadius
                pulse = ObjectAnimator.ofPropertyValuesHolder(
                    dot,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.7f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.7f),
                    PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.4f)
                ).apply {
                    duration = PULSE_DURATION_MS
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    interpolator = AccelerateDecelerateInterpolator()
                    start()
                }
            }

            /** 取消动效并恢复缩放与透明度，避免复用的行带着上一项的动画残留。 */
            fun stopPulse() {
                pulse?.cancel()
                pulse = null
                dot.scaleX = 1f
                dot.scaleY = 1f
                dot.alpha = 1f
            }
        }

        private object StepDiff : DiffUtil.ItemCallback<StepRow>() {
            override fun areItemsTheSame(oldItem: StepRow, newItem: StepRow): Boolean =
                oldItem.index == newItem.index && oldItem.step.id == newItem.step.id

            override fun areContentsTheSame(oldItem: StepRow, newItem: StepRow): Boolean =
                oldItem == newItem
        }
    }

    private fun confirmRun() {
        val pkg = imported ?: run { picker.launch(arrayOf("*/*")); return }
        // 步骤内容对使用者透明：弹窗只做二次确认，不列出命令或传输路径。
        val summary = "${pkg.manifest.name} · ${pkg.manifest.version}\n" +
            "共 ${pkg.manifest.steps.size} 个步骤，将按顺序自动执行。执行期间请保持设备连接。"
        MaterialAlertDialogBuilder(this).setTitle("确认执行？").setMessage(summary)
            .setNegativeButton("取消", null).setPositiveButton("执行") { _, _ -> runPackage() }.show()
    }

    private fun runPackage() {
        if (running) return
        val pkg = imported ?: return
        running = true
        findViewById<Button>(R.id.runPackage).isEnabled = false
        lifecycleScope.launch {
            // 同一次执行复用同一个仓库实例：目录可写探测与续传状态只需建立一次；
            // 只有会话被自动重连替换时才新建，避免继续用已关闭的 socket。
            var repository: FileRepository? = null
            var repositorySession: TelnetClient.Session? = null
            try {
                for ((index, step) in pkg.manifest.steps.withIndex()) {
                    // 选文件时应用会短暂进入后台，部分 ROM 会中止原连接；每步执行前确认会话可用。
                    val session = DeviceSessionHolder.ensure()
                        ?: throw IllegalStateException("设备连接已断开")
                    val files = if (repository == null || repositorySession !== session) {
                        FileRepository(session).also {
                            repository = it
                            repositorySession = session
                        }
                    } else {
                        requireNotNull(repository)
                    }
                    states[index] = StepState(StepStatus.RUNNING); renderSteps()
                    try {
                        when (step) {
                            is RepairStep.Shell -> session.execute(step.command, step.timeoutSeconds * 1000)
                            is RepairStep.File -> {
                                val source = File(pkg.directory, step.source)
                                val host = LocalAddress.find(this@PackageActivity) ?: error("无法识别本机局域网地址")
                                val server = PhoneHttpServer(source)
                                // 绑定监听端口属于网络操作，必须在 IO 线程完成。
                                val url = withContext(Dispatchers.IO) { server.start(host) }
                                val startedAt = System.currentTimeMillis()
                                try {
                                    files.uploadFromHttp(
                                        url = url,
                                        target = step.target,
                                        totalBytes = source.length(),
                                        // 清单已登记哈希时在设备端复核，续传拼接错误会被立即发现。
                                        expectedSha256 = step.sha256,
                                        onProgress = { done, total ->
                                            states[index] = StepState(
                                                StepStatus.RUNNING,
                                                meta = transferMeta(done, total, System.currentTimeMillis() - startedAt)
                                            )
                                            renderSteps()
                                        },
                                        onRetry = { attempt, reason ->
                                            states[index] = StepState(
                                                StepStatus.RUNNING,
                                                meta = "第 ${attempt + 1} 次尝试续传：$reason"
                                            )
                                            renderSteps()
                                        }
                                    )
                                } finally {
                                    server.close()
                                }
                            }
                        }
                        states[index] = StepState(StepStatus.SUCCESS)
                    } catch (error: Exception) {
                        states[index] = StepState(StepStatus.FAILED, error.message ?: "未知错误")
                        renderSteps()
                        if (!step.continueOnError) throw error
                    }
                    renderSteps()
                }
                toast("修复步骤已完成")
            } catch (error: Exception) { toast(error.message ?: "执行中断") }
            finally {
                running = false
                findViewById<Button>(R.id.runPackage).isEnabled = true
            }
        }
    }

    /**
     * 步骤第二行的传输进度：已传 / 总量 · 百分比 · 平均速度。
     * 现场需要区分「慢」与「卡死」，只显示已传字节不足以判断，因此速率一起给出。
     */
    private fun transferMeta(done: Long, total: Long, elapsedMs: Long): String = buildString {
        append(formatByteSize(done))
        if (total > 0) {
            append(" / ").append(formatByteSize(total))
            append(" · ").append(((done * 100) / total).coerceIn(0, 100)).append("%")
        }
        val seconds = (elapsedMs / 1000.0).coerceAtLeast(0.001)
        append(" · ").append(formatByteSize((done / seconds).toLong())).append("/s")
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
