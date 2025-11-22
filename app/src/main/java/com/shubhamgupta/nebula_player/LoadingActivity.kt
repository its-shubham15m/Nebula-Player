package com.shubhamgupta.nebula_player

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.imageview.ShapeableImageView
import android.view.animation.AnimationUtils
import android.widget.TextView
import com.shubhamgupta.nebula_player.utils.ThemeManager

class LoadingActivity : AppCompatActivity() {

    private val loadingHandler = Handler(Looper.getMainLooper())
    private lateinit var loadingText: TextView

    // dot animation state
    private var dotCounter = 0
    private val dotRunnable = object : Runnable {
        override fun run() {
            dotCounter = (dotCounter + 1) % 4
            val dots = when (dotCounter) {
                0 -> " "
                1 -> "."
                2 -> ".."
                else -> "..."
            }
            loadingText.text = dots
            // cycle every 450ms (subtle)
            loadingHandler.postDelayed(this, 450)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // apply saved theme (keeps consistency with About/sidebar)
        ThemeManager.applySavedTheme(this)
        setupSystemBars()

        setContentView(R.layout.activity_loading)

        // Views
        val icon = findViewById<ShapeableImageView>(R.id.app_icon)
        val appName = findViewById<TextView>(R.id.app_name)
        val subtitle = findViewById<TextView>(R.id.loading_subtitle)
        loadingText = findViewById(R.id.loading_text)

        // subtle entrance animations (reuse your existing anim resources)
        icon.alpha = 0f
        appName.alpha = 0f
        val iconAnim = AnimationUtils.loadAnimation(this, R.anim.loading_icon_enter)
        val textAnim = AnimationUtils.loadAnimation(this, R.anim.loading_text_slide_up)
        icon.startAnimation(iconAnim)
        icon.alpha = 1f

        loadingHandler.postDelayed({
            appName.startAnimation(textAnim)
            appName.alpha = 1f
        }, 220)

        loadingHandler.postDelayed({
            subtitle.startAnimation(textAnim)
        }, 400)

        // start dot animation
        loadingHandler.post(dotRunnable)

        // Short splash -> main (adjust to your startup needs)
        loadingHandler.postDelayed({
            navigateToMain()
        }, 1200L) // brief so dots display subtly before navigation
    }

    private fun setupSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = getColor(R.color.colorBackground)
        window.navigationBarColor = getColor(R.color.colorBackground)
        val wc = WindowCompat.getInsetsController(window, window.decorView)
        when (ThemeManager.getCurrentTheme(this)) {
            ThemeManager.THEME_LIGHT -> {
                wc.isAppearanceLightStatusBars = true
                wc.isAppearanceLightNavigationBars = true
            }
            ThemeManager.THEME_DARK -> {
                wc.isAppearanceLightStatusBars = false
                wc.isAppearanceLightNavigationBars = false
            }
            ThemeManager.THEME_SYSTEM -> {
                val isLight = (resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                        android.content.res.Configuration.UI_MODE_NIGHT_NO
                wc.isAppearanceLightStatusBars = isLight
                wc.isAppearanceLightNavigationBars = isLight
            }
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
    }

    private fun navigateToMain() {
        // stop dots before leaving
        loadingHandler.removeCallbacks(dotRunnable)
        val i = Intent(this, MainActivity::class.java)
        startActivity(i)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        loadingHandler.removeCallbacksAndMessages(null)
    }
}