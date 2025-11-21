package com.shubhamgupta.nebula_player.fragments

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import com.shubhamgupta.nebula_player.MainActivity
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.utils.PreferenceManager
import com.shubhamgupta.nebula_player.utils.SongCacheManager

class SettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Note: You'll need to create fragment_settings.xml (provided below)
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar(view)
        setupAudioSettings(view)
        setupLibrarySettings(view)
        setupVideoSettings(view)
        setupGeneralSettings(view)
    }

    private fun setupToolbar(view: View) {
        view.findViewById<View>(R.id.btn_back)?.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        view.findViewById<TextView>(R.id.tv_page_title)?.text = "Settings"
    }

    private fun setupGeneralSettings(view: View) {
        // Theme settings are handled via the dialog in MainActivity usually,
        // but we can trigger it here too if needed.
        // For now, we'll focus on the specific functional settings.
    }

    private fun setupAudioSettings(view: View) {
        val context = requireContext()

        // Equalizer
        view.findViewById<LinearLayout>(R.id.setting_equalizer)?.setOnClickListener {
            (activity as? MainActivity)?.showEqualizerPage()
        }

        // Crossfade
        val switchCrossfade = view.findViewById<SwitchCompat>(R.id.switch_crossfade)
        switchCrossfade.isChecked = PreferenceManager.isCrossfadeEnabled(context)
        switchCrossfade.setOnCheckedChangeListener { _, isChecked ->
            PreferenceManager.setCrossfadeEnabled(context, isChecked)
        }

        // Gapless Playback
        val switchGapless = view.findViewById<SwitchCompat>(R.id.switch_gapless)
        switchGapless.isChecked = PreferenceManager.isGaplessPlaybackEnabled(context)
        switchGapless.setOnCheckedChangeListener { _, isChecked ->
            PreferenceManager.setGaplessPlaybackEnabled(context, isChecked)
        }

        // Filter Short Audio
        val switchFilterShort = view.findViewById<SwitchCompat>(R.id.switch_filter_short)
        switchFilterShort.isChecked = PreferenceManager.isFilterShortAudioEnabled(context)
        switchFilterShort.setOnCheckedChangeListener { _, isChecked ->
            PreferenceManager.setFilterShortAudioEnabled(context, isChecked)
            Toast.makeText(context, "Refresh library to apply changes", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupLibrarySettings(view: View) {
        // Scan Media
        view.findViewById<LinearLayout>(R.id.setting_scan_media)?.setOnClickListener {
            Toast.makeText(requireContext(), "Scanning media...", Toast.LENGTH_SHORT).show()
            SongCacheManager.refreshCache(requireContext())
        }
    }

    private fun setupVideoSettings(view: View) {
        val context = requireContext()

        // Hardware Acceleration
        val switchHwAccel = view.findViewById<SwitchCompat>(R.id.switch_hw_accel)
        switchHwAccel.isChecked = PreferenceManager.isVideoHwAccelerationEnabled(context)
        switchHwAccel.setOnCheckedChangeListener { _, isChecked ->
            PreferenceManager.setVideoHwAccelerationEnabled(context, isChecked)
        }

        // Background Playback (Video)
        val switchBgPlay = view.findViewById<SwitchCompat>(R.id.switch_video_bg)
        switchBgPlay.isChecked = PreferenceManager.isVideoBgPlaybackEnabled(context)
        switchBgPlay.setOnCheckedChangeListener { _, isChecked ->
            PreferenceManager.setVideoBgPlaybackEnabled(context, isChecked)
        }
    }
}