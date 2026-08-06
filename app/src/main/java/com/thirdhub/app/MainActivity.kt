package com.thirdhub.app

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.thirdhub.app.ui.AiFragment
import com.thirdhub.app.ui.CategoryFragment
import com.thirdhub.app.ui.DiscoverFragment
import com.thirdhub.app.ui.ProfileFragment
import com.thirdhub.app.ui.ShelfFragment
import com.thirdhub.app.util.UpdateChecker

class MainActivity : AppCompatActivity() {

    private var currentId = -1
    private val handler = Handler(Looper.getMainLooper())

    /* 后台下载完成 → 弹安装 */
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0L)
            UpdateChecker.onDownloadComplete(ctx, id)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.setOnItemSelectedListener { item ->
            switchTab(item.itemId)
            true
        }
        switchTab(if (currentId != -1) currentId else R.id.nav_discover)

        // 注册下载完成监听
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(downloadReceiver, filter)
        }

        // 启动后自动检查更新（有新版则弹公告，可后台下载）
        handler.postDelayed({ UpdateChecker.checkAndPrompt(this, false) }, 1800)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(downloadReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    fun gotoTab(id: Int) {
        findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = id
    }

    private fun switchTab(id: Int) {
        if (id == currentId) return
        val tx = supportFragmentManager.beginTransaction()
        if (currentId != -1) {
            supportFragmentManager.findFragmentByTag(tagOf(currentId))?.let { tx.hide(it) }
        }
        var f = supportFragmentManager.findFragmentByTag(tagOf(id))
        if (f == null) {
            f = createFragment(id)
            tx.add(R.id.fragmentContainer, f, tagOf(id))
        } else {
            tx.show(f)
        }
        tx.commit()
        currentId = id
    }

    private fun tagOf(id: Int): String = "tab_$id"

    private fun createFragment(id: Int): Fragment = when (id) {
        R.id.nav_ai -> AiFragment()
        R.id.nav_shelf -> ShelfFragment()
        R.id.nav_category -> CategoryFragment()
        R.id.nav_profile -> ProfileFragment()
        else -> DiscoverFragment()
    }
}
