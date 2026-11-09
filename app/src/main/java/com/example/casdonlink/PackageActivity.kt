package com.example.casdonlink

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.casdonlink.files.PhoneHttpServer
import com.example.casdonlink.files.LocalAddress
import com.example.casdonlink.files.FileRepository
import com.example.casdonlink.packages.PackageImporter
import com.example.casdonlink.packages.RepairStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PackageActivity : AppCompatActivity() {
    private val picker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importPackage(uri)
    }
    private var imported: PackageImporter.ImportedPackage? = null
    private var running = false
    private val states = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_package)
        applySystemBarInsets()
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

    private fun importPackage(uri: android.net.Uri) {
        lifecycleScope.launch {
            try {
                val result = PackageImporter(this@PackageActivity).import(uri) { contentResolver.openInputStream(it) }
                imported = result
                states.clear(); states.addAll(result.manifest.steps.map { "待执行" })
                findViewById<TextView>(R.id.packageTitle).text = "${result.manifest.name} · ${result.manifest.version}"
                findViewById<TextView>(R.id.packageDescription).text = "${result.manifest.description}\n${result.manifest.steps.size} 个步骤 · 请核对目标与命令"
                renderSteps()
            } catch (error: Exception) { toast(error.message ?: "导入失败"); finish() }
        }
    }

    private fun renderSteps() {
        val result = imported ?: return
        findViewById<ListView>(R.id.stepList).adapter = object : BaseAdapter() {
            override fun getCount() = result.manifest.steps.size
            override fun getItem(position: Int) = result.manifest.steps[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(this@PackageActivity).inflate(R.layout.row_step, parent, false)
                val step = getItem(position)
                view.findViewById<TextView>(R.id.stepName).text = "${position + 1}. ${step.name}"
                val detail = when (step) {
                    is RepairStep.File -> "传输 ${step.source} → ${step.target}"
                    is RepairStep.Shell -> "命令 · ${step.command}"
                }
                view.findViewById<TextView>(R.id.stepDetail).text = "${states[position]} · $detail"
                return view
            }
        }
        findViewById<TextView>(R.id.packageProgress).text = "${result.manifest.steps.size} 步"
    }

    private fun confirmRun() {
        val pkg = imported ?: run { picker.launch(arrayOf("*/*")); return }
        val summary = pkg.manifest.steps.mapIndexed { index, step ->
            "${index + 1}. ${step.name}: " + when (step) {
                is RepairStep.File -> "${step.source} → ${step.target}"
                is RepairStep.Shell -> step.command
            }
        }.joinToString("\n")
        AlertDialog.Builder(this).setTitle("确认执行 ${pkg.manifest.name}？").setMessage(summary)
            .setNegativeButton("取消", null).setPositiveButton("执行") { _, _ -> runPackage() }.show()
    }

    private fun runPackage() {
        if (running) return
        val pkg = imported ?: return
        running = true
        findViewById<Button>(R.id.runPackage).isEnabled = false
        lifecycleScope.launch {
            try {
                for ((index, step) in pkg.manifest.steps.withIndex()) {
                    // 选文件时应用会短暂进入后台，部分 ROM 会中止原连接；每步执行前确认会话可用。
                    val session = DeviceSessionHolder.ensure()
                        ?: throw IllegalStateException("设备连接已断开")
                    val files = FileRepository(session)
                    states[index] = "执行中"; renderSteps()
                    try {
                        when (step) {
                            is RepairStep.Shell -> session.execute(step.command, step.timeoutSeconds * 1000)
                            is RepairStep.File -> {
                                val source = File(pkg.directory, step.source)
                                val host = LocalAddress.find(this@PackageActivity) ?: error("无法识别本机局域网地址")
                                val server = PhoneHttpServer(source)
                                // 绑定监听端口属于网络操作，必须在 IO 线程完成。
                                val url = withContext(Dispatchers.IO) { server.start(host) }
                                try { files.uploadFromHttp(url, step.target) } finally { server.close() }
                            }
                        }
                        states[index] = "成功"
                    } catch (error: Exception) {
                        states[index] = "失败 · ${error.message ?: "错误"}"
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

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
