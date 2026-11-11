package com.example.casdonlink

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
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
import androidx.recyclerview.widget.LinearLayoutManager
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
    /** 旋转重建后待恢复的列表位置；-1 表示当前没有待恢复位置。 */
    private var pendingScroll = -1
    /** 等待 SAF「保存到…」返回的目标文件内容；一次只能保存一个文件。 */
    private var pendingSaveBytes: ByteArray? = null
    private val picker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) upload(uri)
    }
    private val saveLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val bytes = pendingSaveBytes
        pendingSaveBytes = null
        if (uri == null || bytes == null) return@registerForActivityResult
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("无法写入所选文件")
                }
            }.onSuccess { toast("已保存到所选位置") }
                .onFailure { toast("保存失败：${it.message ?: "未知错误"}") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDarkSystemBars()
        setContentView(R.layout.activity_files)
        applySystemBarInsets()
        if (!DeviceSessionHolder.hasTarget()) {
            Toast.makeText(this, "连接已断开", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        // 左上角只负责退出回上一页面；上/下一层浏览交给列表首行的「..」与目录点击。
        findViewById<Button>(R.id.filesBackButton).setOnClickListener { exitToDevice() }
        // 路径本身作为快捷入口：任意层级都能一步跳回根目录或设备状态页。
        findViewById<TextView>(R.id.filesPath).setOnClickListener { showLocationMenu() }
        // 显式刷新入口：路径栏右侧按钮，避免只能靠点提示条重试。
        findViewById<Button>(R.id.filesRefresh).setOnClickListener { load(path) }
        // 上传、新建文件/文件夹、退出都收进右下角浮空按钮，顶部只保留导航。
        findViewById<View>(R.id.filesFab).setOnClickListener { showActionMenu(it) }
        setUpFileList()
        // 旋转/重建时保留当前目录与列表位置；列表内容重新从设备读取。
        savedInstanceState?.let {
            path = it.getString(KEY_PATH) ?: path
            pendingScroll = it.getInt(KEY_SCROLL, 0)
        }
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
                        result.isNotEmpty() -> "${result.size} 项 · 长按操作"
                        hasParent -> "目录为空"
                        else -> "目录为空，点击重试"
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
        DeviceSessionHolder.ensure() ?: error("连接已断开")
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
        // 旋转重建后列表内容异步读回，等提交完成再把位置还原到原来的可视行。
        if (pendingScroll >= 0 && rows.isNotEmpty()) {
            val target = pendingScroll.coerceAtMost(rows.lastIndex)
            pendingScroll = -1
            val list = findViewById<RecyclerView>(R.id.fileList)
            list.post {
                (list.layoutManager as? LinearLayoutManager)?.scrollToPosition(target)
            }
        }
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
    private fun validateName(name: String): String? = validateFileName(name)

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
            val active = runCatching { repository() }.getOrNull()
            if (active == null) {
                toast("连接已断开")
                return@launch
            }
            val text = runCatching { active.readText(file.path) }.getOrNull()
            if (text == null) {
                // 二进制文件无法按文本解析：至少让「下载」真的能把文件取回本机。
                MaterialAlertDialogBuilder(this@FilesActivity)
                    .setTitle(file.name)
                    .setMessage("无法按文本打开，可下载查看。")
                    .setNegativeButton("关闭", null)
                    .setPositiveButton("下载") { _, _ -> download(file) }
                    .show()
                return@launch
            }
            val view = layoutInflater.inflate(R.layout.dialog_text, null)
            view.findViewById<TextView>(R.id.dialogText).text = buildString {
                append(text.take(PREVIEW_CHARS))
                if (text.length > PREVIEW_CHARS) {
                    append("\n\n…仅显示前 ").append(PREVIEW_CHARS).append(" 字符")
                }
            }
            MaterialAlertDialogBuilder(this@FilesActivity)
                .setTitle(file.name)
                .setView(view)
                .setNegativeButton("关闭", null)
                .setNeutralButton("复制") { _, _ ->
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("CasdonLink ${file.name}", text))
                    toast("已复制到剪贴板")
                }
                .setPositiveButton("下载") { _, _ -> download(file) }
                .show()
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
        val title = if (file.directory) "删除目录？" else "删除文件？"
        val message = if (file.directory) {
            "${file.path}\n\n目录及全部内容将被删除，无法恢复。"
        } else {
            "${file.path}\n\n删除后无法恢复。"
        }
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message)
            .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    runCatching { repository().delete(file.path) }.onSuccess { load(path) }
                        .onFailure { toast(it.message ?: "删除失败") }
                }
            }.show()
    }

    /**
     * 上传前先处理同名文件：同名目录直接拒绝；同名文件二次确认后才覆盖，
     * 避免误把设备上的原文件替换掉。
     */
    /**
     * 上传前先让用户确认/修改设备端文件名：默认取系统文件选择器里的显示名，
     * 名称非法时留在弹窗内提示；同名目录直接拒绝，同名文件二次确认后才覆盖。
     */
    private fun upload(uri: Uri) {
        val defaultName = displayName(uri)
        val content = layoutInflater.inflate(R.layout.dialog_input, null)
        val field = content.findViewById<EditText>(R.id.dialogInput).apply {
            setText(defaultName)
            setSelection(defaultName.length)
            hint = "设备端文件名"
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("上传到 $path")
            .setView(content)
            .setNegativeButton("取消", null)
            .setPositiveButton("上传", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val targetName = field.text.toString().trim()
                validateName(targetName)?.let {
                    field.error = it
                    return@setOnClickListener
                }
                val existing = files.firstOrNull { it.name == targetName }
                if (existing?.directory == true) {
                    field.error = "已存在同名目录"
                    return@setOnClickListener
                }
                dialog.dismiss()
                if (existing != null) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("覆盖同名文件？")
                        .setMessage("已存在 $targetName，继续将覆盖原文件。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("覆盖") { _, _ -> startUpload(uri, targetName) }
                        .show()
                } else {
                    startUpload(uri, targetName)
                }
            }
        }
        dialog.show()
    }

    private fun startUpload(uri: Uri, targetName: String) {
        lifecycleScope.launch {
            val temp = File(cacheDir, "upload_${System.currentTimeMillis()}")
            try {
                withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use(input::copyTo) } }
                val server = PhoneHttpServer(temp)
                activeHttpServer = server
                // 选本机地址要遍历网卡并解析目标地址（可能触发 DNS），不能放在主线程。
                val selection = withContext(Dispatchers.IO) {
                    LocalAddress.select(this@FilesActivity, DeviceSessionHolder.host)
                }
                Diagnostics.log("上传服务端地址：${selection.message}")
                val host = selection.address ?: throw IllegalStateException(selection.message)
                // 无法确认同网段时明确提示，避免 Wi-Fi + VPN 并存时静默选错网卡。
                if (!selection.sameSubnet) toast(selection.message)
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

    /**
     * 下载到手机缓存，再让用户选择「保存到…」或「分享」：
     * 保存走 SAF，目标位置由用户决定；分享作为备选，仍走 FileProvider。
     */
    private fun download(file: DeviceFile) {
        lifecycleScope.launch {
            val progress = showTransferProgress("下载 ${file.name}")
            try {
                val bytes = repository().downloadBase64(
                    path = file.path,
                    isCancelled = { progress.cancelled }
                ) { done, total -> progress.update(done, total) }
                progress.dismiss()
                offerDownload(file, bytes)
            } catch (error: Exception) {
                toast(error.message ?: "下载失败")
            } finally {
                progress.dismiss()
            }
        }
    }

    private fun offerDownload(file: DeviceFile, bytes: ByteArray) {
        MaterialAlertDialogBuilder(this)
            .setTitle(file.name)
            .setMessage("已下载 ${formatByteSize(bytes.size.toLong())}，可保存或分享。")
            .setNegativeButton("取消", null)
            .setNeutralButton("分享") { _, _ -> shareFile(file, bytes) }
            .setPositiveButton("保存到…") { _, _ ->
                pendingSaveBytes = bytes
                saveLauncher.launch(file.name)
            }
            .show()
    }

    private fun shareFile(file: DeviceFile, bytes: ByteArray) {
        lifecycleScope.launch {
            runCatching {
                val cache = File(cacheDir, file.name)
                withContext(Dispatchers.IO) { cache.writeBytes(bytes) }
                val intent = Intent(Intent.ACTION_SEND).setType("application/octet-stream")
                    .putExtra(
                        Intent.EXTRA_STREAM,
                        androidx.core.content.FileProvider.getUriForFile(
                            this@FilesActivity,
                            "$packageName.fileprovider",
                            cache
                        )
                    ).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(intent, "分享文件"))
            }.onFailure { toast(it.message ?: "分享失败") }
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 旋转/重建时保留目录与滚动位置；列表内容会在 onCreate 重新读取。
        outState.putString(KEY_PATH, path)
        val layout = findViewById<RecyclerView>(R.id.fileList).layoutManager as? LinearLayoutManager
        outState.putInt(KEY_SCROLL, layout?.findFirstVisibleItemPosition() ?: 0)
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
            private val icon = view.findViewById<ImageView>(R.id.fileIcon)
            private val name = view.findViewById<TextView>(R.id.fileName)
            private val meta = view.findViewById<TextView>(R.id.fileMeta)

            fun bind(row: FileRow) {
                when (row) {
                    FileRow.Parent -> {
                        icon.setImageResource(R.drawable.ic_folder_up)
                        name.text = ".."
                        meta.text = "返回上一层"
                    }
                    is FileRow.Entry -> {
                        val file = row.file
                        icon.setImageResource(if (file.directory) R.drawable.ic_folder else R.drawable.ic_file)
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

    private companion object {
        /** 文本预览最多展示的字符数；超出时提示下载完整文件。 */
        const val PREVIEW_CHARS = 8_000
        /** 旋转重建时保存当前目录与列表位置。 */
        const val KEY_PATH = "files_path"
        const val KEY_SCROLL = "files_scroll"
    }
}

/**
 * 设备端文件名校验：与 shell 参数拼接方式保持一致，拒绝会改变路径含义的输入。
 * 抽成纯函数，便于单元测试；中文、空格等正常文件名允许通过。
 */
internal fun validateFileName(name: String): String? = when {
    name.isBlank() -> "名称不能为空"
    name == "." || name == ".." -> "名称无效"
    name.contains('/') -> "名称不能包含 /"
    name.any { it.code < 0x20 } -> "名称包含控制字符"
    else -> null
}
