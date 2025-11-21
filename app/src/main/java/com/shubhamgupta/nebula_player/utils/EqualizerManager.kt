package com.shubhamgupta.nebula_player.utils

import android.media.audiofx.Equalizer
import android.util.Log
import com.google.gson.Gson
import com.shubhamgupta.nebula_player.NebulaPlayerApplication

object EqualizerManager {
    private var equalizer: Equalizer? = null
    private var currentAudioSessionId = 0
    private var isEnabled = true

    // Equalizer settings - store base levels without enhancements
    private var baseBandLevels = intArrayOf(0, 0, 0, 0, 1) // Default levels (as per your image)
    private var currentPreset = "Custom"
    private var bassBoostEnabled = false
    private var immersiveAudioEnabled = false

    // Frequency bands (Hz): 60, 230, 910, 3600, 14000
    private val bandFrequencies = intArrayOf(60, 230, 910, 3600, 14000)

    private val presets = mapOf(
        "Regular" to intArrayOf(0, 0, 0, 0, 0),
        "Classical" to intArrayOf(0, 0, 0, 0, 0),
        "Dance" to intArrayOf(6, 4, 0, 0, 2),
        "Flat" to intArrayOf(0, 0, 0, 0, 0),
        "Folk" to intArrayOf(0, 0, 0, 0, 1),
        "Heavy metal" to intArrayOf(4, 2, -2, 2, 4),
        "Hip-hop" to intArrayOf(5, 3, 0, 1, 2),
        "Jazz" to intArrayOf(0, 0, 0, 0, 1),
        "Pop" to intArrayOf(-1, 0, 2, 2, 0),
        "Rock" to intArrayOf(4, 2, -1, 2, 4)
    )

    fun initialize(audioSessionId: Int) {
        if (audioSessionId == 0) {
            Log.d("EqualizerManager", "Invalid audio session ID: 0")
            return
        }

        // Only reinitialize if audio session changed
        if (audioSessionId == currentAudioSessionId && equalizer != null) {
            Log.d("EqualizerManager", "Equalizer already initialized for audio session: $audioSessionId")
            // Still apply current settings in case they changed
            applyCurrentSettings()
            return
        }

        // Release previous equalizer if session changed
        if (audioSessionId != currentAudioSessionId) {
            releaseEqualizer()
        }

        currentAudioSessionId = audioSessionId

        try {
            // Initialize Equalizer
            equalizer = Equalizer(0, audioSessionId).apply {
                enabled = true
                applyCurrentSettings()
            }

            Log.d("EqualizerManager", "Equalizer initialized for audio session: $audioSessionId")

        } catch (e: Exception) {
            Log.e("EqualizerManager", "Error initializing equalizer", e)
        }
    }

    private fun releaseEqualizer() {
        equalizer?.let { eq ->
            try {
                eq.enabled = false
                eq.release()
                Log.d("EqualizerManager", "Equalizer released for session: $currentAudioSessionId")
            } catch (e: Exception) {
                Log.e("EqualizerManager", "Error releasing equalizer", e)
            }
        }
        equalizer = null
    }

    fun release() {
        releaseEqualizer()
        currentAudioSessionId = 0
        Log.d("EqualizerManager", "EqualizerManager fully released")
    }

    fun setBandLevel(band: Int, level: Int) {
        if (band in 0..4) {
            baseBandLevels[band] = level
            currentPreset = "Custom" // Any manual change switches to Custom preset
            applyCurrentSettings()
            saveSettings()
        }
    }

    fun getBandLevel(band: Int): Int {
        return if (band in 0..4) baseBandLevels[band] else 0
    }

    fun getDisplayBandLevel(band: Int): Int {
        // Return the level that should be displayed in UI (without enhancements)
        return getBandLevel(band)
    }

    fun setPreset(presetName: String) {
        currentPreset = presetName
        if (presetName != "Custom") {
            presets[presetName]?.let { levels ->
                baseBandLevels = levels.copyOf()
                applyCurrentSettings()
            }
        }
        saveSettings()
    }

    fun getCurrentPreset(): String = currentPreset

    fun setBassBoost(enabled: Boolean) {
        bassBoostEnabled = enabled
        applyCurrentSettings()
        saveSettings()
    }

    fun isBassBoostEnabled(): Boolean = bassBoostEnabled

    fun setImmersiveAudio(enabled: Boolean) {
        immersiveAudioEnabled = enabled
        applyCurrentSettings()
        saveSettings()
    }

