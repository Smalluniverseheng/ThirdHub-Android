package com.thirdhub.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.thirdhub.app.ui.AiFragment
import com.thirdhub.app.ui.CategoryFragment
import com.thirdhub.app.ui.DiscoverFragment
import com.thirdhub.app.ui.ProfileFragment
import com.thirdhub.app.ui.ShelfFragment

class MainActivity : AppCompatActivity() {

    private var currentId = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.setOnItemSelectedListener { item ->
            switchTab(item.itemId)
            true
        }
        switchTab(if (currentId != -1) currentId else R.id.nav_discover)
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
