package com.example.casdonlink

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.casdonlink.connection.TelnetClient
import com.example.casdonlink.files.FileRepository
import com.example.casdonlink.files.LocalAddress
import com.example.casdonlink.files.PhoneHttpServer
import com.example.casdonlink.files.formatByteSize
import com.example.casdonlink.packages.PackageCrypto
import com.example.casdonlink.packages.PackageDecryptException
import com.example.casdonlink.packages.PackageImporter
import com.example.casdonlink.packages.PackageVerifier
import com.example.casdonlink.packages.RepairStep
import com.example.casdonlink.ui.applyDarkSystemBars
import com.example.casdonlink.ui.configureAsList
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 执行中圆点的呼吸动效周期（单程）。 */
private const val PULSE_DURATION_MS = 750L

/**
 * 修复包页：只接受加密容器 `.clpkg`，导入时输入密码；解密后的 ZIP 仍走原有
 * 清单校验与解压流程。执行开始后不提供主动停止，失败时只允许选择继续方式。
 */
class PackageActivity : AppCompatActivity() {
    private val picker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            lastUri = uri
            startImport(uri)
        }
    }

    private var imported: PackageImporter.ImportedPackage? = null
    private var running = false
    private var importing = false
    private var lastUri: Uri? = null
    private val states = mutableListOf<StepState>()
    private lateinit var stepAdapter: StepAdapter
    private var runStartedAt = 0L
    private var lastRunMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDarkSystemBars()
        setContentView(R.layout.activity_package)
        // 修复可能持续数分钟，保持屏幕常亮，避免息屏后 Telnet 会话被系统回收。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 进程被系统终止时，上次解密出的临时 ZIP 可能残留；启动时清理，避免明文包长期占用缓存。
        cacheDir.listFiles { file -> file.isFile && file.name.startsWith("package_import_") }
            ?.forEach { it.delete() }
        applySystemBarInsets()
        setUpStepList()

        findViewById<Button>(R.id.packageBack).setOnClickListener { handleBack() }
        findViewById<Button>(R.id.runPackage).setOnClickListener { onRunClicked() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })

        renderEmptyState()
        updateControls()
        picker.launch(arrayOf("*/*"))
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

    private fun renderEmptyState() {
        findViewById<TextView>(R.id.packageTitle).apply {
            text = "修复包"
            isSelected = true
        }
        findViewById<TextView>(R.id.packageDescription).text =
            "请选择官方签名的 .clpkg 加密修复包；导入时无需输入密码。"
        findViewById<TextView>(R.id.packageProgress).text = "未选择"
        states.clear()
        renderSteps()
    }

    /** 验签并解密到临时 ZIP，再交给 [PackageImporter] 做结构与清单校验。 */
    private fun startImport(uri: Uri) {
        if (importing || running) return
        importing = true
        updateControls()
        val busy = showBusy("正在导入修复包", "正在校验官方签名并解密…")
        lifecycleScope.launch {
            val temp = File(cacheDir, "package_import_${System.currentTimeMillis()}.zip")
            var result: PackageImporter.ImportedPackage? = null
            var failure: String? = null
            try {
                result = withContext(Dispatchers.IO) {
                    val verifier = PackageVerifier.fromAsset(this@PackageActivity)
                    contentResolver.openInputStream(uri)?.use { input ->
                        PackageCrypto.decryptToZip(input, verifier, temp)
                    } ?: throw PackageDecryptException("无法读取所选文件")
                    PackageImporter(this@PackageActivity).importZip(temp)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failure = friendlyImportError(error)
            } finally {
                temp.delete()
                busy.dismiss()
                importing = false
                if (!isFinishing && !isDestroyed) updateControls()
            }
            val loaded = result
            if (loaded == null) {
                showImportFailure(failure ?: "导入失败")
                return@launch
            }
            imported = loaded
            states.clear()
            states.addAll(loaded.manifest.steps.map { StepState(StepStatus.PENDING) })
            findViewById<TextView>(R.id.packageTitle).apply {
                text = "${loaded.manifest.name} · ${loaded.manifest.version}"
                isSelected = true
            }
            findViewById<TextView>(R.id.packageDescription).text = buildString {
                if (loaded.manifest.description.isNotBlank()) {
                    append(loaded.manifest.description).append('\n')
                }
                append("ID · ").append(loaded.manifest.packageId)
                append(" · 共 ").append(loaded.manifest.steps.size).append(" 个步骤 · 官方签名已校验")
            }
            findViewById<TextView>(R.id.packageProgress).text = "${states.size} 步"
            renderSteps()
            updateControls()
            toast("修复包已导入")
        }
    }

    private fun friendlyImportError(error: Exception): String = when (error) {
        is PackageDecryptException -> error.message ?: "修复包校验失败"
        is java.util.zip.ZipException -> "修复包 ZIP 无效：${error.message ?: "无法解析"}"
        else -> error.message ?: error.javaClass.simpleName
    }

    private fun showImportFailure(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("导入失败")
            .setMessage("$message\n\n请确认选择的是官方签名修复包（.clpkg），或重新选择文件。")
            .setPositiveButton("重试") { _, _ -> lastUri?.let { startImport(it) } }
            .setNeutralButton("重新选择") { _, _ -> picker.launch(arrayOf("*/*")) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showBusy(title: String, status: String): AlertDialog {
        val view = layoutInflater.inflate(R.layout.dialog_transfer_progress, null)
        view.findViewById<TextView>(R.id.transferTitle).text = title
        view.findViewById<TextView>(R.id.transferStatus).text = status
        view.findViewById<ProgressBar>(R.id.transferBar).isIndeterminate = true
        return MaterialAlertDialogBuilder(this)
            .setView(view)
            .setCancelable(false)
            .create()
            .also { it.show() }
    }

    /** 没有包时打开选择器；有失败步骤时先让用户选择继续方式。 */
    private fun onRunClicked() {
        if (running || importing) return
        val pkg = imported
        if (pkg == null) {
            picker.launch(arrayOf("*/*"))
            return
        }
        val failed = states.indexOfFirst { it.status == StepStatus.FAILED }
        if (failed >= 0) {
            val success = states.count { it.status == StepStatus.SUCCESS }
            val failedCount = states.count { it.status == StepStatus.FAILED }
            val pending = states.count { it.status == StepStatus.PENDING }
            // 如果失败步骤之后已有成功步骤，说明是 continueOnError 走完了流程；
            // 此时“从失败处继续”会重复执行后面的成功步骤，只提供从头开始。
            val hasSuccessAfterFailure = (failed + 1 until states.size)
                .any { states[it].status == StepStatus.SUCCESS }
            if (hasSuccessAfterFailure) {
                showPartialSummary(success, failedCount, pending)
            } else {
                showFailureOptions(
                    index = failed,
                    message = states[failed].message ?: "上次执行未完成",
                    success = success,
                    failed = failedCount,
                    pending = pending
                )
            }
            return
        }
        if (states.any { it.status == StepStatus.SUCCESS }) {
            MaterialAlertDialogBuilder(this)
                .setTitle("再次执行？")
                .setMessage("将从头重新执行全部 ${pkg.manifest.steps.size} 个步骤。")
                .setNegativeButton("取消", null)
                .setPositiveButton("从头开始") { _, _ -> startRun(0) }
                .show()
            return
        }
        confirmRun(pkg)
    }

    private fun confirmRun(pkg: PackageImporter.ImportedPackage) {
        val summary = "包 ID · ${pkg.manifest.packageId}\n" +
            "${pkg.manifest.name} · ${pkg.manifest.version}\n" +
            "共 ${pkg.manifest.steps.size} 个步骤，将按顺序自动执行。\n" +
            "开始后不能主动停止，请保持设备连接。"
        MaterialAlertDialogBuilder(this)
            .setTitle("确认执行？")
            .setMessage(summary)
            .setNegativeButton("取消", null)
            .setPositiveButton("执行") { _, _ -> startRun(0) }
            .show()
    }

    /**
     * 从 [startIndex] 开始执行；0 表示全部从头开始。失败后选择“从失败处继续”时
     * 保留之前的成功状态，只重置失败步骤及其后的步骤。
     */
    private fun startRun(startIndex: Int) {
        if (running) return
        val pkg = imported ?: return
        val total = pkg.manifest.steps.size
        if (startIndex !in 0 until total) return
        if (startIndex == 0) {
            for (index in states.indices) states[index] = StepState(StepStatus.PENDING)
        } else {
            for (index in startIndex until states.size) states[index] = StepState(StepStatus.PENDING)
        }
        running = true
        runStartedAt = System.currentTimeMillis()
        updateProgress(startIndex, total)
        renderSteps()
        updateControls()

        lifecycleScope.launch {
            var repository: FileRepository? = null
            var repositorySession: TelnetClient.Session? = null
            var fatalIndex: Int? = null
            var fatalMessage: String? = null
            var currentIndex = startIndex
            try {
                for (index in startIndex until total) {
                    currentIndex = index
                    val step = pkg.manifest.steps[index]
                    val session = try {
                        DeviceSessionHolder.ensure() ?: throw IllegalStateException("设备连接已断开")
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        states[index] = StepState(StepStatus.FAILED, error.message ?: "设备连接已断开")
                        fatalIndex = index
                        fatalMessage = error.message ?: "设备连接已断开"
                        renderSteps()
                        break
                    }
                    val files = if (repository == null || repositorySession !== session) {
                        FileRepository(session).also {
                            repository = it
                            repositorySession = session
                        }
                    } else {
                        requireNotNull(repository)
                    }
                    states[index] = StepState(StepStatus.RUNNING)
                    renderSteps()
                    updateProgress(index + 1, total)
                    try {
                        when (step) {
                            is RepairStep.Shell ->
                                session.execute(step.command, step.timeoutSeconds * 1000)
                            is RepairStep.File -> {
                                val source = File(pkg.directory, step.source)
                                val host = LocalAddress.find(this@PackageActivity)
                                    ?: error("无法识别本机局域网地址")
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
                                        onProgress = { done, value ->
                                            states[index] = StepState(
                                                StepStatus.RUNNING,
                                                meta = transferMeta(
                                                    done,
                                                    value,
                                                    System.currentTimeMillis() - startedAt
                                                )
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
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        states[index] = StepState(StepStatus.FAILED, error.message ?: "未知错误")
                        renderSteps()
                        if (!step.continueOnError) {
                            fatalIndex = index
                            fatalMessage = error.message ?: error.javaClass.simpleName
                            break
                        }
                    }
                    renderSteps()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val index = currentIndex.coerceIn(0, total - 1)
                if (states.getOrNull(index)?.status != StepStatus.FAILED) {
                    states[index] = StepState(StepStatus.FAILED, error.message ?: "执行中断")
                }
                fatalIndex = index
                fatalMessage = error.message ?: "执行中断"
            } finally {
                running = false
                lastRunMs = System.currentTimeMillis() - runStartedAt
                if (isFinishing || isDestroyed) return@launch
                renderSteps()
                updateControls()
                val success = states.count { it.status == StepStatus.SUCCESS }
                val failed = states.count { it.status == StepStatus.FAILED }
                val pending = states.count { it.status == StepStatus.PENDING }
                if (fatalIndex != null) {
                    updateProgress(success + failed, total)
                    showFailureOptions(
                        index = fatalIndex!!,
                        message = fatalMessage ?: "未知错误",
                        success = success,
                        failed = failed,
                        pending = pending
                    )
                } else if (failed > 0) {
                    updateProgress(success + failed, total)
                    showPartialSummary(success, failed, pending)
                } else {
                    findViewById<TextView>(R.id.packageProgress).text = "全部完成 · $total 步"
                    toast("修复步骤已完成")
                }
            }
        }
    }

    /** 失败后的二次弹窗：只允许选择继续方式，不提供停止。 */
    private fun showFailureOptions(
        index: Int,
        message: String,
        success: Int,
        failed: Int,
        pending: Int
    ) {
        val step = imported?.manifest?.steps?.getOrNull(index)
        val stepLabel = if (step != null) "步骤 ${index + 1}「${step.name}」" else "步骤 ${index + 1}"
        MaterialAlertDialogBuilder(this)
            .setTitle("修复未完成")
            .setMessage(
                "$stepLabel 失败：\n$message\n\n" +
                    "成功 $success · 失败 $failed · 未执行 $pending\n" +
                    "耗时 ${formatDuration(lastRunMs)}\n\n请选择继续方式。"
            )
            .setPositiveButton("从失败处继续") { _, _ -> startRun(index) }
            .setNeutralButton("从头开始") { _, _ -> startRun(0) }
            .setNegativeButton("关闭", null)
            .setCancelable(false)
            .show()
    }

    /** continueOnError 导致部分步骤失败但流程已走完时的汇总；不提供“从失败处继续”。 */
    private fun showPartialSummary(success: Int, failed: Int, pending: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle("执行完成（有失败）")
            .setMessage(
                "成功 $success · 失败 $failed · 未执行 $pending\n" +
                    "耗时 ${formatDuration(lastRunMs)}\n\n可从头重新执行全部步骤。"
            )
            .setPositiveButton("从头开始") { _, _ -> startRun(0) }
            .setNegativeButton("关闭", null)
            .setCancelable(false)
            .show()
    }

    private fun updateProgress(done: Int, total: Int) {
        findViewById<TextView>(R.id.packageProgress).text = "$done/$total"
    }

    private fun updateControls() {
        val run = findViewById<Button>(R.id.runPackage)
        run.isEnabled = !running && !importing
        run.text = when {
            running -> "执行中…"
            importing -> "导入中…"
            imported == null -> "选择加密修复包"
            states.any { it.status == StepStatus.FAILED } -> "重新执行"
            else -> "执行修复"
        }
        findViewById<Button>(R.id.packageBack).alpha = if (running) 0.5f else 1f
    }

    private fun handleBack() {
        if (running) {
            toast("修复包执行中，不能停止")
            return
        }
        if (importing) {
            toast("正在导入修复包，请稍候")
            return
        }
        finish()
    }

    private fun formatDuration(millis: Long): String {
        val seconds = (millis / 1000).coerceAtLeast(0)
        return if (seconds >= 60) "${seconds / 60} 分 ${seconds % 60} 秒" else "$seconds 秒"
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    /** 状态变化时整份重新提交，DiffUtil 会把更新定位到具体行，不重建整个列表。 */
    private fun renderSteps() {
        val result = imported ?: return
        stepAdapter.submitList(
            result.manifest.steps.mapIndexed { index, step ->
                StepRow(index, step, states.getOrElse(index) { StepState(StepStatus.PENDING) })
            }
        )
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

    private fun transferMeta(done: Long, total: Long, elapsedMs: Long): String = buildString {
        append(formatByteSize(done))
        if (total > 0) {
            append(" / ").append(formatByteSize(total))
            append(" · ").append(((done * 100) / total).coerceIn(0, 100)).append("%")
        }
        val seconds = (elapsedMs / 1000.0).coerceAtLeast(0.001)
        append(" · ").append(formatByteSize((done / seconds).toLong())).append("/s")
    }
}
