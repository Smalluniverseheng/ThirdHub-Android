package com.thirdhub.app

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/* 开屏页（与网页版/aiBeta 同一品牌页：呼吸 Logo + 渐变 slogan） */
@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var entered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val inner = findViewById<View>(R.id.splashInner)
        val logoCard = findViewById<View>(R.id.splashLogoCard)
        val slogan = findViewById<TextView>(R.id.splashSlogan)
        val brand = findViewById<TextView>(R.id.splashBrand)
        val version = findViewById<TextView>(R.id.splashVersion)
        version.text = "v" + BuildConfig.VERSION_NAME

        // slogan 渐变文字（与网页版 accent-grad 一致）
        slogan.post {
            val w = if (slogan.width > 0) slogan.width.toFloat() else 600f
            slogan.paint.shader = LinearGradient(
                0f, 0f, w, 0f,
                Color.parseColor("#818CF8"), Color.parseColor("#C4B5FD"),
                Shader.TileMode.CLAMP
            )
            slogan.invalidate()
        }

        // Logo 呼吸动画（splashBreath 2.6s）
        val sx = ObjectAnimator.ofFloat(logoCard, View.SCALE_X, 1f, 1.05f)
        val sy = ObjectAnimator.ofFloat(logoCard, View.SCALE_Y, 1f, 1.05f)
        sx.repeatCount = ObjectAnimator.INFINITE
        sy.repeatCount = ObjectAnimator.INFINITE
        sx.repeatMode = ObjectAnimator.REVERSE
        sy.repeatMode = ObjectAnimator.REVERSE
        val breath = AnimatorSet()
        breath.playTogether(sx, sy)
        breath.duration = 2600
        breath.interpolator = AccelerateDecelerateInterpolator()
        breath.start()

        // fadeUp 入场
        fadeUp(inner, 0)
        fadeUp(slogan, 300)
        fadeUp(brand, 550)
        fadeUp(version, 700)

        // 点击跳过
        findViewById<View>(android.R.id.content).setOnClickListener { enter() }

        // 2.2s 后进入主界面
        handler.postDelayed({ enter() }, 2200)
    }

    private fun fadeUp(v: View, delay: Long) {
        v.alpha = 0f
        v.translationY = 24f
        v.animate().alpha(1f).translationY(0f)
            .setDuration(500).setStartDelay(delay.toLong()).start()
    }

    private fun enter() {
        if (entered) return
        entered = true
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
