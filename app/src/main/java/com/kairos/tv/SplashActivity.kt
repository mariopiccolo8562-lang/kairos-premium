package com.kairos.tv

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView

/** avvio: logo oro che compare con alone e riflesso, poi l'app */
class SplashActivity : Activity() {
    private var gone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isTaskRoot) { finish(); return } // app già aperta: torna dove eri
        setContentView(R.layout.activity_splash)
        val glow = findViewById<View>(R.id.glow)
        val logo = findViewById<View>(R.id.logoBox)
        val shine = findViewById<View>(R.id.shine)
        val brand = findViewById<TextView>(R.id.brand)
        val sub = findViewById<View>(R.id.sub)
        if (App.lite) {
            for (v in listOf(logo, brand, sub)) v.animate().alpha(1f).setDuration(300).start()
            logo.postDelayed({ next(250) }, 700)
            return
        }
        val dp = resources.displayMetrics.density
        glow.scaleX = 0.5f; glow.scaleY = 0.5f
        glow.animate().alpha(1f).scaleX(1.1f).scaleY(1.1f).setDuration(1100).setInterpolator(DecelerateInterpolator(1.5f)).start()
        logo.scaleX = 0.4f; logo.scaleY = 0.4f
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(150).setDuration(800).setInterpolator(OvershootInterpolator(1.3f)).withLayer().start()
        logo.postDelayed({
            if (gone) return@postDelayed
            shine.translationX = -90 * dp; shine.visibility = View.VISIBLE
            shine.animate().translationX(200 * dp).setDuration(700).setInterpolator(DecelerateInterpolator()).withEndAction { shine.visibility = View.INVISIBLE }.start()
        }, 950)
        brand.translationY = 18 * dp
        brand.animate().alpha(1f).translationY(0f).setStartDelay(650).setDuration(600).setInterpolator(DecelerateInterpolator(2f)).start()
        ValueAnimator.ofFloat(0.6f, 0.15f).apply {
            startDelay = 650; duration = 900; interpolator = DecelerateInterpolator(2f)
            addUpdateListener { brand.letterSpacing = it.animatedValue as Float }
            start()
        }
        sub.animate().alpha(1f).setStartDelay(1050).setDuration(500).start()
        glow.postDelayed({
            if (gone) return@postDelayed
            glow.animate().alpha(0.55f).scaleX(1f).scaleY(1f).setDuration(600).start()
        }, 1200)
        logo.postDelayed({ next(400) }, 2100)
    }

    private fun next(fade: Int) {
        if (gone) return
        gone = true
        startActivity(Intent(this, ActivationActivity::class.java))
        if (fade > 0) overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {}
}
