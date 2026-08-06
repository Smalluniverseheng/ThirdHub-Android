package com.thirdhub.app.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.thirdhub.app.R
import com.thirdhub.app.data.Prefs
import com.thirdhub.app.data.Supabase
import com.thirdhub.app.data.SyncRepo
import com.thirdhub.app.reader.LocalBook
import com.thirdhub.app.reader.ReaderActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShelfFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var cloudItems: List<SyncRepo.ShelfItem> = emptyList()

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFile(uri)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_shelf, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.btnImport).setOnClickListener { picker.launch(arrayOf("*/*")) }
        view.findViewById<View>(R.id.btnSync).setOnClickListener { refresh() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    fun refresh() {
        val ctx = context ?: return
        val stateView = view?.findViewById<TextView>(R.id.txtSyncState) ?: return
        if (!Supabase.isLoggedIn()) {
            stateView.text = "未登录，仅显示本地书籍（登录后与网页版书架互通）"
            render(emptyList())
            return
        }
        stateView.text = "正在从云端同步书架…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try { SyncRepo.pullShelf() } catch (e: Exception) { e }
            }
            if (result is List<*>) {
                @Suppress("UNCHECKED_CAST")
                cloudItems = result as List<SyncRepo.ShelfItem>
                stateView.text = "已登录 ${Prefs.userEmail} · 云端书架 ${cloudItems.size} 项（与网页版实时互通）"
                render(cloudItems)
            } else {
                stateView.text = "同步失败：" + (result as Exception).message + "（仅显示本地书籍）"
                render(emptyList())
            }
        }
        // 同时展示本地书
        if (LocalBook.list(ctx).isEmpty() && !Supabase.isLoggedIn()) render(emptyList())
    }

    private fun importFile(uri: Uri) {
        val ctx = context ?: return
        val name = LocalBook.displayName(ctx, uri)
        val lower = name.lowercase()
        if (!lower.endsWith(".txt") && !lower.endsWith(".epub")) {
            Toast.makeText(ctx, "仅支持 TXT / EPUB 文件", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(ctx, "正在导入，请稍候…", Toast.LENGTH_SHORT).show()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val meta = LocalBook.import(ctx, uri, name)
                    // 同步到云端书架（网页版可见）
                    if (Supabase.isLoggedIn()) {
                        try {
                            SyncRepo.pushShelf(
                                SyncRepo.ShelfItem(
                                    id = "local:" + meta.id,
                                    sourceId = "local",
                                    type = "novel",
                                    title = meta.title,
                                    author = meta.author,
                                    bookUrl = meta.id,
                                    sourceName = "本地导入",
                                    addedAt = meta.addedAt,
                                )
                            )
                        } catch (_: Exception) {}
                    }
                    meta
                } catch (e: Exception) { e }
            }
            if (result is LocalBook.Meta) {
                Toast.makeText(ctx, "已导入《${result.title}》", Toast.LENGTH_LONG).show()
                refresh()
            } else {
                Toast.makeText(ctx, "导入失败：" + (result as Exception).message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun render(cloud: List<SyncRepo.ShelfItem>) {
        val v = view ?: return
        val ctx = context ?: return
        val box = v.findViewById<LinearLayout>(R.id.shelfList)
        box.removeAllViews()

        val locals = LocalBook.list(ctx)
        val localIds = locals.map { it.id }.toSet()

        if (cloud.isEmpty() && locals.isEmpty()) {
            val empty = TextView(ctx)
            empty.text = "书架空空如也\n\n点右上角「导入」添加本地书籍；网页版书架在登录后自动同步到这里。"
            empty.textSize = 13f
            empty.setLineSpacing(0f, 1.4f)
            box.addView(empty)
            return
        }

        // 云端条目（含网页版加入的书）
        for (item in cloud) {
            val row = layoutInflater.inflate(R.layout.item_book, box, false)
            row.findViewById<TextView>(R.id.txtBookTitle).text = item.title
            val isLocalHere = item.sourceId == "local" && localIds.contains(item.bookUrl)
            val sub = buildString {
                append(item.sourceName.ifEmpty { item.sourceId })
                if (item.type.isNotEmpty()) append(" · " + typeLabel(item.type))
                if (item.sourceId == "local" && !isLocalHere) append(" · 文件在其他设备")
            }
            row.findViewById<TextView>(R.id.txtBookSub).text = sub
            row.setOnClickListener { openCloudItem(item, isLocalHere) }
            row.setOnLongClickListener { confirmRemove(item); true }
            box.addView(row)
        }

        // 本地有但云端没有的（未登录或未同步）
        val cloudBookIds = cloud.filter { it.sourceId == "local" }.map { it.bookUrl }.toSet()
        for (b in locals) {
            if (cloudBookIds.contains(b.id)) continue
            val row = layoutInflater.inflate(R.layout.item_book, box, false)
            row.findViewById<TextView>(R.id.txtBookTitle).text = b.title
            row.findViewById<TextView>(R.id.txtBookSub).text = "本地导入 · ${b.chapterCount} 章"
            row.setOnClickListener {
                startActivity(Intent(ctx, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_BOOK_ID, b.id))
            }
            row.setOnLongClickListener {
                AlertDialog.Builder(ctx)
                    .setTitle("删除《${b.title}》？")
                    .setPositiveButton("删除") { _, _ ->
                        LocalBook.delete(ctx, b.id)
                        scope.launch(Dispatchers.IO) { SyncRepo.removeShelf("local:" + b.id) }
                        refresh()
                    }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
            box.addView(row)
        }
    }

    private fun openCloudItem(item: SyncRepo.ShelfItem, isLocalHere: Boolean) {
        val ctx = context ?: return
        if (isLocalHere) {
            startActivity(Intent(ctx, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_BOOK_ID, item.bookUrl))
            return
        }
        AlertDialog.Builder(ctx)
            .setTitle(item.title)
            .setMessage(
                if (item.sourceId == "local") "这是一本在其他设备导入的本地书籍，文件不在本机。"
                else "在线内容请通过网页版阅读（连接器在网页版沙箱中运行）。"
            )
            .setPositiveButton("打开网页版") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://smalluniverseheng.github.io/ThirdHub/")))
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun confirmRemove(item: SyncRepo.ShelfItem) {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle("移出书架《${item.title}》？")
            .setMessage("云端与网页版会同步移除。")
            .setPositiveButton("移出") { _, _ ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        try { SyncRepo.removeShelf(item.id) } catch (_: Exception) {}
                    }
                    refresh()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun typeLabel(t: String): String = when (t) {
        "novel" -> "小说"
        "comic" -> "漫画"
        "video" -> "影视"
        "audio" -> "听书"
        "music" -> "音乐"
        else -> t
    }
}
