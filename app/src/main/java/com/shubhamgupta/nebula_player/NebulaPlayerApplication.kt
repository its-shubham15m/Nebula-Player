package com.shubhamgupta.nebula_player

import android.app.Application
import androidx.media3.common.BuildConfig
import com.shubhamgupta.nebula_player.utils.DebugUtils
import com.shubhamgupta.nebula_player.utils.EqualizerManager
import com.shubhamgupta.nebula_player.utils.SongCacheManager
import com.shubhamgupta.nebula_player.utils.ThemeManager

class NebulaPlayerApplication : Application() {

    companion object {
        private var instance: NebulaPlayerApplication? = null
        fun getAppContext(): Application? = instance
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // This call now triggers the persistent cache logic. No changes needed.
        SongCacheManager.initializeCache(this)

        EqualizerManager.loadSettings()
        ThemeManager.applySavedTheme(this)
        DebugUtils.initialize(BuildConfig.DEBUG)
        DebugUtils.logInfo("Nebula Player Application started")
    }
}