package com.shubhamgupta.nebula_player.fragments

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.card.MaterialCardView
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.service.MusicService
import com.shubhamgupta.nebula_player.utils.EqualizerManager

class EqualizerFragment : Fragment() {

    private var musicService: MusicService? = null
    private var isBound = false

    // UI Components
    private lateinit var presetSpinner: Spinner
    private lateinit var manualAdjustmentLayout: MaterialCardView
    private lateinit var bassBoostSwitch: SwitchCompat // Changed to SwitchCompat
    private lateinit var immersiveAudioSwitch: SwitchCompat // Changed to SwitchCompat

    // Band seek bars
    private val bandSeekBars = arrayOfNulls<SeekBar>(5)
    private val bandTextViews = arrayOfNulls<TextView>(5)

    // Preset data
    private val presets = arrayOf(
        "Custom", "Regular", "Classical", "Dance", "Flat", "Folk",
        "Heavy metal", "Hip-hop", "Jazz", "Pop", "Rock"
    )

    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(className: android.content.ComponentName, service: android.os.IBinder) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isBound = true
            initializeEqualizer()
        }

        override fun onServiceDisconnected(className: android.content.ComponentName) {
            isBound = false
            musicService = null
        }
    }

    // Broadcast receiver to handle song changes
    private val songChangeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            when (intent?.action) {
                "SONG_CHANGED" -> {
                    // Reinitialize equalizer when song changes
                    handler.postDelayed({
                        reinitializeEqualizerForNewSong()
                    }, 100)
                }
                "PLAYBACK_STATE_CHANGED" -> {
                    // Also reinitialize when playback state changes
                    handler.postDelayed({
                        reinitializeEqualizerForNewSong()
                    }, 100)
                }
            }
        }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_equalizer, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeViews(view)
        setupPresetSpinner()
        setupSeekBars()
        setupEnhancementSwitches()
        loadCurrentSettings()
    }

    override fun onResume() {
        super.onResume()
        // Refresh UI with current settings
        loadCurrentSettings()
    }

    override fun onStart() {
        super.onStart()
        // Bind to MusicService
        val intent = Intent(requireContext(), MusicService::class.java)
        requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Register broadcast receiver for song changes with proper flags
        val filter = IntentFilter().apply {
            addAction("SONG_CHANGED")
            addAction("PLAYBACK_STATE_CHANGED")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(songChangeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            requireContext().registerReceiver(songChangeReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        saveCurrentSettings()
        requireContext().unbindService(serviceConnection)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            requireContext().unregisterReceiver(songChangeReceiver)
        } catch (e: Exception) {
            // Ignore if already unregistered
        }
        handler.removeCallbacksAndMessages(null)
    }

    private fun initializeViews(view: View) {
        presetSpinner = view.findViewById(R.id.preset_spinner)
        manualAdjustmentLayout = view.findViewById(R.id.manual_adjustment_layout)
        bassBoostSwitch = view.findViewById(R.id.bass_boost_switch) // Now correctly typed as SwitchCompat
        immersiveAudioSwitch = view.findViewById(R.id.immersive_audio_switch) // Now correctly typed as SwitchCompat

        // Initialize band seekbars and textviews
        bandSeekBars[0] = view.findViewById(R.id.seekbar_60hz)
        bandSeekBars[1] = view.findViewById(R.id.seekbar_230hz)
        bandSeekBars[2] = view.findViewById(R.id.seekbar_910hz)
        bandSeekBars[3] = view.findViewById(R.id.seekbar_3_6khz)
        bandSeekBars[4] = view.findViewById(R.id.seekbar_14khz)

        bandTextViews[0] = view.findViewById(R.id.text_60hz)
        bandTextViews[1] = view.findViewById(R.id.text_230hz)
        bandTextViews[2] = view.findViewById(R.id.text_910hz)
        bandTextViews[3] = view.findViewById(R.id.text_3_6khz)
        bandTextViews[4] = view.findViewById(R.id.text_14khz)
    }

    private fun setupPresetSpinner() {
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, presets)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        presetSpinner.adapter = adapter

        presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedPreset = presets[position]
                EqualizerManager.setPreset(selectedPreset)

                // If not custom preset, update seekbars
                if (selectedPreset != "Custom") {
                    loadCurrentSettings()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupSeekBars() {
        for (i in bandSeekBars.indices) {
            bandSeekBars[i]?.let { seekBar ->
                seekBar.max = 24 // -12 to +12 range
                seekBar.progress = 12 // Center position (0 dB)

                seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            val level = progress - 12
                            updateBandLevel(i, level)
                            presetSpinner.setSelection(0) // Switch to Custom preset
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
        }
    }

    private fun setupEnhancementSwitches() {
        bassBoostSwitch.setOnCheckedChangeListener { _, isChecked ->
            EqualizerManager.setBassBoost(isChecked)
        }

        immersiveAudioSwitch.setOnCheckedChangeListener { _, isChecked ->
            EqualizerManager.setImmersiveAudio(isChecked)
        }
    }

    private fun initializeEqualizer() {
        try {
            musicService?.let { service ->
                val audioSessionId = service.getAudioSessionId()
                if (audioSessionId != 0) {
                    EqualizerManager.initialize(audioSessionId)
                    Log.d("EqualizerFragment", "Equalizer initialized with audio session: $audioSessionId")

                    // Update UI with current settings after initialization
                    loadCurrentSettings()
                } else {
                    Log.d("EqualizerFragment", "Audio session ID is 0, waiting for valid session")
                    // Retry after a short delay
                    handler.postDelayed({
                        initializeEqualizer()
                    }, 500)
                }
            }
        } catch (e: Exception) {
            Log.e("EqualizerFragment", "Error initializing equalizer", e)
            Toast.makeText(requireContext(), "Equalizer not supported on this device", Toast.LENGTH_SHORT).show()
        }
    }

    private fun reinitializeEqualizerForNewSong() {
        Log.d("EqualizerFragment", "Reinitializing equalizer for new song")
        handler.postDelayed({
            initializeEqualizer()
        }, 200)
    }

    private fun updateBandLevel(bandIndex: Int, level: Int) {
        bandTextViews[bandIndex]?.text = if (level >= 0) "+$level" else "$level"
        EqualizerManager.setBandLevel(bandIndex, level)
    }

    private fun loadCurrentSettings() {
        val settings = EqualizerManager.getCurrentSettings()

        // Load band levels
        for (i in settings.bandLevels.indices) {
            val level = settings.bandLevels[i]
            bandSeekBars[i]?.progress = level + 12
            bandTextViews[i]?.text = if (level >= 0) "+$level" else "$level"
        }

        // Load preset
        val presetIndex = presets.indexOf(settings.currentPreset)
        if (presetIndex != -1) {
            presetSpinner.setSelection(presetIndex)
        }

        // Load enhancement settings
        bassBoostSwitch.isChecked = settings.bassBoostEnabled
        immersiveAudioSwitch.isChecked = settings.immersiveAudioEnabled

        Log.d("EqualizerFragment", "Settings loaded: ${settings.currentPreset}")
    }

    private fun saveCurrentSettings() {
        // Settings are automatically saved by EqualizerManager when changes are made
        Log.d("EqualizerFragment", "Settings saved via EqualizerManager")
    }

    companion object {
        fun newInstance(): EqualizerFragment {
            return EqualizerFragment()
        }
    }
}