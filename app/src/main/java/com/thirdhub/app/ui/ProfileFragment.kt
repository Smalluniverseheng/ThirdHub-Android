package com.thirdhub.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.thirdhub.app.BuildConfig
import com.thirdhub.app.R
import com.thirdhub.app.data.Levels
import com.thirdhub.app.data.Prefs
import com.thirdhub.app.data.Supabase
import com.thirdhub.app.data.SyncRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProfileFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_profile, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<Button>(R.id.btnSignIn).setOnClickListener { doSignIn(view) }
        view.findViewById<Button>(R.id.btnSignUp).setOnClickListener { doSignUp(view) }
        view.findViewById<Button>(R.id.btnSignOut).setOnClickListener {
            scope.launch(Dispatchers.IO) { Supabase.signOut() }
            refreshUi()
            toast("已退出登录")
        }
        view.findViewById<Button>(R.id.btnRedeem).setOnClickListener { doRedeem(view) }
        view.findViewById<Button>(R.id.btnCheckUpdate).setOnClickListener {
            val act = activity ?: return@setOnClickListener
            toast("正在检查更新…")
            com.thirdhub.app.util.UpdateChecker.checkAndPrompt(act, true)
        }
        view.findViewById<Button>(R.id.btnTheme).setOnClickListener { cycleTheme() }
        view.findViewById<TextView>(R.id.txtVersion).text =
            "ThirdHub Android v${BuildConfig.VERSION_NAME} · 与网页版数据库互通\n网页版：https://smalluniverseheng.github.io/ThirdHub/"
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    /* ---------- 登录 / 注册 ---------- */

    private fun doSignIn(view: View) {
        val email = view.findViewById<EditText>(R.id.inputEmail).text.toString().trim()
        val pwd = view.findViewById<EditText>(R.id.inputPassword).text.toString()
        if (email.isEmpty() || pwd.isEmpty()) { toast("请输入邮箱和密码"); return }
        toast("登录中…")
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                try { Supabase.signIn(email, pwd); null } catch (e: Exception) { e }
            }
            if (err == null) { toast("登录成功"); refreshUi() } else toast("登录失败：" + err.message)
        }
    }

    private fun doSignUp(view: View) {
        val email = view.findViewById<EditText>(R.id.inputEmail).text.toString().trim()
        val pwd = view.findViewById<EditText>(R.id.inputPassword).text.toString()
        if (email.isEmpty() || pwd.length < 6) { toast("请输入邮箱和至少 6 位密码"); return }
        toast("注册中…")
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try { Supabase.signUp(email, pwd) } catch (e: Exception) { e }
            }
            when (result) {
                is Boolean -> {
                    if (result) { toast("注册成功，已自动登录"); refreshUi() }
                    else toast("注册成功，请查收验证邮件后再登录")
                }
                is Exception -> toast("注册失败：" + result.message)
            }
        }
    }

    /* ---------- 用户卡片 ---------- */

    private fun refreshUi() {
        val v = view ?: return
        val loginBox = v.findViewById<View>(R.id.loginBox)
        val userBox = v.findViewById<View>(R.id.userBox)
        if (!Supabase.isLoggedIn()) {
            loginBox.visibility = View.VISIBLE
            userBox.visibility = View.GONE
            return
        }
        loginBox.visibility = View.GONE
        userBox.visibility = View.VISIBLE
        v.findViewById<TextView>(R.id.txtEmail).text = Prefs.userEmail
        v.findViewById<TextView>(R.id.txtNickname).text = Prefs.userEmail.substringBefore("@")
        v.findViewById<TextView>(R.id.txtLevel).text = "等级加载中…"

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try { SyncRepo.pullProfile() } catch (e: Exception) { e }
            }
            val vv = view ?: return@launch
            if (result is SyncRepo.Profile) {
                val lv = Levels.byId(result.level)
                vv.findViewById<TextView>(R.id.txtNickname).text =
                    result.nickname.ifEmpty { Prefs.userEmail.substringBefore("@") }
                val roleTag = if (result.role == "admin") " · 管理员" else ""
                vv.findViewById<TextView>(R.id.txtLevel).text = "会员等级：${lv.label}$roleTag"
                vv.findViewById<TextView>(R.id.txtStorage).text =
                    "存储空间：${Levels.fmt(result.storageUsed)} / ${Levels.fmt(lv.storageBytes)}（会员只卖存储，不卖 Token）"
            } else {
                vv.findViewById<TextView>(R.id.txtLevel).text = "会员等级：卫星"
                vv.findViewById<TextView>(R.id.txtStorage).text = ""
            }
        }
    }

    /* ---------- 卡密 ---------- */

    private fun doRedeem(view: View) {
        val card = view.findViewById<EditText>(R.id.inputCard).text.toString().trim().uppercase()
        if (!Regex("^TP(-[A-Z0-9]{8}){6}$").matches(card)) { toast("卡密格式不正确（TP- 开头共 50 位）"); return }
        toast("激活中…")
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try { SyncRepo.redeemCard(card) } catch (e: Exception) { e }
            }
            if (result is String) {
                toast(result)
                view.findViewById<EditText>(R.id.inputCard).setText("")
                refreshUi()
            } else {
                toast("激活失败：" + (result as Exception).message)
            }
        }
    }

    /* ---------- 主题 ---------- */

    private fun cycleTheme() {
        val next = when (Prefs.themeMode) {
            "auto" -> "light"
            "light" -> "dark"
            else -> "auto"
        }
        Prefs.themeMode = next
        when (next) {
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
        val label = if (next == "auto") "跟随系统" else if (next == "light") "浅色" else "深色"
        toast("主题：$label")
    }

    private fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }
}
