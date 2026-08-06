package com.thirdhub.app.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.thirdhub.app.R
import com.thirdhub.app.data.Prefs
import com.thirdhub.app.data.Supabase
import com.thirdhub.app.data.SyncRepo
import com.thirdhub.app.reader.LocalBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CategoryFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_category, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.btnConnectorHelp).setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("关于连接器")
                .setMessage("ThirdHub 不内置任何内容源。\n\nJS 连接器在网页版的 Web Worker 沙箱中运行（搜索 / 目录 / 正文均由用户自行导入的连接器提供）。安卓版为保证安全与合规，当前仅支持本地书籍，在线内容请使用网页版。\n\n连接器开发文档见网页版仓库 docs/CONNECTOR.md。")
                .setPositiveButton("查看文档") { _, _ ->
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Smalluniverseheng/ThirdHub/blob/main/docs/CONNECTOR.md")))
                }
                .setNegativeButton("关闭", null)
                .show()
        }
        view.findViewById<View>(R.id.btnManageLocal).setOnClickListener { manageLocal() }
        view.findViewById<View>(R.id.btnClearCache).setOnClickListener {
            Prefs.aiHistory = "[]"
            Toast.makeText(context, "缓存已清理（AI 会话记录）", Toast.LENGTH_SHORT).show()
        }
        view.findViewById<View>(R.id.btnOpenWeb2).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://smalluniverseheng.github.io/ThirdHub/")))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshInfo()
    }

    private fun refreshInfo() {
        val v = view ?: return
        val ctx = context ?: return
        val info = v.findViewById<TextView>(R.id.txtSyncInfo)
        val locals = LocalBook.list(ctx)
        if (!Supabase.isLoggedIn()) {
            info.text = "未登录云端\n本地书籍：${locals.size} 本\n\n登录后书架 / 历史 / 收藏 / 阅读进度将与网页版实时互通。"
            return
        }
        info.text = "已登录 ${Prefs.userEmail}，正在统计云端数据…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try { SyncRepo.counts() } catch (e: Exception) { e }
            }
            if (result is Triple<*, *, *>) {
                @Suppress("UNCHECKED_CAST")
                val c = result as Triple<Int, Int, Int>
                info.text = "已登录 ${Prefs.userEmail}\n云端书架：${c.first} · 历史：${c.second} · 收藏：${c.third}\n本地书籍：${locals.size} 本\n\n数据与网页版 ThirdHub 实时互通。"
            } else {
                info.text = "已登录 ${Prefs.userEmail}\n云端统计失败：" + (result as Exception).message + "\n本地书籍：${locals.size} 本"
            }
        }
    }

    private fun manageLocal() {
        val ctx = context ?: return
        val books = LocalBook.list(ctx)
        if (books.isEmpty()) {
            Toast.makeText(ctx, "暂无本地书籍", Toast.LENGTH_SHORT).show()
            return
        }
        val names = books.map { it.title }.toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle("本地书籍管理（点击删除）")
            .setItems(names) { _, which ->
                val b = books[which]
                AlertDialog.Builder(ctx)
                    .setTitle("删除《${b.title}》？")
                    .setPositiveButton("删除") { _, _ ->
                        LocalBook.delete(ctx, b.id)
                        scope.launch(Dispatchers.IO) { SyncRepo.removeShelf("local:" + b.id) }
                        refreshInfo()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }
}