    fun isImmersiveAudioEnabled(): Boolean = immersiveAudioEnabled

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        equalizer?.enabled = enabled
        saveSettings()
    }

    fun isEnabled(): Boolean = isEnabled

    private fun applyBandLevels() {
        equalizer?.let { eq ->
            try {
                val effectiveLevels = getEffectiveBandLevels()

                for (i in effectiveLevels.indices) {
                    if (i < eq.numberOfBands) {
                        val levelInMillibels = (effectiveLevels[i] * 100).toShort()
                        eq.setBandLevel(i.toShort(), levelInMillibels)
                    }
                }
                Log.d("EqualizerManager", "Applied band levels: ${effectiveLevels.joinToString()}")
            } catch (e: Exception) {
                Log.e("EqualizerManager", "Error applying band levels", e)
            }
        }
    }

    private fun getEffectiveBandLevels(): IntArray {
        val effectiveLevels = baseBandLevels.copyOf()

        // Apply bass boost by enhancing low frequencies
        if (bassBoostEnabled) {
            effectiveLevels[0] = effectiveLevels[0] + 3 // Boost 60Hz
            effectiveLevels[1] = effectiveLevels[1] + 2 // Boost 230Hz
            Log.d("EqualizerManager", "Bass boost applied to levels")
        }

        // Apply immersive audio by enhancing high frequencies
        if (immersiveAudioEnabled) {
            effectiveLevels[4] = effectiveLevels[4] + 2 // Boost 14kHz
            Log.d("EqualizerManager", "Immersive audio applied to levels")
        }

        return effectiveLevels
    }

    private fun applyCurrentSettings() {
        applyBandLevels()
        setEnabled(isEnabled)
        Log.d("EqualizerManager", "Current settings applied - Bass: $bassBoostEnabled, Immersive: $immersiveAudioEnabled")
    }

    fun getCurrentSettings(): EqualizerSettings {
        return EqualizerSettings(
            bandLevels = baseBandLevels.copyOf(),
            currentPreset = currentPreset,
            bassBoostEnabled = bassBoostEnabled,
            immersiveAudioEnabled = immersiveAudioEnabled,
            isEnabled = isEnabled
        )
    }

    fun applySettings(settings: EqualizerSettings) {
        baseBandLevels = settings.bandLevels.copyOf()
        currentPreset = settings.currentPreset
        bassBoostEnabled = settings.bassBoostEnabled
        immersiveAudioEnabled = settings.immersiveAudioEnabled
        isEnabled = settings.isEnabled

        applyCurrentSettings()
    }

    private fun saveSettings() {
        val settings = getCurrentSettings()
        val gson = Gson()
        val json = gson.toJson(settings)

        // Save to shared preferences
        val context = NebulaPlayerApplication.getAppContext()
        context?.getSharedPreferences("equalizer_settings", android.content.Context.MODE_PRIVATE)
            ?.edit()
            ?.putString("equalizer_config", json)
            ?.apply()

        Log.d("EqualizerManager", "Settings saved: $currentPreset, Bass: $bassBoostEnabled, Immersive: $immersiveAudioEnabled")
    }

    fun loadSettings() {
        val context = NebulaPlayerApplication.getAppContext() ?: return

        val prefs = context.getSharedPreferences("equalizer_settings", android.content.Context.MODE_PRIVATE)
        val json = prefs.getString("equalizer_config", null)

        if (json != null) {
            try {
                val gson = Gson()
                val settings = gson.fromJson(json, EqualizerSettings::class.java)
                applySettings(settings)
                Log.d("EqualizerManager", "Settings loaded: ${settings.currentPreset}, Bass: ${settings.bassBoostEnabled}, Immersive: ${settings.immersiveAudioEnabled}")
            } catch (e: Exception) {
                Log.e("EqualizerManager", "Error loading settings", e)
                // Apply default settings
                applySettings(EqualizerSettings())
            }
        } else {
            // Apply default settings
            applySettings(EqualizerSettings())
        }
    }

    // Check if equalizer is currently active
    fun isInitialized(): Boolean {
        return equalizer != null && currentAudioSessionId != 0
    }

    // Get current audio session ID
    fun getCurrentAudioSessionId(): Int = currentAudioSessionId

    // Force reapply settings (useful when song changes)
    fun reapplySettings() {
        if (isInitialized()) {
            applyCurrentSettings()
            Log.d("EqualizerManager", "Settings reapplied for current audio session")
        }
    }

    // Get frequency labels for display
    fun getFrequencyLabel(band: Int): String {
        return when (band) {
            0 -> "60Hz"
            1 -> "230Hz"
            2 -> "910Hz"
            3 -> "3.6kHz"
            4 -> "14.0kHz"
            else -> ""
        }
    }

    // Get frequency value for a band
    fun getFrequency(band: Int): Int {
        return if (band in bandFrequencies.indices) bandFrequencies[band] else 0
    }

    data class EqualizerSettings(
        val bandLevels: IntArray = intArrayOf(0, 0, 0, 0, 1),
        val currentPreset: String = "Custom",
        val bassBoostEnabled: Boolean = false,
        val immersiveAudioEnabled: Boolean = false,
        val isEnabled: Boolean = true
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as EqualizerSettings

            if (!bandLevels.contentEquals(other.bandLevels)) return false
            if (currentPreset != other.currentPreset) return false
            if (bassBoostEnabled != other.bassBoostEnabled) return false
            if (immersiveAudioEnabled != other.immersiveAudioEnabled) return false
            if (isEnabled != other.isEnabled) return false

            return true
        }

        override fun hashCode(): Int {
            var result = bandLevels.contentHashCode()
            result = 31 * result + currentPreset.hashCode()
            result = 31 * result + bassBoostEnabled.hashCode()
            result = 31 * result + immersiveAudioEnabled.hashCode()
            result = 31 * result + isEnabled.hashCode()
            return result
        }
    }
}