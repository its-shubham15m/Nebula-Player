package com.shubhamgupta.nebula_music

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
import android.view.WindowManager
import android.widget.FrameLayout
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
import com.shubhamgupta.nebula_music.fragments.AboutFragment
import com.shubhamgupta.nebula_music.fragments.EqualizerFragment
import com.shubhamgupta.nebula_music.fragments.FavoritesFragment
import com.shubhamgupta.nebula_music.fragments.HomePageFragment
import com.shubhamgupta.nebula_music.fragments.MiniPlayerFragment
import com.shubhamgupta.nebula_music.fragments.NowPlayingFragment
import com.shubhamgupta.nebula_music.fragments.PlaylistsFragment
import com.shubhamgupta.nebula_music.fragments.RecentFragment
import com.shubhamgupta.nebula_music.service.MusicService
import com.shubhamgupta.nebula_music.utils.PreferenceManager
import com.shubhamgupta.nebula_music.utils.SongUtils
import com.shubhamgupta.nebula_music.utils.ThemeManager
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
        arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
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
            // Permissions granted, continue with app initialization
            Log.d("MainActivity", "All permissions granted")
            initializeAppAfterPermissions()
        } else {
            // Permissions denied, show explanation and maybe close app
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

            // Update the MiniPlayer once the service is connected and musicService is available
            updateMiniPlayerVisibility()

            // Auto-refresh current fragment after service connection
            handler.postDelayed({
                forceRefreshCurrentFragment()
            }, 500)

            // Enhanced state restoration with better timing
            handler.postDelayed({
                musicService?.let { service ->
                    Log.d("MainActivity", "Triggering state restoration after service connection")

                    // Method 1: Send restore command
                    val restoreIntent = Intent(this@MainActivity, MusicService::class.java).apply {
                        action = "RESTORE_PLAYBACK"
                    }
                    startService(restoreIntent)

                    // Method 2: Direct restoration call with retry
                    lifecycleScope.launch {
                        // Wait a bit for service to be fully initialized
                        delay(800)

                        // Check if we need to restore
                        val savedState = PreferenceManager.loadPlaybackState(this@MainActivity)
                        if (savedState?.lastPlayedSongId != null) {
                            Log.d("MainActivity", "Saved state exists, triggering restoration")

                            if (service.getCurrentSong() == null) {
                                Log.d("MainActivity", "No current song, forcing state restoration")
                                service.triggerStateRestoration()
                            } else {
                                Log.d("MainActivity", "Current song already exists: ${service.getCurrentSong()?.title}")

                                // Force queue update broadcast
                                sendBroadcast(Intent("QUEUE_CHANGED"))
                                sendBroadcast(Intent("SONG_CHANGED"))
                            }
                        }
                    }
                }
            }, 300)

            // Handle pending intent after service is bound
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
                    // Force update any queue-related UI
                    updateMiniPlayerVisibility()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Store savedInstanceState for later use
        savedInstanceBundle = savedInstanceState

        // Apply saved theme BEFORE setting content view
        ThemeManager.applySavedTheme(this)

        // Set window background to prevent flash (safer check)
        if (window.attributes.windowAnimations == 0 || window.decorView.background == null) {
            window.setBackgroundDrawableResource(android.R.color.transparent)
        }

        // Set content view after theme is applied
        setContentView(R.layout.activity_main)

        // SYSTEM UI FIX: Update system UI colors immediately
        updateSystemUiColors()

        handler = Handler(Looper.getMainLooper())

        PreferenceManager.init(this)

        // Check permissions first
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        if (hasRequiredPermissions()) {
            Log.d("MainActivity", "Permissions already granted, initializing app")
            initializeAppAfterPermissions()
        } else {
            Log.d("MainActivity", "Permissions not granted, requesting permissions")

            // Check if we should show rationale
            if (shouldShowPermissionRationale()) {
                showPermissionExplanationDialog()
            } else {
                // Directly request permissions
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
        // Handle different permission models for different Android versions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ - READ_MEDIA_AUDIO
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else {
            // Android 11-12 - READ_EXTERNAL_STORAGE
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun showPermissionExplanationDialog() {
        if (isShowingPermissionDialog) {
            return // Prevent multiple dialogs
        }

        isShowingPermissionDialog = true

        val permissionMessage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            "Nebula Music needs access to your audio files to play music, create playlists, and manage your music library.\n\nThis permission allows the app to:\n• Browse and play your music\n• Create and manage playlists\n• Display album art and song information\n• Remember your playback preferences"
        } else {
            "Nebula Music needs access to your storage to play music, create playlists, and manage your music library.\n\nThis permission allows the app to:\n• Browse and play your music files\n• Create and manage playlists\n• Display album art and song information\n• Remember your playback preferences"
        }

        AlertDialog.Builder(this)
            .setTitle("Allow Access to Your Music")
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
            // Android 13+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_AUDIO)
            }

            // Android 13+ also needs notification permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            // Android 11-12
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
            "You've denied access to your audio files. Without this permission, Nebula Music cannot:\n\n• Play your music\n• Create playlists\n• Display your music library\n• Save your preferences\n\nYou can grant permission in Settings or continue with limited functionality."
        } else {
            "You've denied access to your storage. Without this permission, Nebula Music cannot:\n\n• Play your music files\n• Create playlists\n• Display your music library\n• Save your preferences\n\nYou can grant permission in Settings or continue with limited functionality."
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
        setupSidebarInsets() // NEW: Setup insets for the sidebar
        setupThemeFunctionality()
        setupBackPressHandler()
        setupDrawerListener()

        try {
            val sidebarVersionTextView = findViewById<TextView>(R.id.sidebar_app_version)
            sidebarVersionTextView?.text = "Version: ${getAppVersionName()}"
        } catch (e: Exception) {
            Log.w("MainActivity", "Could not find R.id.sidebar_app_version. Skipping sidebar version set. Please add this TextView to your sidebar layout.")
            // This catch block is to prevent crashes if the ID doesn't exist.
        }

        // Use the stored savedInstanceState
        if (savedInstanceBundle == null) {
            showHomePageFragment()
        }

        // Initial MiniPlayer setup
        if (supportFragmentManager.findFragmentById(R.id.mini_player_container) == null) {
            supportFragmentManager.commit {
                replace(R.id.mini_player_container, MiniPlayerFragment.newInstance(), "MINI_PLAYER_TAG")
                setReorderingAllowed(true)
            }
        }

        // Check if we need to restore playback state
        val savedState = PreferenceManager.loadPlaybackState(this)
        if (savedState?.lastPlayedSongId != null && savedState.lastPlayedSongId != -1L) {
            Log.d("MainActivity", "Found saved playback state, starting service to restore")
            Intent(this, MusicService::class.java).also { intent ->
                startService(intent)
                bindService(intent, conn, Context.BIND_AUTO_CREATE)
            }
        } else {
            Log.d("MainActivity", "No saved playback state, starting service normally")
            Intent(this, MusicService::class.java).also { intent ->
                startService(intent)
                bindService(intent, conn, Context.BIND_AUTO_CREATE)
            }
        }

        // Register queue update receiver with proper flags for Android 14+
        val filter = IntentFilter().apply {
            addAction("QUEUE_CHANGED")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ requires explicit export flags
            registerReceiver(queueUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            // For older versions, use the old method
            @Suppress("DEPRECATION")
            registerReceiver(queueUpdateReceiver, filter)
        }
    }

    private fun setupMiniPlayerInsets() {
        val miniPlayerContainer = findViewById<View>(R.id.mini_player_container)
        // A base margin to have some space above the navigation bar. 16dp is a standard margin.
        val baseMarginBottom = (16 * resources.displayMetrics.density).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(drawerLayout) { _, insets ->
            val systemBarInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            // Apply the bottom inset and the base margin to the mini player container
            val params = miniPlayerContainer.layoutParams as FrameLayout.LayoutParams
            params.bottomMargin = baseMarginBottom + systemBarInsets.bottom
            miniPlayerContainer.layoutParams = params

            // Return the insets so that other views can also use them
            insets
        }
    }

    /**
     * Applies bottom padding to the sidebar footer to account for the system navigation bar.
     */
    private fun setupSidebarInsets() {
        val sidebar = findViewById<View>(R.id.sidebar)
        val sidebarFooterContainer = findViewById<View>(R.id.sidebar_footer_container)

        // This ensures the sidebar content is pushed up when the navigation bar is visible.
        ViewCompat.setOnApplyWindowInsetsListener(sidebar) { v, insets ->
            val systemBarInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            // The sidebar_footer_container has an existing padding of 16dp in XML,
            // we will add the system bottom inset to this padding.
            val originalPaddingDp = 16f
            val originalBottomPaddingPx = (originalPaddingDp * resources.displayMetrics.density).toInt()
            val newBottomPadding = originalBottomPaddingPx + systemBarInsets.bottom

            // Apply new padding to the sidebar footer container to lift the content
            sidebarFooterContainer.setPadding(
                sidebarFooterContainer.paddingLeft,
                sidebarFooterContainer.paddingTop,
                sidebarFooterContainer.paddingRight,
                newBottomPadding
            )

            // Return the insets so other views can consume them if necessary
            insets
        }
    }

    fun updateSystemUiColors() {
        val window = window
        val decorView = window.decorView

        // Force status bar and navigation bar to be transparent
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        // Enable edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val windowController = WindowCompat.getInsetsController(window, decorView)
        val currentTheme = ThemeManager.getCurrentTheme(this)

        when (currentTheme) {
            ThemeManager.THEME_LIGHT -> {
                // Light theme - dark icons on light background
                windowController.isAppearanceLightStatusBars = true
                windowController.isAppearanceLightNavigationBars = true
            }
            ThemeManager.THEME_DARK -> {
                // Dark theme - light icons on dark background
                windowController.isAppearanceLightStatusBars = false
                windowController.isAppearanceLightNavigationBars = false
            }
            ThemeManager.THEME_SYSTEM -> {
                // Follow system setting
                @Suppress("DEPRECATION")
                val isLightTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_NO
                windowController.isAppearanceLightStatusBars = isLightTheme
                windowController.isAppearanceLightNavigationBars = isLightTheme
            }
        }

        // Add this to ensure the colors are applied immediately
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        @Suppress("DEPRECATION")
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
    }

    @SuppressLint("SetTextI18n")
    private fun setupThemeFunctionality() {
        // Initialize theme views
        sidebarAppearance = findViewById(R.id.sidebar_appearance)
        sidebarThemeMode = findViewById(R.id.sidebar_theme_mode)
        themeModeOptions = findViewById(R.id.theme_mode_options)
        themeRadioGroup = findViewById(R.id.theme_radio_group)
        themeSystem = findViewById(R.id.theme_system)
        themeLight = findViewById(R.id.theme_light)
        themeDark = findViewById(R.id.theme_dark)

        // Set current theme in UI
        val currentTheme = ThemeManager.getCurrentTheme(this)
        updateThemeUI(currentTheme)

        // Reduce radio button size
        reduceRadioButtonSize(themeSystem)
        reduceRadioButtonSize(themeLight)
        reduceRadioButtonSize(themeDark)

        // Setup appearance click listener
        sidebarAppearance.setOnClickListener {
            val isVisible = themeModeOptions.visibility == View.VISIBLE
            themeModeOptions.visibility = if (isVisible) View.GONE else View.VISIBLE
        }

        // Setup radio group listener
        themeRadioGroup.setOnCheckedChangeListener { group, checkedId ->
            when (checkedId) {
                R.id.theme_system -> {
                    ThemeManager.setTheme(this, ThemeManager.THEME_SYSTEM)
                    updateThemeUI(ThemeManager.THEME_SYSTEM)
                    themeModeOptions.visibility = View.GONE
                    applyThemeAndRecreate()
                }
                R.id.theme_light -> {
                    ThemeManager.setTheme(this, ThemeManager.THEME_LIGHT)
                    updateThemeUI(ThemeManager.THEME_LIGHT)
                    themeModeOptions.visibility = View.GONE
                    applyThemeAndRecreate()
                }
                R.id.theme_dark -> {
                    ThemeManager.setTheme(this, ThemeManager.THEME_DARK)
                    updateThemeUI(ThemeManager.THEME_DARK)
                    themeModeOptions.visibility = View.GONE
                    applyThemeAndRecreate()
                }
            }
        }

        // Setup other sidebar items
        findViewById<TextView>(R.id.sidebar_equalizer).setOnClickListener {
            showEqualizerPage()
            drawerLayout.closeDrawer(GravityCompat.START)
        }

        findViewById<TextView>(R.id.sidebar_settings).setOnClickListener {
            Toast.makeText(this, "Settings - Coming Soon", Toast.LENGTH_SHORT).show()
            drawerLayout.closeDrawer(GravityCompat.START)
        }

        findViewById<TextView>(R.id.sidebar_about).setOnClickListener {
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
        // Apply the theme immediately
        ThemeManager.applySavedTheme(this)
        updateSystemUiColors()

        // Recreate activity to apply theme changes
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

    fun setDrawerOpen(isOpen: Boolean) {
        val homeFragment = supportFragmentManager.findFragmentByTag("HOME_PAGE_FRAGMENT") as? HomePageFragment
        homeFragment?.setDrawerOpen(isOpen)
    }

    private fun setupDrawerListener() {
        drawerLayout.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                // Optional: Add any behavior when drawer slides
            }

            override fun onDrawerOpened(drawerView: View) {
                // Add a touch interceptor view to block all touches
                addTouchInterceptor()

                // Notify the fragment to disable scrolling
                setDrawerOpen(true)
            }

            override fun onDrawerClosed(drawerView: View) {
                // Remove the touch interceptor
                removeTouchInterceptor()

                // Notify the fragment to enable scrolling
                setDrawerOpen(false)

                // Hide theme options when drawer closes
                themeModeOptions.visibility = View.GONE
            }

            override fun onDrawerStateChanged(newState: Int) {
                // Optional: Add any behavior when drawer state changes
            }
        })
    }

    fun setDrawerLocked(locked: Boolean) {
        if (locked) {
            drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        } else {
            drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
        }
    }

    private fun addTouchInterceptor() {
        val mainContentContainer = findViewById<View>(R.id.main_content_container)
        val touchInterceptor = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            id = R.id.touch_interceptor
            setOnTouchListener { _, _ -> true } // Block all touches
            isClickable = true
            isFocusable = true
        }

        // Add the interceptor to the main content container
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

    fun showEqualizerPage() {
        if (currentFragment == "equalizer" || isTransitioning) return
        isTransitioning = true

        currentFragment = "equalizer"
        // Remove manual drawer locking - handled in fragment now

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
        // Remove manual drawer locking - handled in fragment now

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

        // Try to get from cache first
        val homeFragment = fragmentCache["home"] as? HomePageFragment ?: HomePageFragment.newInstance()

        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.fragment_container, homeFragment, "HOME_PAGE_FRAGMENT")
        }

        // Cache the fragment
        fragmentCache["home"] = homeFragment

        updateMiniPlayerVisibility()

        handler.postDelayed({
            isTransitioning = false
        }, 300)
    }

    fun showFavoritesPage() {
        if (currentFragment == "favorites" || isTransitioning) return
        isTransitioning = true

        currentFragment = "favorites"
        // Remove manual drawer locking - handled in fragment now

        supportFragmentManager.commit {
            setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left)
            replace(R.id.fragment_container, FavoritesFragment(), "favorites_page")
            setReorderingAllowed(true)
            addToBackStack("favorites_page")
        }
        drawerLayout.closeDrawer(GravityCompat.START)

        handler.postDelayed({
            updateMiniPlayerVisibility()
            isTransitioning = false
        }, 300)
    }

    fun showPlaylistsPage() {
        if (currentFragment == "playlists" || isTransitioning) return
        isTransitioning = true

        currentFragment = "playlists"
        // Remove manual drawer locking - handled in fragment now

        supportFragmentManager.commit {
            setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left)
            replace(R.id.fragment_container, PlaylistsFragment(), "playlists_page")
            setReorderingAllowed(true)
            addToBackStack("playlists_page")
        }
        drawerLayout.closeDrawer(GravityCompat.START)

        handler.postDelayed({
            updateMiniPlayerVisibility()
            isTransitioning = false
        }, 300)
    }

    fun showRecentPage() {
        if (currentFragment == "recent" || isTransitioning) return
        isTransitioning = true

        currentFragment = "recent"
        // Remove manual drawer locking - handled in fragment now

        supportFragmentManager.commit {
            setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left)
            replace(R.id.fragment_container, RecentFragment(), "recent_page")
            setReorderingAllowed(true)
            addToBackStack("recent_page")
        }
        drawerLayout.closeDrawer(GravityCompat.START)

        handler.postDelayed({
            updateMiniPlayerVisibility()
            isTransitioning = false
        }, 300)
    }

    fun getMusicService(): MusicService? = musicService

    fun updateMiniPlayerVisibility() {
        val shouldBeVisible = currentFragment == "home" || currentFragment == "favorites" ||
                currentFragment == "playlists" || currentFragment == "recent" ||
                currentFragment == "equalizer" || currentFragment == "about"

        Log.d("MainActivity", "updateMiniPlayerVisibility: currentFragment=$currentFragment, shouldBeVisible=$shouldBeVisible")

        val miniPlayerContainer = findViewById<View>(R.id.mini_player_container)
        if (shouldBeVisible) {
            miniPlayerContainer.visibility = View.VISIBLE
            // Ensure the MiniPlayer fragment is properly attached
            if (supportFragmentManager.findFragmentById(R.id.mini_player_container) == null) {
                supportFragmentManager.commit {
                    replace(R.id.mini_player_container, MiniPlayerFragment.newInstance(), "MINI_PLAYER_TAG")
                    setReorderingAllowed(true)
                }
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

        supportFragmentManager.commit {
            setCustomAnimations(R.anim.slide_up, R.anim.slide_down)
            replace(R.id.fragment_container, NowPlayingFragment.newInstance(), "NOW_PLAYING_FRAGMENT")
            setReorderingAllowed(true)
            addToBackStack("now_playing")
        }

        handler.postDelayed({
            updateMiniPlayerVisibility()
            isTransitioning = false
        }, 300)
    }

    fun navigateToNowPlaying() {
        Log.d("MainActivity", "navigateToNowPlaying called")
        val currentSong = musicService?.getCurrentSong()

        if (currentSong != null) {
            Log.d("MainActivity", "Current song exists, showing NowPlaying: ${currentSong.title}")
            showNowPlayingPage()
        } else {
            val miniPlayer = supportFragmentManager.findFragmentByTag("MINI_PLAYER_TAG") as? MiniPlayerFragment
            if (miniPlayer?.isResumableState() == true) {
                Log.d("MainActivity", "MiniPlayer is in resumable state, showing NowPlaying")
                showNowPlayingPage()
            } else {
                Log.d("MainActivity", "No song available to play")
                Toast.makeText(this, "No song is currently playing", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleBackPressed() {
        Log.d("MainActivity", "handleBackPressed - currentFragment: $currentFragment, backStackCount: ${supportFragmentManager.backStackEntryCount}")

        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
            return
        }

        // If we're not on home page and there are fragments in back stack, pop them
        if (supportFragmentManager.backStackEntryCount > 0) {
            // Pop the back stack and update current fragment state
            supportFragmentManager.popBackStack()

            // Update current fragment based on what's showing
            updateCurrentFragmentAfterBackPress()

            updateMiniPlayerVisibility()
        } else {
            // We're on home page - implement double tap to exit
            if (backPressCount == 1) {
                // Second back press - exit the app
                finish()
            } else {
                // First back press - show toast message
                backPressCount++
                Toast.makeText(this, "Press back again to exit", Toast.LENGTH_SHORT).show()

                // Reset back press count after 2 seconds
                backPressHandler.postDelayed(backPressRunnable, 2000)
            }
        }
    }

    private fun updateCurrentFragmentAfterBackPress() {
        when {
            supportFragmentManager.findFragmentByTag("HOME_PAGE_FRAGMENT") != null -> {
                currentFragment = "home"
                drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
            }
            supportFragmentManager.findFragmentByTag("favorites_page") != null -> currentFragment = "favorites"
            supportFragmentManager.findFragmentByTag("playlists_page") != null -> currentFragment = "playlists"
            supportFragmentManager.findFragmentByTag("recent_page") != null -> currentFragment = "recent"
            supportFragmentManager.findFragmentByTag("equalizer_page") != null -> currentFragment = "equalizer"
            supportFragmentManager.findFragmentByTag("about_page") != null -> currentFragment = "about"
            supportFragmentManager.findFragmentByTag("NOW_PLAYING_FRAGMENT") != null -> currentFragment = "now_playing"
            else -> {
                currentFragment = "home"
                drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
            }
        }

        Log.d("MainActivity", "Updated current fragment to: $currentFragment")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        this.intent = intent
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val uri: Uri? = intent.data
            if (uri != null) {
                if (bound && musicService != null) {
                    playExternalUri(uri)
                    handler.postDelayed({
                        forceRefreshCurrentFragment()
                    }, 1000)
                }
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
        when (currentFragment) {
            "home" -> {
                val homeFragment = supportFragmentManager.findFragmentByTag("HOME_PAGE_FRAGMENT") as? HomePageFragment
                homeFragment?.refreshData()
            }
            "favorites" -> {
                val favoritesFragment = supportFragmentManager.findFragmentByTag("favorites_page") as? FavoritesFragment
                favoritesFragment?.refreshData()
            }
            "playlists" -> {
                val playlistsFragment = supportFragmentManager.findFragmentByTag("playlists_page") as? PlaylistsFragment
                playlistsFragment?.refreshData()
            }
            "recent" -> {
                val recentFragment = supportFragmentManager.findFragmentByTag("recent_page") as? RecentFragment
                recentFragment?.refreshData()
            }
            "about" -> {
                // About fragment doesn't need refresh data
            }
        }
    }

    @SuppressLint("UnsafeImplicitIntentLaunch")
    override fun onResume() {
        super.onResume()
        Log.d("MainActivity", "onResume - Auto-refreshing UI")

        // Reset back press counter
        backPressCount = 0

        // Check if we need to request permissions again (user might have come from settings)
        if (!hasRequiredPermissions() && !isShowingPermissionDialog) {
            Log.d("MainActivity", "Permissions not granted in onResume, checking if we should request again")
            // Don't auto-request here to avoid annoying the user
            // The app will continue with limited functionality
        }

        handler.postDelayed({
            forceRefreshCurrentFragment()
        }, 800)

        lifecycleScope.launch {
            delay(1200)

            musicService?.let { service ->
                val savedState = PreferenceManager.loadPlaybackState(this@MainActivity)

                if (savedState?.lastPlayedSongId != null) {
                    Log.d("MainActivity", "Saved state found in onResume: songId=${savedState.lastPlayedSongId}, queueSize=${savedState.queueSongIds.size}")

                    if (service.getCurrentSong() == null) {
                        Log.d("MainActivity", "No current song in onResume, triggering restoration")
                        service.triggerStateRestoration()

                        delay(500)
                        if (service.getCurrentSong() == null) {
                            Log.d("MainActivity", "Still no song after restoration, retrying")
                        }
                    } else {
                        Log.d("MainActivity", "Current song exists: ${service.getCurrentSong()?.title}")
                        sendBroadcast(Intent("QUEUE_CHANGED"))
                    }
                } else {
                    Log.d("MainActivity", "No saved state found in onResume")
                }
            } ?: run {
                Log.d("MainActivity", "MusicService not bound in onResume")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d("MainActivity", "onPause - Saving state")
        // Remove any pending back press runnables
        backPressHandler.removeCallbacks(backPressRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("MainActivity", "onDestroy - Cleaning up")

        // Remove back press handler
        backPressHandler.removeCallbacks(backPressRunnable)

        // Unbind service
        if (bound) {
            unbindService(conn)
            bound = false
        }

        // Unregister receiver
        try {
            unregisterReceiver(queueUpdateReceiver)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error unregistering receiver", e)
        }

        // Clear fragment cache
        fragmentCache.clear()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d("MainActivity", "Configuration changed - updating system UI colors")
        handler.postDelayed({
            updateSystemUiColors()
        }, 100)
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
            Log.e("MainActivity", "Failed to get package info", e)
            "v?" // Fallback
        }
    }

    enum class SortType {
        NAME_ASC,
        NAME_DESC,
        DATE_ADDED_ASC,
        DATE_ADDED_DESC,
        DURATION
    }
}