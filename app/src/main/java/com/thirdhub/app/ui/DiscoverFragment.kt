package com.thirdhub.app.ui

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
import com.thirdhub.app.MainActivity
import com.thirdhub.app.R
import com.thirdhub.app.reader.LocalBook
import com.thirdhub.app.reader.ReaderActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DiscoverFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main)

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFile(uri)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_discover, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.btnImportLocal).setOnClickListener {
            picker.launch(arrayOf("*/*"))
        }
        view.findViewById<View>(R.id.btnGotoAi).setOnClickListener {
            (activity as? MainActivity)?.gotoTab(R.id.nav_ai)
        }
        view.findViewById<View>(R.id.btnOpenWeb).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://smalluniverseheng.github.io/ThirdHub/")))
        }
    }

    override fun onResume() {
        super.onResume()
        renderLocalBooks()
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
                try { LocalBook.import(ctx, uri, name) } catch (e: Exception) { e }
            }
            if (result is LocalBook.Meta) {
                Toast.makeText(ctx, "已导入《${result.title}》（${result.chapterCount} 章）", Toast.LENGTH_LONG).show()
                renderLocalBooks()
            } else {
                Toast.makeText(ctx, "导入失败：" + (result as Exception).message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun renderLocalBooks() {
        val v = view ?: return
        val ctx = context ?: return
        val box = v.findViewById<LinearLayout>(R.id.localBookList)
        box.removeAllViews()
        val books = LocalBook.list(ctx)
        if (books.isEmpty()) {
            val empty = TextView(ctx)
            empty.text = "还没有本地书籍，点击上方按钮导入 TXT / EPUB"
            empty.textSize = 13f
            box.addView(empty)
            return
        }
        for (b in books) {
            val item = layoutInflater.inflate(R.layout.item_book, box, false)
            item.findViewById<TextView>(R.id.txtBookTitle).text = b.title
            item.findViewById<TextView>(R.id.txtBookSub).text = "本地导入 · ${b.chapterCount} 章"
            item.setOnClickListener {
                startActivity(Intent(ctx, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_BOOK_ID, b.id))
            }
            box.addView(item)
        }
    }
}
