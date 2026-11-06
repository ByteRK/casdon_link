package com.example.casdonlink

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.casdonlink.connection.Diagnostics
import com.example.casdonlink.files.DeviceFile
import com.example.casdonlink.files.FileRepository
import com.example.casdonlink.files.PhoneHttpServer
import com.example.casdonlink.files.LocalAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class FilesActivity : AppCompatActivity() {
    private lateinit var repository: FileRepository
    private var path = "/"
    private var files = emptyList<DeviceFile>()
    private var activeHttpServer: PhoneHttpServer? = null
    private val picker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) upload(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_files)
        val session = DeviceSessionHolder.session() ?: run {
            Toast.makeText(this, "设备连接已断开", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        repository = FileRepository(session)
        findViewById<Button>(R.id.filesBackButton).setOnClickListener { navigateUp() }
        findViewById<Button>(R.id.uploadButton).setOnClickListener {
            picker.launch(arrayOf("*/*"))
        }
        findViewById<ListView>(R.id.fileList).setOnItemClickListener { _, _, position, _ ->
            val file = files.getOrNull(position) ?: return@setOnItemClickListener
            if (file.directory) load(file.path) else showFile(file)
        }
        findViewById<ListView>(R.id.fileList).setOnItemLongClickListener { _, _, position, _ ->
            files.getOrNull(position)?.let(::showActions); true
        }
        load(path)
    }

    private fun load(target: String) {
        lifecycleScope.launch {
            try {
                path = target
                findViewById<TextView>(R.id.filesPath).text = path
                setHint("读取中…")
                val result = repository.list(path)
                files = result
                findViewById<ListView>(R.id.fileList).adapter = FileAdapter()
                // 空目录和「命令没有输出」在界面上要能区分，否则只能看到一片空白。
                setHint(if (result.isEmpty()) "目录为空 · 点击重试" else "${result.size} 项 · 长按可操作")
            } catch (error: Exception) {
                files = emptyList()
                findViewById<ListView>(R.id.fileList).adapter = FileAdapter()
                val reason = error.message ?: error.javaClass.simpleName
                Diagnostics.log("列目录失败（$path）：$reason")
                setHint("读取失败：$reason\n点击重试")
                toast(reason)
            }
        }
    }

    /** 顶部提示条同时充当状态与重试入口。 */
    private fun setHint(text: String) {
        findViewById<TextView>(R.id.filesHint).apply {
            this.text = text
            setOnClickListener { load(path) }
        }
    }

    private fun navigateUp() {
        if (path == "/") { finish(); return }
        val parent = path.trimEnd('/').substringBeforeLast('/', "")
        load(parent.ifBlank { "/" })
    }

    private fun showFile(file: DeviceFile) {
        lifecycleScope.launch {
            try {
                val text = repository.readText(file.path)
                AlertDialog.Builder(this@FilesActivity).setTitle(file.name)
                    .setMessage(text.take(8_000)).setPositiveButton("关闭", null).show()
            } catch (_: Exception) {
                AlertDialog.Builder(this@FilesActivity).setTitle(file.name).setMessage("无法作为文本打开")
                    .setPositiveButton("下载", null).show()
            }
        }
    }

    private fun showActions(file: DeviceFile) {
        val actions = mutableListOf("重命名", "删除")
        if (!file.directory) actions += "下载"
        AlertDialog.Builder(this).setTitle(file.name).setItems(actions.toTypedArray()) { _, which ->
            when (actions[which]) {
                "重命名" -> rename(file)
                "删除" -> confirmDelete(file)
                "下载" -> download(file)
            }
        }.show()
    }

    private fun rename(file: DeviceFile) {
        val field = EditText(this).apply { setText(file.name) }
        AlertDialog.Builder(this).setTitle("重命名").setView(field).setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val newName = field.text.toString().trim()
                if (newName.isBlank() || newName.contains('/')) { toast("名称无效"); return@setPositiveButton }
                lifecycleScope.launch {
                    runCatching { repository.rename(file.path, "$path/$newName") }
                        .onSuccess { load(path) }.onFailure { toast(it.message ?: "重命名失败") }
                }
            }.show()
    }

    private fun confirmDelete(file: DeviceFile) {
        AlertDialog.Builder(this).setTitle("删除文件？").setMessage(file.path)
            .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    runCatching { repository.delete(file.path) }.onSuccess { load(path) }
                        .onFailure { toast(it.message ?: "删除失败") }
                }
            }.show()
    }

    private fun upload(uri: Uri) {
        lifecycleScope.launch {
            val temp = File(cacheDir, "upload_${System.currentTimeMillis()}")
            try {
                withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use(input::copyTo) } }
                val server = PhoneHttpServer(temp)
                activeHttpServer = server
                val host = LocalAddress.find(this@FilesActivity) ?: throw IllegalStateException("无法识别本机局域网地址")
                val url = server.start(host)
                repository.uploadFromHttp(url, "$path/${temp.nameWithoutExtension}")
                toast("传输完成")
                load(path)
            } catch (error: Exception) { toast(error.message ?: "上传失败") }
            finally { activeHttpServer?.close(); activeHttpServer = null; temp.delete() }
        }
    }

    private fun download(file: DeviceFile) {
        lifecycleScope.launch {
            try {
                val bytes = repository.downloadBase64(file.path)
                val cache = File(cacheDir, file.name)
                withContext(Dispatchers.IO) { cache.writeBytes(bytes) }
                val intent = Intent(Intent.ACTION_SEND).setType("application/octet-stream")
                    .putExtra(Intent.EXTRA_STREAM, androidx.core.content.FileProvider.getUriForFile(this@FilesActivity,
                        "$packageName.fileprovider", cache)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(intent, "保存文件"))
            } catch (error: Exception) { toast(error.message ?: "下载失败") }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        activeHttpServer?.close()
        super.onDestroy()
    }

    private inner class FileAdapter : BaseAdapter() {
        override fun getCount() = files.size
        override fun getItem(position: Int) = files[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@FilesActivity).inflate(R.layout.row_file, parent, false)
            val file = getItem(position)
            view.findViewById<TextView>(R.id.fileIcon).text = if (file.directory) "⌂" else "·"
            view.findViewById<TextView>(R.id.fileName).text = file.name
            view.findViewById<TextView>(R.id.fileMeta).text = if (file.directory) "文件夹" else "${file.size} B"
            return view
        }
    }
}
