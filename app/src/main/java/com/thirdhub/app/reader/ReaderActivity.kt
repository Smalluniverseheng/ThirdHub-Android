package com.thirdhub.app.reader

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.thirdhub.app.R
import com.thirdhub.app.data.Prefs
import com.thirdhub.app.data.SyncRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

class ReaderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
    }

    private val scope = CoroutineScope(Dispatchers.Main)

    private lateinit var bookId: String
    private lateinit var titles: List<String>
    private var chapterIndex = 0
    private var restoreOffset = 0

    private lateinit var scroll: ScrollView
    private lateinit var content: TextView
    private lateinit var chapterTitle: TextView

    /* 阅读主题：深色 / 浅色 / 护眼 */
    private val themes = arrayOf(
        intArrayOf(Color.rgb(16, 18, 22), Color.rgb(232, 234, 239)),
        intArrayOf(Color.rgb(250, 248, 245), Color.rgb(35, 36, 40)),
        intArrayOf(Color.rgb(238, 231, 213), Color.rgb(70, 60, 45)),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)

        bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: ""
        if (bookId.isEmpty()) { finish(); return }

        scroll = findViewById(R.id.readerScroll)
        content = findViewById(R.id.txtContent)
        chapterTitle = findViewById(R.id.txtChapterTitle)

        titles = LocalBook.chapterTitles(this, bookId)
        if (titles.isEmpty()) {
            Toast.makeText(this, "书籍数据不存在", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // 恢复进度（本地优先，云端补充）
        restoreProgress()

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnCatalog).setOnClickListener { showCatalog() }
        findViewById<Button>(R.id.btnPrev).setOnClickListener { gotoChapter(chapterIndex - 1) }
        findViewById<Button>(R.id.btnNext).setOnClickListener { gotoChapter(chapterIndex + 1) }
        findViewById<Button>(R.id.btnFontMinus).setOnClickListener { changeFont(-1f) }
        findViewById<Button>(R.id.btnFontPlus).setOnClickListener { changeFont(1f) }
        findViewById<Button>(R.id.btnTheme).setOnClickListener {
            Prefs.readerTheme = (Prefs.readerTheme + 1) % themes.size
            applyTheme()
        }

        content.textSize = Prefs.readerFont
        applyTheme()
        loadChapter(chapterIndex)
    }

    private fun restoreProgress() {
        val local = Prefs.progress(bookId)
        if (local.isNotEmpty()) {
            try {
                val o = JSONObject(local)
                chapterIndex = o.optInt("chapterIndex", 0).coerceIn(0, titles.size - 1)
                restoreOffset = o.optInt("offset", 0)
                return
            } catch (_: Exception) {}
        }
        // 云端进度（网页版阅读的进度）
        scope.launch(Dispatchers.IO) {
            val cloud = try { SyncRepo.pullProgress("local:$bookId") } catch (_: Exception) { null }
                ?: try { SyncRepo.pullProgress(bookId) } catch (_: Exception) { null }
            if (cloud != null) {
                val ci = cloud.optInt("chapterIndex", 0).coerceIn(0, titles.size - 1)
                launch(Dispatchers.Main) {
                    if (ci != chapterIndex) {
                        chapterIndex = ci
                        loadChapter(chapterIndex)
                    }
                }
            }
        }
    }

    private fun loadChapter(index: Int) {
        chapterIndex = index.coerceIn(0, titles.size - 1)
        chapterTitle.text = titles[chapterIndex]
        content.text = try {
            LocalBook.chapterText(this, bookId, chapterIndex)
        } catch (e: Exception) {
            "章节加载失败：" + e.message
        }
        scroll.post { scroll.scrollTo(0, if (restoreOffset > 0) restoreOffset else 0) }
        restoreOffset = 0
        saveProgress()
    }

    private fun gotoChapter(index: Int) {
        if (index < 0 || index >= titles.size) {
            Toast.makeText(this, if (index < 0) "已经是第一章" else "已经是最后一章", Toast.LENGTH_SHORT).show()
            return
        }
        loadChapter(index)
    }

    private fun showCatalog() {
        AlertDialog.Builder(this)
            .setTitle("目录（${titles.size}）")
            .setItems(titles.toTypedArray()) { _, which -> loadChapter(which) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun changeFont(delta: Float) {
        val size = (Prefs.readerFont + delta).coerceIn(13f, 28f)
        Prefs.readerFont = size
        content.textSize = size
    }

    private fun applyTheme() {
        val t = themes[Prefs.readerTheme.coerceIn(0, themes.size - 1)]
        findViewById<android.view.View>(android.R.id.content).setBackgroundColor(t[0])
        scroll.setBackgroundColor(t[0])
        content.setTextColor(t[1])
        chapterTitle.setTextColor(t[1])
    }

    private fun saveProgress() {
        val json = JSONObject()
            .put("chapterIndex", chapterIndex)
            .put("offset", scroll.scrollY)
            .put("ts", System.currentTimeMillis())
            .toString()
        Prefs.setProgress(bookId, json)
        scope.launch(Dispatchers.IO) {
            SyncRepo.pushProgress("local:$bookId", chapterIndex, scroll.scrollY)
        }
    }

    override fun onPause() {
        super.onPause()
        if (::bookId.isInitialized && bookId.isNotEmpty()) saveProgress()
    }
}
