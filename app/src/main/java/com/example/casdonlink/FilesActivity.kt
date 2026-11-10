package com.example.casdonlink

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.casdonlink.connection.Diagnostics
import com.example.casdonlink.files.DeviceFile
import com.example.casdonlink.files.FileRepository
import com.example.casdonlink.files.PhoneHttpServer
import com.example.casdonlink.files.LocalAddress
import com.example.casdonlink.files.formatByteSize
import com.example.casdonlink.ui.applyDarkSystemBars
import com.example.casdonlink.ui.configureAsList
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class FilesActivity : AppCompatActivity() {
    private var path = "/"
    private var files = emptyList<DeviceFile>()
    private lateinit var fileAdapter: FileAdapter
    private var activeHttpServer: PhoneHttpServer? = null
    private val picker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) upload(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDarkSystemBars()
        setContentView(R.layout.activity_files)
        applySystemBarInsets()
        if (!DeviceSessionHolder.hasTarget()) {
            Toast.makeText(this, "设备连接已断开", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        // 左上角只负责退出回上一页面；上/下一层浏览交给列表首行的「..」与目录点击。
        findViewById<Button>(R.id.filesBackButton).setOnClickListener { exitToDevice() }
        // 路径本身作为快捷入口：任意层级都能一步跳回根目录或设备状态页。
        findViewById<TextView>(R.id.filesPath).setOnClickListener { showLocationMenu() }
        // 上传、新建文件/文件夹、退出都收进右下角浮空按钮，顶部只保留导航。
        findViewById<View>(R.id.filesFab).setOnClickListener { showActionMenu(it) }
        setUpFileList()
        load(path)
    }

    /** 列表只负责展示与事件转发，点击/长按的具体行为留在 Activity。 */
    private fun setUpFileList() {
        val list = findViewById<RecyclerView>(R.id.fileList)
        list.configureAsList()
        fileAdapter = FileAdapter(
            onOpen = { row ->
                when (row) {
                    FileRow.Parent -> navigateUp()
                    is FileRow.Entry -> if (row.file.directory) load(row.file.path) else showFile(row.file)
                }
            },
            onLongPress = { row ->
                // 「..」是导航项，不参与重命名/删除/下载。
                if (row is FileRow.Entry) showActions(row.file)
            }
        )
        list.adapter = fileAdapter
    }

    /**
     * targetSdk 35+ 强制 edge-to-edge，内容会画到状态栏/导航栏下面。
     * 这里把系统栏高度叠加到根布局原有 padding 上，避免标题栏贴顶、底部按钮贴边。
     */
    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.filesRoot)
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

    private fun load(target: String) {
        lifecycleScope.launch {
            try {
                path = target
                findViewById<TextView>(R.id.filesPath).text = path
                setHint("读取中…")
                val result = repository().list(path)
                files = result
                renderFiles()
                // 空目录和「命令没有输出」在界面上要能区分，否则只能看到一片空白。
                setHint(
                    when {
                        result.isNotEmpty() -> "${result.size} 项 · 长按可操作 · 点路径可跳转"
                        hasParent -> "目录为空 · 点「..」可返回上一层"
                        else -> "目录为空 · 点击重试"
                    }
                )
            } catch (error: Exception) {
                files = emptyList()
                renderFiles()
                val reason = error.message ?: error.javaClass.simpleName
                Diagnostics.log("列目录失败（$path）：$reason")
                setHint("读取失败：$reason\n点击重试")
                toast(reason)
            }
        }
    }

    /**
     * 连接可能在应用退到后台期间被 ROM 中止，每次操作前重新获取可用会话，
     * 失效时由 [DeviceSessionHolder.ensure] 自动重连。
     */
    private suspend fun repository(): FileRepository = FileRepository(
        DeviceSessionHolder.ensure() ?: error("设备连接已断开")
    )

    /** 顶部提示条同时充当状态与重试入口。 */
    private fun setHint(text: String) {
        findViewById<TextView>(R.id.filesHint).apply {
            this.text = text
            setOnClickListener { load(path) }
        }
    }

    /** 非根目录时列表首行显示「..」导航项。 */
    private val hasParent: Boolean get() = path.trimEnd('/').isNotEmpty()

    /** 把当前目录内容（含首行「..」）交给列表，由 DiffUtil 计算最小更新量。 */
    private fun renderFiles() {
        val rows = buildList {
            if (hasParent) add(FileRow.Parent)
            files.forEach { add(FileRow.Entry(it)) }
        }
        fileAdapter.submitList(rows)
    }

    private fun navigateUp() {
        if (!hasParent) return
        val parent = path.trimEnd('/').substringBeforeLast('/', "")
        load(parent.ifBlank { "/" })
    }

    /** 浮空按钮弹出的操作菜单：当前目录下可执行的动作都在这里。返回上一页面由左上角按钮负责。 */
    private fun showActionMenu(anchor: View) {
        val actions = listOf("新建文件", "新建文件夹", "上传文件")
        // 套用悬浮菜单主题，避免使用系统浅色菜单破坏深色页面风格。
        val themed = ContextThemeWrapper(this, R.style.ThemeOverlay_CasdonLink_PopupMenu)
        PopupMenu(themed, anchor).apply {
            actions.forEach { menu.add(it) }
            setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "新建文件" -> promptCreate(directory = false)
                    "新建文件夹" -> promptCreate(directory = true)
                    "上传文件" -> picker.launch(arrayOf("*/*"))
                }
                true
            }
            show()
        }
    }

    /** 在当前目录创建空文件或目录；名称在这里校验，避免把非法字符传给设备 shell。 */
    private fun promptCreate(directory: Boolean) {
        val title = if (directory) "新建文件夹" else "新建文件"
        val content = layoutInflater.inflate(R.layout.dialog_input, null)
        val field = content.findViewById<EditText>(R.id.dialogInput).apply {
            hint = if (directory) "文件夹名称" else "文件名"
        }
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(path).setView(content)
            .setNegativeButton("取消", null)
            .setPositiveButton("创建") { _, _ ->
                val name = field.text.toString().trim()
                validateName(name)?.let { toast(it); return@setPositiveButton }
                val target = path.trimEnd('/') + "/" + name
                lifecycleScope.launch {
                    runCatching {
                        if (directory) repository().createDirectory(target) else repository().createFile(target)
                    }.onSuccess {
                        toast("已创建 $name")
                        load(path)
                    }.onFailure { toast(it.message ?: "$title 失败") }
                }
            }.show()
    }

    /** 设备端文件名校验：与 shell 参数拼接方式保持一致，拒绝会改变路径含义的输入。 */
    private fun validateName(name: String): String? = when {
        name.isBlank() -> "名称不能为空"
        name == "." || name == ".." -> "名称无效"
        name.contains('/') -> "名称不能包含 /"
        else -> null
    }

    /** 退出文件页，回到上一页面（设备状态页）。 */
    private fun exitToDevice() {
        finish()
    }

    /**
     * 路径点击菜单：列出当前目录的所有上层与根目录，任选一层直接跳过去；
     * 首项可在任意深度一步回到设备状态页。
     */
    private fun showLocationMenu() {
        val ancestors = mutableListOf<String>()
        var cursor = path.trimEnd('/')
        while (cursor.isNotEmpty()) {
            ancestors += cursor
            cursor = cursor.substringBeforeLast('/', "")
        }
        // ancestors 当前由近到远，倒序后变成「根 → 当前」，便于按层级阅读。
        val targets = ancestors.reversed().filter { it != path }
        val items = mutableListOf("返回设备状态页")
        items += targets.map { if (it == "/") "根目录 /" else it }
        MaterialAlertDialogBuilder(this).setTitle(path).setItems(items.toTypedArray()) { _, which ->
            if (which == 0) exitToDevice() else load(targets[which - 1])
        }.show()
    }

    private fun showFile(file: DeviceFile) {
        lifecycleScope.launch {
            try {
                val text = repository().readText(file.path)
                MaterialAlertDialogBuilder(this@FilesActivity).setTitle(file.name)
                    .setMessage(text.take(8_000)).setPositiveButton("关闭", null).show()
            } catch (_: Exception) {
                MaterialAlertDialogBuilder(this@FilesActivity).setTitle(file.name).setMessage("无法作为文本打开")
                    .setPositiveButton("下载", null).show()
            }
        }
    }

    private fun showActions(file: DeviceFile) {
        val actions = mutableListOf("重命名", "删除")
        if (!file.directory) actions += "下载"
        MaterialAlertDialogBuilder(this).setTitle(file.name).setItems(actions.toTypedArray()) { _, which ->
            when (actions[which]) {
                "重命名" -> rename(file)
                "删除" -> confirmDelete(file)
                "下载" -> download(file)
            }
        }.show()
    }

    private fun rename(file: DeviceFile) {
        val content = layoutInflater.inflate(R.layout.dialog_input, null)
        val field = content.findViewById<EditText>(R.id.dialogInput).apply { setText(file.name) }
        MaterialAlertDialogBuilder(this).setTitle("重命名").setView(content).setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val newName = field.text.toString().trim()
                validateName(newName)?.let { toast(it); return@setPositiveButton }
                lifecycleScope.launch {
                    runCatching { repository().rename(file.path, "$path/$newName") }
                        .onSuccess { load(path) }.onFailure { toast(it.message ?: "重命名失败") }
                }
            }.show()
    }

    private fun confirmDelete(file: DeviceFile) {
        MaterialAlertDialogBuilder(this).setTitle("删除文件？").setMessage(file.path)
            .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    runCatching { repository().delete(file.path) }.onSuccess { load(path) }
                        .onFailure { toast(it.message ?: "删除失败") }
                }
            }.show()
    }

    private fun upload(uri: Uri) {
        lifecycleScope.launch {
            val targetName = displayName(uri)
            val temp = File(cacheDir, "upload_${System.currentTimeMillis()}")
            try {
                withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use(input::copyTo) } }
                val server = PhoneHttpServer(temp)
                activeHttpServer = server
                val host = LocalAddress.find(this@FilesActivity) ?: throw IllegalStateException("无法识别本机局域网地址")
                val url = withContext(Dispatchers.IO) { server.start(host) }
                // 大文件传输可能持续很久，必须给出进度与取消入口，否则界面看起来像卡死。
                val progress = showTransferProgress("上传 $targetName")
                try {
                    repository().uploadFromHttp(
                        url = url,
                        target = path.trimEnd('/') + "/" + targetName,
                        totalBytes = temp.length(),
                        isCancelled = { progress.cancelled },
                        onProgress = { done, total -> progress.update(done, total) }
                    )
                } finally {
                    progress.dismiss()
                }
                toast("传输完成")
                load(path)
            } catch (error: Exception) { toast(error.message ?: "上传失败") }
            finally { activeHttpServer?.close(); activeHttpServer = null; temp.delete() }
        }
    }

    /** 读取系统文件选择器提供的显示名，作为设备端保存名；失败时退回时间戳名称。 */
    private fun displayName(uri: Uri): String {
        val raw = runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        val name = raw?.substringAfterLast('/')?.trim()?.replace(Regex("[\\u0000-\\u001f]"), "")
        return if (name.isNullOrBlank() || name == "." || name == "..") {
            "upload_${System.currentTimeMillis()}.bin"
        } else {
            name
        }
    }

    private fun download(file: DeviceFile) {
        lifecycleScope.launch {
            val progress = showTransferProgress("下载 ${file.name}")
            try {
                val bytes = repository().downloadBase64(
                    path = file.path,
                    isCancelled = { progress.cancelled }
                ) { done, total -> progress.update(done, total) }
                val cache = File(cacheDir, file.name)
                withContext(Dispatchers.IO) { cache.writeBytes(bytes) }
                val intent = Intent(Intent.ACTION_SEND).setType("application/octet-stream")
                    .putExtra(Intent.EXTRA_STREAM, androidx.core.content.FileProvider.getUriForFile(this@FilesActivity,
                        "$packageName.fileprovider", cache)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(intent, "保存文件"))
            } catch (error: Exception) {
                toast(error.message ?: "下载失败")
            } finally {
                progress.dismiss()
            }
        }
    }

    /**
     * 传输进度对话框：展示已传输字节、总量、百分比与平均速度，并提供取消入口。
     * 取消只做标记，由传输循环在下一次检查时退出并清理半成品。
     */
    private inner class TransferProgressUi(
        private val view: View,
        private val dialog: AlertDialog,
        private val startedAt: Long
    ) {
        @Volatile var cancelled = false

        fun update(done: Long, total: Long) = runOnUiThread {
            val percent = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
            view.findViewById<ProgressBar>(R.id.transferBar).progress = percent
            val seconds = ((System.currentTimeMillis() - startedAt) / 1000.0).coerceAtLeast(0.001)
            val status = buildString {
                append(formatByteSize(done))
                if (total > 0) {
                    append(" / ").append(formatByteSize(total)).append(" · ").append(percent).append("%")
                } else {
                    append(" · 已传输")
                }
                append("\n平均速度 ").append(formatByteSize((done / seconds).toLong())).append("/s")
            }
            view.findViewById<TextView>(R.id.transferStatus).text = status
        }

        fun dismiss() = runOnUiThread { if (dialog.isShowing) dialog.dismiss() }
    }

    private fun showTransferProgress(title: String): TransferProgressUi {
        val view = layoutInflater.inflate(R.layout.dialog_transfer_progress, null)
        view.findViewById<TextView>(R.id.transferTitle).text = title
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .setNegativeButton("取消", null)
            .setCancelable(false)
            .create()
        dialog.show()
        val ui = TransferProgressUi(view, dialog, System.currentTimeMillis())
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener { ui.cancelled = true }
        return ui
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        activeHttpServer?.close()
        super.onDestroy()
    }

    private inner class FileAdapter(
        private val onOpen: (FileRow) -> Unit,
        private val onLongPress: (FileRow) -> Unit
    ) : ListAdapter<FileRow, FileAdapter.Holder>(RowDiff) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.row_file, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val icon = view.findViewById<TextView>(R.id.fileIcon)
            private val name = view.findViewById<TextView>(R.id.fileName)
            private val meta = view.findViewById<TextView>(R.id.fileMeta)

            fun bind(row: FileRow) {
                when (row) {
                    FileRow.Parent -> {
                        icon.text = "↰"
                        name.text = ".."
                        meta.text = "返回上一层"
                    }
                    is FileRow.Entry -> {
                        val file = row.file
                        icon.text = if (file.directory) "⌂" else "·"
                        name.text = file.name
                        meta.text = if (file.directory) "文件夹" else formatByteSize(file.size)
                    }
                }
                itemView.setOnClickListener { onOpen(row) }
                itemView.setOnLongClickListener {
                    onLongPress(row)
                    true
                }
            }
        }
    }

    /** 文件列表的行：首行「..」导航项与设备文件条目。 */
    private sealed interface FileRow {
        data object Parent : FileRow
        data class Entry(val file: DeviceFile) : FileRow
    }

    private object RowDiff : DiffUtil.ItemCallback<FileRow>() {
        override fun areItemsTheSame(oldItem: FileRow, newItem: FileRow): Boolean = when {
            oldItem is FileRow.Parent && newItem is FileRow.Parent -> true
            oldItem is FileRow.Entry && newItem is FileRow.Entry -> oldItem.file.path == newItem.file.path
            else -> false
        }

        override fun areContentsTheSame(oldItem: FileRow, newItem: FileRow): Boolean = oldItem == newItem
    }
}
