package com.shubhamgupta.nebula_player

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.shubhamgupta.nebula_player.utils.ThemeManager

class LoadingActivity : AppCompatActivity() {

    private val loadingHandler = Handler(Looper.getMainLooper())
    private var dotCounter = 0
    private val dotUpdateRunnable = object : Runnable {
        override fun run() {
            updateLoadingDots()
            loadingHandler.postDelayed(this, 200) // Update every 200ms
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Apply theme before setting content view
        ThemeManager.applySavedTheme(this)

        // FIX: Set status bar and navigation bar colors explicitly
        setupSystemBars()

        setContentView(R.layout.activity_loading)

        // Start loading animation
        startLoadingAnimation()

        // Start dot animation
        loadingHandler.post(dotUpdateRunnable)

        // Simulate app loading and then start MainActivity
        loadingHandler.postDelayed({
            navigateToMainActivity()
        }, 2000) // Show loading for 2 seconds (adjust as needed)
    }

    private fun setupSystemBars() {
        // Make sure content doesn't draw behind system bars
        WindowCompat.setDecorFitsSystemWindows(window, true)

        // Set status bar color to match app background
        window.statusBarColor = getColor(R.color.colorBackground)

        // Set navigation bar color to match app background
        window.navigationBarColor = getColor(R.color.colorBackground)

        // Control status bar icons color based on theme
        val windowController = WindowCompat.getInsetsController(window, window.decorView)
        val currentTheme = ThemeManager.getCurrentTheme(this)

        when (currentTheme) {
            ThemeManager.THEME_LIGHT -> {
                // Light theme - dark icons
                windowController.isAppearanceLightStatusBars = true
                windowController.isAppearanceLightNavigationBars = true
            }
            ThemeManager.THEME_DARK -> {
                // Dark theme - light icons
                windowController.isAppearanceLightStatusBars = false
                windowController.isAppearanceLightNavigationBars = false
            }
            ThemeManager.THEME_SYSTEM -> {
                // Follow system setting
                val isLightTheme = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_NO
                windowController.isAppearanceLightStatusBars = isLightTheme
                windowController.isAppearanceLightNavigationBars = isLightTheme
            }
        }

        // Ensure the window draws system bar backgrounds
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
    }

    private fun startLoadingAnimation() {
        val appIcon = findViewById<ImageView>(R.id.app_icon)
        val appName = findViewById<TextView>(R.id.app_name)
        val loadingText = findViewById<TextView>(R.id.loading_text)

        // Set initial loading text to empty dots
        loadingText.text = ""

        // Fade in animation for app icon
        val iconAnimation = AnimationUtils.loadAnimation(this, R.anim.loading_icon_enter)
        appIcon.startAnimation(iconAnimation)

        // Slight delay for app name animation
        loadingHandler.postDelayed({
            val nameAnimation = AnimationUtils.loadAnimation(this, R.anim.loading_text_slide_up)
            appName.startAnimation(nameAnimation)
        }, 400)

        // Delay for loading text animation
        loadingHandler.postDelayed({
            val textAnimation = AnimationUtils.loadAnimation(this, R.anim.loading_text_slide_up)
            loadingText.startAnimation(textAnimation)
        }, 600)
    }

    private fun updateLoadingDots() {
        val loadingText = findViewById<TextView>(R.id.loading_text)
        dotCounter = (dotCounter + 1) % 4 // Cycle through 0, 1, 2, 3

        val dots = when (dotCounter) {
            0 -> ""
            1 -> "."
            2 -> ".."
            else -> "..."
        }

        loadingText.text = dots
    }

    private fun navigateToMainActivity() {
        // Stop dot animation
        loadingHandler.removeCallbacks(dotUpdateRunnable)

        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up handlers to prevent memory leaks
        loadingHandler.removeCallbacks(dotUpdateRunnable)
    }
}