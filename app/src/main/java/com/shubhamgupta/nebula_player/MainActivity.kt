package com.shubhamgupta.nebula_player

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import com.shubhamgupta.nebula_player.fragments.AboutFragment
import com.shubhamgupta.nebula_player.fragments.EqualizerFragment
import com.shubhamgupta.nebula_player.fragments.HomePageFragment
import com.shubhamgupta.nebula_player.fragments.MiniPlayerFragment
import com.shubhamgupta.nebula_player.fragments.NowPlayingFragment
import com.shubhamgupta.nebula_player.fragments.SearchFragment
import com.shubhamgupta.nebula_player.fragments.SettingsFragment
import com.shubhamgupta.nebula_player.service.MusicService
import com.shubhamgupta.nebula_player.utils.PreferenceManager
import com.shubhamgupta.nebula_player.utils.SongUtils
import com.shubhamgupta.nebula_player.utils.ThemeManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private var musicService: MusicService? = null
    private var bound = false
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var handler: Handler

    // Cache for fragment instances
    private val fragmentCache = mutableMapOf<String, Fragment>()

    // Track current fragment state
    private var currentFragment: String = "home"
    private var isTransitioning = false

    // Flag to control mini player from child fragments
    private var isMiniPlayerAllowed = true

    // Theme related views
    private lateinit var sidebarAppearance: View
    private lateinit var sidebarThemeMode: TextView
    private lateinit var themeModeOptions: View
    private lateinit var themeRadioGroup: RadioGroup
    private lateinit var themeSystem: RadioButton
    private lateinit var themeLight: RadioButton
    private lateinit var themeDark: RadioButton

    // Permission handling
    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    // Store savedInstanceState for later use
    private var savedInstanceBundle: Bundle? = null

    // Track if we're currently showing permission dialog
    private var isShowingPermissionDialog = false

    // Track back press behavior
    private var backPressCount = 0
    private val backPressHandler = Handler(Looper.getMainLooper())
    private val backPressRunnable = Runnable { backPressCount = 0 }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        isShowingPermissionDialog = false

        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Log.d("MainActivity", "All permissions granted")
            initializeAppAfterPermissions()
        } else {
            Log.d("MainActivity", "Some permissions denied: $permissions")
            showPermissionDeniedDialog()
        }
    }

    private val conn = object : ServiceConnection {
        @SuppressLint("UnsafeImplicitIntentLaunch")
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            bound = true

            Log.d("MainActivity", "Service connected, bound: $bound")

            updateMiniPlayerVisibility()

            handler.postDelayed({
                forceRefreshCurrentFragment()
            }, 500)

            handler.postDelayed({
                musicService?.let { service ->
                    Log.d("MainActivity", "Triggering state restoration after service connection")

                    val restoreIntent = Intent(this@MainActivity, MusicService::class.java).apply {
                        action = "RESTORE_PLAYBACK"
                    }
                    startService(restoreIntent)

                    lifecycleScope.launch {
                        delay(800)
                        val savedState = PreferenceManager.loadPlaybackState(this@MainActivity)
                        if (savedState?.lastPlayedSongId != null) {
                            if (service.getCurrentSong() == null) {
                                service.triggerStateRestoration()
                            } else {
                                sendBroadcast(Intent("QUEUE_CHANGED"))
                                sendBroadcast(Intent("SONG_CHANGED"))
                            }
                        }
                    }
                }
            }, 300)

            handleIntent(intent)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d("MainActivity", "Service disconnected")
            bound = false
            musicService = null
        }
    }

    private val queueUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "QUEUE_CHANGED" -> {
                    Log.d("MainActivity", "Queue changed broadcast received")
                    updateMiniPlayerVisibility()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceBundle = savedInstanceState
        ThemeManager.applySavedTheme(this)

        // FIX: Ensure full edge-to-edge configuration
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (window.attributes.windowAnimations == 0 || window.decorView.background == null) {
            window.setBackgroundDrawableResource(android.R.color.transparent)
        }

        setContentView(R.layout.activity_main)
        updateSystemUiColors()
        handler = Handler(Looper.getMainLooper())
        PreferenceManager.init(this)
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        if (hasRequiredPermissions()) {
            Log.d("MainActivity", "Permissions already granted, initializing app")
            initializeAppAfterPermissions()
        } else {
            Log.d("MainActivity", "Permissions not granted, requesting permissions")
            if (shouldShowPermissionRationale()) {
                showPermissionExplanationDialog()
            } else {
                requestSystemPermissions()
            }
        }
    }

    private fun shouldShowPermissionRationale(): Boolean {
        return requiredPermissions.any { permission ->
            ActivityCompat.shouldShowRequestPermissionRationale(this, permission)
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
            val videoGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
            return audioGranted && videoGranted
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun showPermissionExplanationDialog() {
        if (isShowingPermissionDialog) return

        isShowingPermissionDialog = true

        val permissionMessage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            "Nebula Music needs access to your audio and video files to play music, create playlists, show videos, and manage your library.\n\nThis permission allows the app to:\n• Browse and play your music and videos\n• Create and manage playlists\n• Display album art and song information\n• Remember your playback preferences"
        } else {
            "Nebula Music needs access to your storage to play music and videos, create playlists, and manage your library.\n\nThis permission allows the app to:\n• Browse and play your music files\n• Create and manage playlists\n• Display album art and song information\n• Remember your playback preferences"
        }

        AlertDialog.Builder(this)
            .setTitle("Allow Access to Media")
            .setMessage(permissionMessage)
            .setPositiveButton("Allow") { dialog, _ ->
                dialog.dismiss()
                isShowingPermissionDialog = false
                requestSystemPermissions()
            }
            .setNegativeButton("Deny") { dialog, _ ->
                dialog.dismiss()
                isShowingPermissionDialog = false
                showPermissionDeniedDialog()
            }
            .setCancelable(false)
            .setOnCancelListener {
                isShowingPermissionDialog = false
                showPermissionDeniedDialog()
            }
            .show()
    }

    private fun requestSystemPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            Log.d("MainActivity", "Requesting permissions: $permissionsToRequest")
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            Log.d("MainActivity", "All permissions already granted")
            initializeAppAfterPermissions()
        }
    }

    private fun showPermissionDeniedDialog() {
        val deniedMessage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            "You've denied access to your media files. Without this permission, Nebula Music cannot:\n\n• Play your music or videos\n• Create playlists\n• Display your library\n• Save your preferences\n\nYou can grant permission in Settings or continue with limited functionality."
        } else {
            "You've denied access to your storage. Without this permission, Nebula Music cannot:\n\n• Play your music or videos\n• Create playlists\n• Display your library\n• Save your preferences\n\nYou can grant permission in Settings or continue with limited functionality."
        }

        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage(deniedMessage)
            .setPositiveButton("Settings") { dialog, _ ->
                dialog.dismiss()
                openAppSettings()
            }
            .setNegativeButton("Continue Anyway") { dialog, _ ->
                dialog.dismiss()
                initializeAppWithLimitedFunctionality()
            }
            .setCancelable(false)
            .show()
    }

    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            finish()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error opening app settings", e)
            Toast.makeText(this, "Error opening settings", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun initializeAppWithLimitedFunctionality() {
        Log.d("MainActivity", "Initializing app with limited functionality")
        Toast.makeText(this, "Running with limited functionality", Toast.LENGTH_LONG).show()
        initializeAppAfterPermissions()
    }

    @SuppressLint("SetTextI18n")
    private fun initializeAppAfterPermissions() {
        Log.d("MainActivity", "Initializing app after permissions check")

        initializeViews()
        setupMiniPlayerInsets()
        setupSidebarInsets()
        setupThemeFunctionality()
        setupBackPressHandler()
        setupDrawerListener()

        try {
            val sidebarVersionTextView = findViewById<TextView>(R.id.sidebar_app_version)
            sidebarVersionTextView?.text = "Version: ${getAppVersionName()}"
        } catch (e: Exception) {
            Log.w("MainActivity", "Could not find R.id.sidebar_app_version.")
        }

        if (savedInstanceBundle == null) {
            showHomePageFragment()
        }

        if (supportFragmentManager.findFragmentById(R.id.mini_player_container) == null) {
            supportFragmentManager.commit {
                replace(R.id.mini_player_container, MiniPlayerFragment.newInstance(), "MINI_PLAYER_TAG")
                setReorderingAllowed(true)
            }
        }

        val savedState = PreferenceManager.loadPlaybackState(this)
        if (savedState?.lastPlayedSongId != null && savedState.lastPlayedSongId != -1L) {
            Intent(this, MusicService::class.java).also { intent ->
                startService(intent)
                bindService(intent, conn, Context.BIND_AUTO_CREATE)
            }
        } else {
            Intent(this, MusicService::class.java).also { intent ->
                startService(intent)
                bindService(intent, conn, Context.BIND_AUTO_CREATE)
            }
        }

        val filter = IntentFilter().apply {
            addAction("QUEUE_CHANGED")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(queueUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(queueUpdateReceiver, filter)
        }
    }

    private fun setupMiniPlayerInsets() {
        // Ensure insets are passed down correctly from the root.
        // Returning 'insets' allows propagation to children (like Fragments).
        ViewCompat.setOnApplyWindowInsetsListener(drawerLayout) { _, insets ->
            insets
        }
    }

    fun setMiniPlayerBottomMargin(bottomMarginPx: Int) {
        val miniPlayerContainer = findViewById<View>(R.id.mini_player_container) ?: return
        val params = miniPlayerContainer.layoutParams as? ViewGroup.MarginLayoutParams
        if (params != null) {
            if (params.bottomMargin != bottomMarginPx) {
                params.bottomMargin = bottomMarginPx
                miniPlayerContainer.layoutParams = params
                Log.d("MainActivity", "MiniPlayer margin updated to: $bottomMarginPx px")
            }
        }
    }

    private fun setupSidebarInsets() {
        val sidebar = findViewById<View>(R.id.sidebar)
        val sidebarFooterContainer = findViewById<View>(R.id.sidebar_footer_container)

        ViewCompat.setOnApplyWindowInsetsListener(sidebar) { v, insets ->
            val systemBarInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val originalPaddingDp = 16f
            val originalBottomPaddingPx = (originalPaddingDp * resources.displayMetrics.density).toInt()
            val newBottomPadding = originalBottomPaddingPx + systemBarInsets.bottom

            sidebarFooterContainer.setPadding(
                sidebarFooterContainer.paddingLeft,
                sidebarFooterContainer.paddingTop,
                sidebarFooterContainer.paddingRight,
                newBottomPadding
            )
            insets
        }
    }

    fun updateSystemUiColors() {
        val window = window
        val decorView = window.decorView

        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT

        // Ensure we are still in edge-to-edge mode
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val windowController = WindowCompat.getInsetsController(window, decorView)
        val currentTheme = ThemeManager.getCurrentTheme(this)

        when (currentTheme) {
            ThemeManager.THEME_LIGHT -> {
                windowController.isAppearanceLightStatusBars = true
                windowController.isAppearanceLightNavigationBars = true
            }
            ThemeManager.THEME_DARK -> {
                windowController.isAppearanceLightStatusBars = false
                windowController.isAppearanceLightNavigationBars = false
            }
            ThemeManager.THEME_SYSTEM -> {
                @Suppress("DEPRECATION")
                val isLightTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_NO
                windowController.isAppearanceLightStatusBars = isLightTheme
                windowController.isAppearanceLightNavigationBars = isLightTheme
            }
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        @Suppress("DEPRECATION")
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
    }

    @SuppressLint("SetTextI18n")
    private fun setupThemeFunctionality() {
        sidebarAppearance = findViewById(R.id.sidebar_appearance)
        sidebarThemeMode = findViewById(R.id.sidebar_theme_mode)
        themeModeOptions = findViewById(R.id.theme_mode_options)
        themeRadioGroup = findViewById(R.id.theme_radio_group)
        themeSystem = findViewById(R.id.theme_system)
        themeLight = findViewById(R.id.theme_light)
        themeDark = findViewById(R.id.theme_dark)

        // Find arrow icon for animation
        val sidebarThemeArrow = findViewById<ImageView>(R.id.sidebar_theme_arrow)

        val currentTheme = ThemeManager.getCurrentTheme(this)
        updateThemeUI(currentTheme)

        reduceRadioButtonSize(themeSystem)
        reduceRadioButtonSize(themeLight)
        reduceRadioButtonSize(themeDark)

        sidebarAppearance.setOnClickListener {
            val isVisible = themeModeOptions.visibility == View.VISIBLE
            if (isVisible) {
                // Collapse: Hide options and rotate arrow back to closed state (-90 degrees)
                themeModeOptions.visibility = View.GONE
                sidebarThemeArrow?.animate()?.rotation(-90f)?.setDuration(200)?.start()
            } else {
                // Expand: Show options and rotate arrow to open state (0 degrees)
                themeModeOptions.visibility = View.VISIBLE
                sidebarThemeArrow?.animate()?.rotation(90f)?.setDuration(200)?.start()
            }
        }

        themeRadioGroup.setOnCheckedChangeListener { group, checkedId ->
            when (checkedId) {
                R.id.theme_system -> {
                    ThemeManager.setTheme(this, ThemeManager.THEME_SYSTEM)
                    updateThemeUI(ThemeManager.THEME_SYSTEM)
                    // No longer auto-hiding to allow user to see selection change
                    applyThemeAndRecreate()
                }
                R.id.theme_light -> {
                    ThemeManager.setTheme(this, ThemeManager.THEME_LIGHT)
                    updateThemeUI(ThemeManager.THEME_LIGHT)
                    applyThemeAndRecreate()
                }
                R.id.theme_dark -> {
                    ThemeManager.setTheme(this, ThemeManager.THEME_DARK)
                    updateThemeUI(ThemeManager.THEME_DARK)
                    applyThemeAndRecreate()
                }
            }
        }

        findViewById<View>(R.id.sidebar_equalizer).setOnClickListener {
            showEqualizerPage()
            drawerLayout.closeDrawer(GravityCompat.START)
        }

        findViewById<View>(R.id.sidebar_settings).setOnClickListener {
            showSettingsPage()
            drawerLayout.closeDrawer(GravityCompat.START)
        }

        findViewById<View>(R.id.sidebar_about).setOnClickListener {
            showAboutPage()
            drawerLayout.closeDrawer(GravityCompat.START)
        }
    }

    private fun reduceRadioButtonSize(radioButton: RadioButton) {
        radioButton.scaleX = 0.7f
        radioButton.scaleY = 0.7f
        radioButton.pivotX = 0f
    }

    private fun applyThemeAndRecreate() {
        ThemeManager.applySavedTheme(this)
        updateSystemUiColors()
        recreate()
    }

    @SuppressLint("SetTextI18n")
    private fun updateThemeUI(theme: Int) {
        sidebarThemeMode.text = "Theme Mode: ${ThemeManager.getThemeName(theme)}"
        when (theme) {
            ThemeManager.THEME_SYSTEM -> themeSystem.isChecked = true
            ThemeManager.THEME_LIGHT -> themeLight.isChecked = true
            ThemeManager.THEME_DARK -> themeDark.isChecked = true
        }
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPressed()
            }
        })
    }

    private fun initializeViews() {
        drawerLayout = findViewById(R.id.drawer_layout)
    }

    fun openDrawer() {
        drawerLayout.openDrawer(GravityCompat.START)
    }

    fun isDrawerLocked(): Boolean {
        return drawerLayout.getDrawerLockMode(GravityCompat.START) != DrawerLayout.LOCK_MODE_UNLOCKED
    }

    fun setDrawerLocked(locked: Boolean) {
        if (locked) {
            drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        } else {
            drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
        }
    }

    private fun setupDrawerListener() {
        drawerLayout.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {}

            override fun onDrawerOpened(drawerView: View) {
                addTouchInterceptor()
            }

            override fun onDrawerClosed(drawerView: View) {
                removeTouchInterceptor()
                themeModeOptions.visibility = View.GONE
                // Reset arrow on drawer close
                findViewById<ImageView>(R.id.sidebar_theme_arrow)?.rotation = -90f
            }

            override fun onDrawerStateChanged(newState: Int) {}
        })
    }

    private fun addTouchInterceptor() {
        val mainContentContainer = findViewById<View>(R.id.main_content_container)
        val touchInterceptor = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            id = R.id.touch_interceptor
            setOnTouchListener { _, _ -> true }
            isClickable = true
            isFocusable = true
        }
        if (mainContentContainer is FrameLayout) {
            mainContentContainer.addView(touchInterceptor)
        }
    }

    private fun removeTouchInterceptor() {
        val mainContentContainer = findViewById<View>(R.id.main_content_container)
        if (mainContentContainer is FrameLayout) {
            val touchInterceptor = mainContentContainer.findViewById<View>(R.id.touch_interceptor)
            if (touchInterceptor != null) {
                mainContentContainer.removeView(touchInterceptor)
            }
        }
    }

    fun showSearchPage() {
        val homeFragment = supportFragmentManager.findFragmentByTag("HOME_PAGE_FRAGMENT") as? HomePageFragment
        if (homeFragment != null && homeFragment.isVisible) {
            homeFragment.switchToTab(R.id.nav_search)
        } else {
            if (currentFragment == "search" || isTransitioning) return
            isTransitioning = true
            currentFragment = "search"

            supportFragmentManager.commit {
                setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left)
                replace(R.id.fragment_container, SearchFragment(), "search_page")
                setReorderingAllowed(true)
                addToBackStack("search_page")
            }

            handler.postDelayed({
                updateMiniPlayerVisibility()
                isTransitioning = false
            }, 300)
        }
    }

    fun showSettingsPage() {
        if (currentFragment == "settings" || isTransitioning) return
        isTransitioning = true

        currentFragment = "settings"
        // Hide mini player for Settings
        isMiniPlayerAllowed = false
        setMiniPlayerBottomMargin(0)

        supportFragmentManager.commit {
            setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left)
            replace(R.id.fragment_container, SettingsFragment(), "settings_page")
            setReorderingAllowed(true)
            addToBackStack("settings_page")
        }
        drawerLayout.closeDrawer(GravityCompat.START)

        handler.postDelayed({
            updateMiniPlayerVisibility()
            isTransitioning = false
        }, 300)
    }

    fun showEqualizerPage() {
        if (currentFragment == "equalizer" || isTransitioning) return
        isTransitioning = true

        currentFragment = "equalizer"
        // Hide mini player for Equalizer
        isMiniPlayerAllowed = false
        setMiniPlayerBottomMargin(0)

        supportFragmentManager.commit {
            setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left)
            replace(R.id.fragment_container, EqualizerFragment(), "equalizer_page")
            setReorderingAllowed(true)
            addToBackStack("equalizer_page")
        }
        drawerLayout.closeDrawer(GravityCompat.START)

        handler.postDelayed({
            updateMiniPlayerVisibility()
            isTransitioning = false
        }, 300)
    }

    fun showAboutPage() {
        if (currentFragment == "about" || isTransitioning) return
        isTransitioning = true

        currentFragment = "about"
        // Hide mini player for About
        isMiniPlayerAllowed = false
        setMiniPlayerBottomMargin(0)

        supportFragmentManager.commit {
            setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left)
            replace(R.id.fragment_container, AboutFragment(), "about_page")
            setReorderingAllowed(true)
            addToBackStack("about_page")
        }
        drawerLayout.closeDrawer(GravityCompat.START)

        handler.postDelayed({
            updateMiniPlayerVisibility()
            isTransitioning = false
        }, 300)
    }

    private fun showHomePageFragment() {
        if (isTransitioning) return
        isTransitioning = true

        currentFragment = "home"
        isMiniPlayerAllowed = true

        val homeFragment = fragmentCache["home"] as? HomePageFragment ?: HomePageFragment.newInstance()

        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.fragment_container, homeFragment, "HOME_PAGE_FRAGMENT")
        }

        fragmentCache["home"] = homeFragment
        updateMiniPlayerVisibility()

        handler.postDelayed({
            isTransitioning = false
        }, 300)
    }

    fun showFavoritesPage() {
        val homeFragment = supportFragmentManager.findFragmentByTag("HOME_PAGE_FRAGMENT") as? HomePageFragment

        if (homeFragment != null && homeFragment.isVisible) {
            homeFragment.navigateToFavorites()
        } else {
            showHomePageFragment()
            handler.postDelayed({
                (supportFragmentManager.findFragmentByTag("HOME_PAGE_FRAGMENT") as? HomePageFragment)?.navigateToFavorites()
            }, 100)
        }
        drawerLayout.closeDrawer(GravityCompat.START)
    }

    fun showPlaylistsPage() {
        val homeFragment = supportFragmentManager.findFragmentByTag("HOME_PAGE_FRAGMENT") as? HomePageFragment

        if (homeFragment != null && homeFragment.isVisible) {
            homeFragment.navigateToPlaylists()
        } else {
            showHomePageFragment()
            handler.postDelayed({
                (supportFragmentManager.findFragmentByTag("HOME_PAGE_FRAGMENT") as? HomePageFragment)?.navigateToPlaylists()
            }, 100)
        }
        drawerLayout.closeDrawer(GravityCompat.START)
    }

    fun showRecentPage() {
        val homeFragment = supportFragmentManager.findFragmentByTag("HOME_PAGE_FRAGMENT") as? HomePageFragment

        if (homeFragment != null && homeFragment.isVisible) {
            homeFragment.navigateToRecents()
        } else {
            showHomePageFragment()
            handler.postDelayed({
                (supportFragmentManager.findFragmentByTag("HOME_PAGE_FRAGMENT") as? HomePageFragment)?.navigateToRecents()
            }, 100)
        }
        drawerLayout.closeDrawer(GravityCompat.START)
    }

    fun getMusicService(): MusicService? = musicService

    fun setMiniPlayerVisibility(visible: Boolean) {
        isMiniPlayerAllowed = visible
        updateMiniPlayerVisibility()
    }

    fun updateMiniPlayerVisibility() {
        val miniPlayerContainer = findViewById<View>(R.id.mini_player_container) ?: return

        if (!isMiniPlayerAllowed) {
            miniPlayerContainer.visibility = View.GONE
            return
        }

        // Explicitly checking against fragments where we DON'T want it
        val isSettingsOrAboutOrEqualizer = currentFragment == "settings" ||
                currentFragment == "about" ||
                currentFragment == "equalizer"

        val shouldBeVisible = !isSettingsOrAboutOrEqualizer && currentFragment != "now_playing"

        Log.d("MainActivity", "updateMiniPlayerVisibility: currentFragment=$currentFragment, shouldBeVisible=$shouldBeVisible")

        if (shouldBeVisible) {
            miniPlayerContainer.visibility = View.VISIBLE
            if (supportFragmentManager.findFragmentById(R.id.mini_player_container) == null) {
                supportFragmentManager.commit {
                    replace(R.id.mini_player_container, MiniPlayerFragment.newInstance(), "MINI_PLAYER_TAG")
                    setReorderingAllowed(true)
                }
            }
            // Ensure margin is correct if we are on Home
            val homeFragment = supportFragmentManager.findFragmentByTag("HOME_PAGE_FRAGMENT") as? HomePageFragment
            if (homeFragment != null && homeFragment.isVisible) {
                homeFragment.updateMiniPlayerPosition()
            }
        } else {
            miniPlayerContainer.visibility = View.GONE
        }
    }

    fun showNowPlayingPage() {
        if (currentFragment == "now_playing" || isTransitioning) return
        isTransitioning = true

        currentFragment = "now_playing"
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)

        // Temporarily hide mini player for full screen player
        setMiniPlayerVisibility(false)

        supportFragmentManager.commit {
            setCustomAnimations(R.anim.slide_up, R.anim.slide_down)
            replace(R.id.fragment_container, NowPlayingFragment.newInstance(), "NOW_PLAYING_FRAGMENT")
            setReorderingAllowed(true)
            addToBackStack("now_playing")
        }

        handler.postDelayed({
            isTransitioning = false
        }, 300)
    }

    fun navigateToNowPlaying() {
        val currentSong = musicService?.getCurrentSong()
        if (currentSong != null) {
            showNowPlayingPage()
        } else {
            val miniPlayer = supportFragmentManager.findFragmentByTag("MINI_PLAYER_TAG") as? MiniPlayerFragment
            if (miniPlayer?.isResumableState() == true) {
                showNowPlayingPage()
            } else {
                Toast.makeText(this, "No song is currently playing", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
            return
        }

        val homeFragment = supportFragmentManager.findFragmentByTag("HOME_PAGE_FRAGMENT") as? HomePageFragment
        if (homeFragment != null && homeFragment.isVisible && homeFragment.handleBackPress()) {
            // HomePageFragment handled the back press (popped a child fragment)
            return
        }

        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
            updateCurrentFragmentAfterBackPress()
        } else {
            if (backPressCount == 1) {
                finish()
            } else {
                backPressCount++
                Toast.makeText(this, "Press back again to exit", Toast.LENGTH_SHORT).show()
                backPressHandler.postDelayed(backPressRunnable, 2000)
            }
        }
    }

    private fun updateCurrentFragmentAfterBackPress() {
        handler.postDelayed({
            when {
                supportFragmentManager.findFragmentByTag("HOME_PAGE_FRAGMENT")?.isVisible == true -> {
                    currentFragment = "home"
                    isMiniPlayerAllowed = true
                    drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
                    (supportFragmentManager.findFragmentByTag("HOME_PAGE_FRAGMENT") as? HomePageFragment)?.updateMiniPlayerPosition()
                }
                supportFragmentManager.findFragmentByTag("settings_page")?.isVisible == true -> {
                    currentFragment = "settings"
                    isMiniPlayerAllowed = false
                }
                supportFragmentManager.findFragmentByTag("about_page")?.isVisible == true -> {
                    currentFragment = "about"
                    isMiniPlayerAllowed = false
                }
                supportFragmentManager.findFragmentByTag("equalizer_page")?.isVisible == true -> {
                    currentFragment = "equalizer"
                    isMiniPlayerAllowed = false
                }
                supportFragmentManager.findFragmentByTag("NOW_PLAYING_FRAGMENT")?.isVisible == true -> {
                    currentFragment = "now_playing"
                    isMiniPlayerAllowed = false
                }
                else -> {
                    currentFragment = "home"
                    isMiniPlayerAllowed = true
                    drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
                }
            }
            updateMiniPlayerVisibility()
        }, 100)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        this.intent = intent
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val uri: Uri? = intent.data
            if (uri != null && bound && musicService != null) {
                playExternalUri(uri)
                handler.postDelayed({ forceRefreshCurrentFragment() }, 1000)
            }
        }
    }

    private fun playExternalUri(uri: Uri) {
        lifecycleScope.launch {
            val externalSong = SongUtils.createSongFromUri(this@MainActivity, uri)
            if (externalSong != null) {
                val songList = arrayListOf(externalSong)
                musicService?.startPlayback(songList, 0)
                showNowPlayingPage()
                Toast.makeText(this@MainActivity, "Playing: ${externalSong.title}", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@MainActivity, "Could not load selected audio file.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun forceRefreshCurrentFragment() {
        val homeFragment = supportFragmentManager.findFragmentByTag("HOME_PAGE_FRAGMENT") as? HomePageFragment
        homeFragment?.refreshData()
    }

    @SuppressLint("UnsafeImplicitIntentLaunch")
    override fun onResume() {
        super.onResume()
        backPressCount = 0
        handler.postDelayed({ forceRefreshCurrentFragment() }, 800)

        lifecycleScope.launch {
            delay(1200)
            musicService?.let { service ->
                val savedState = PreferenceManager.loadPlaybackState(this@MainActivity)
                if (savedState?.lastPlayedSongId != null) {
                    if (service.getCurrentSong() == null) {
                        service.triggerStateRestoration()
                    } else {
                        sendBroadcast(Intent("QUEUE_CHANGED"))
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        backPressHandler.removeCallbacks(backPressRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        backPressHandler.removeCallbacks(backPressRunnable)
        if (bound) {
            unbindService(conn)
            bound = false
        }
        try {
            unregisterReceiver(queueUpdateReceiver)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error unregistering receiver", e)
        }
        fragmentCache.clear()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.postDelayed({ updateSystemUiColors() }, 100)
    }

    @SuppressLint("SetTextI18n")
    fun getAppVersionName(): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            "v" + packageInfo.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            "v?"
        }
    }

    enum class SortType {
        NAME_ASC, NAME_DESC, DATE_ADDED_ASC, DATE_ADDED_DESC, DURATION
    }
}