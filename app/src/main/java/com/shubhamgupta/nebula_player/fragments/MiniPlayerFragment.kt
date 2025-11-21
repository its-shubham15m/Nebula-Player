package com.shubhamgupta.nebula_player.fragments

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.palette.graphics.Palette
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.google.android.material.card.MaterialCardView
import com.shubhamgupta.nebula_player.MainActivity
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.service.MusicService
import com.shubhamgupta.nebula_player.utils.SongUtils
import com.shubhamgupta.nebula_player.models.Song
import com.shubhamgupta.nebula_player.repository.SongRepository
import com.shubhamgupta.nebula_player.utils.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class MiniPlayerFragment : Fragment() {

    private var musicService: MusicService? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var albumArt: ImageView
    private lateinit var songTitle: TextView
    private lateinit var songArtist: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var contentLayout: View
    private lateinit var miniPlayerCard: MaterialCardView
    private var lastPlayedSong: Song? = null
    private var currentDominantColor: Int = Color.BLACK
    private var isVisibleToUser = false

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isVisibleToUser) {
                updatePlayerInfo()
            }
            handler.postDelayed(this, 1000)
        }
    }

    companion object {
        fun newInstance(): MiniPlayerFragment = MiniPlayerFragment()
        private const val TAG = "MiniPlayerFragment"
    }

    // Standard dark and light colors for text/icons
    private val COLOR_FOREGROUND_DARK = Color.BLACK
    private val COLOR_FOREGROUND_LIGHT = Color.WHITE

    /**
     * Helper to load the last played song from preferences/storage.
     */
    private fun getLastPlayedSongFromRepo(): Song? {
        val recentSongIds = PreferenceManager.getRecentSongs(requireContext())
        val recentSongId = recentSongIds.firstOrNull()

        if (recentSongId != null) {
            return try {
                SongRepository.getAllSongs(requireContext())
                    .firstOrNull { it.id == recentSongId }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
        return null
    }

    /**
     * Public method for MainActivity to check if MiniPlayer is displaying a resumable song.
     */
    fun isResumableState(): Boolean {
        return musicService?.getCurrentSong() == null && lastPlayedSong != null
    }

    /**
     * Helper function to resolve a theme attribute to its color value.
     */
    private fun resolveThemeColor(attrId: Int): Int {
        val typedValue = android.util.TypedValue()
        requireContext().theme.resolveAttribute(attrId, typedValue, true)
        return typedValue.data
    }

    /**
     * Determines if a color is light or dark based on its luminance.
     */
    private fun isColorLight(color: Int): Boolean {
        return Color.luminance(color) > 0.5f
    }

    /**
     * Sets the text and icon colors based on the luminance check.
     */
    private fun setForegroundColors(isLightBackground: Boolean) {
        val foregroundColor = if (isLightBackground) {
            COLOR_FOREGROUND_DARK
        } else {
            COLOR_FOREGROUND_LIGHT
        }

        val colorStateList = ColorStateList.valueOf(foregroundColor)

        songTitle.setTextColor(foregroundColor)
        songArtist.setTextColor(foregroundColor)

        btnPlay.imageTintList = colorStateList
        btnNext.imageTintList = colorStateList
    }

    /**
     * Load last played song details from persistent storage
     */
    private fun loadLastPlayedSongDetails() {
        val lastSongDetails = PreferenceManager.getLastSongDetails(requireContext())

        if (lastSongDetails.isNotEmpty()) {
            // Show last played song details
            songTitle.text = lastSongDetails["title"] ?: "Last Played"
            songArtist.text = lastSongDetails["artist"] ?: "Unknown Artist"

            // Enable Play button for resume
            btnPlay.isEnabled = true
            btnNext.isEnabled = false
            btnPlay.setImageResource(R.drawable.ic_playn)

            // Load album art for last played song
            lastSongDetails["albumId"]?.toLongOrNull()?.let { albumId ->
                val artUri = SongUtils.getAlbumArtUri(albumId)
                Glide.with(this).load(artUri).placeholder(R.drawable.default_album_art).into(albumArt)

                // Extract colors from the album art for color palette
                extractColorFromAlbumArt(artUri)
            }

            // Load the actual song object for resuming playback
            GlobalScope.launch(Dispatchers.IO) {
                val recentSongIds = PreferenceManager.getRecentSongs(requireContext())
                val recentSongId = recentSongIds.firstOrNull()
                if (recentSongId != null) {
                    lastPlayedSong = SongRepository.getAllSongs(requireContext())
                        .firstOrNull { it.id == recentSongId }
                    Log.d(TAG, "Loaded last played song: ${lastPlayedSong?.title}")
                }
            }

            contentLayout.visibility = View.VISIBLE
        } else {
            // No recent song found
            showDefaultState()
        }
    }

    /**
     * Extract color palette from album art and update UI
     */
    private fun extractColorFromAlbumArt(artSource: Any) {
        Glide.with(this)
            .asBitmap()
            .load(artSource)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    Palette.from(resource).generate { palette ->
                        val defaultColor = resolveThemeColor(com.google.android.material.R.attr.colorSurface)
                        val dominantColor = palette?.getDominantColor(defaultColor) ?: defaultColor
                        currentDominantColor = dominantColor

                        // Apply the dominant color to the MaterialCardView background
                        miniPlayerCard.setCardBackgroundColor(dominantColor)
                        setForegroundColors(isColorLight(dominantColor))

                        Log.d(TAG, "Applied dominant color: ${String.format("#%06X", 0xFFFFFF and dominantColor)}")
                    }
                }
                override fun onLoadCleared(placeholder: Drawable?) {
                    // Reset to default theme color when cleared
                    val defaultColor = resolveThemeColor(com.google.android.material.R.attr.colorSurface)
                    miniPlayerCard.setCardBackgroundColor(defaultColor)
                    setForegroundColors(isColorLight(defaultColor))
                }
            })
    }

    private fun showDefaultState() {
        songTitle.text = "Welcome to Nebula Music!"
        songArtist.text = "Start Playing Songs: Tap Any Song"

        btnPlay.isEnabled = false
        btnNext.isEnabled = false
        btnPlay.setImageResource(R.drawable.ic_playn)

        Glide.with(this).load(R.drawable.default_album_art).into(albumArt)

        // Reset to default theme color
        val defaultColor = resolveThemeColor(com.google.android.material.R.attr.colorSurface)
        miniPlayerCard.setCardBackgroundColor(defaultColor)
        setForegroundColors(isColorLight(defaultColor))

        contentLayout.visibility = View.VISIBLE
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_mini_player, container, false)
        initializeViews(view)
        setupClickListeners()
        return view
    }

    private fun initializeViews(view: View) {
        albumArt = view.findViewById(R.id.mini_album_art)
        songTitle = view.findViewById(R.id.mini_title)
        songArtist = view.findViewById(R.id.mini_artist)
        btnPlay = view.findViewById(R.id.mini_play_pause)
        btnNext = view.findViewById(R.id.mini_next)
        contentLayout = view.findViewById(R.id.mini_player_content)
        miniPlayerCard = view.findViewById(R.id.mini_player_card)

        // Ensure the MiniPlayer is visible by default
        contentLayout.visibility = View.VISIBLE
    }

    private fun setupClickListeners() {
        contentLayout.setOnClickListener {
            Log.d(TAG, "MiniPlayer clicked")
            val currentSong = musicService?.getCurrentSong()
            if (currentSong != null) {
                // Song is loaded, navigate to Now Playing
                Log.d(TAG, "Current song exists, navigating to NowPlaying")
                navigateToNowPlaying()
            } else if (lastPlayedSong != null) {
                // No song loaded, but a recent song is available. Load it, play it, and navigate.
                Log.d(TAG, "No current song, but last played song exists. Loading and playing: ${lastPlayedSong?.title}")
                musicService?.startPlayback(arrayListOf(lastPlayedSong!!), 0)
                // Add a small delay to ensure the song is loaded before navigating
                handler.postDelayed({
                    navigateToNowPlaying()
                }, 500)
            } else {
                Log.d(TAG, "No song available to play")
            }
        }

        btnPlay.setOnClickListener {
            val currentSong = musicService?.getCurrentSong()
            if (currentSong != null) {
                // Toggle playback for the current song
                musicService?.togglePlayPause()
                updatePlayButton()
            } else if (lastPlayedSong != null) {
                // No song loaded, but a recent song is available. Load it and start playing.
                Log.d(TAG, "Playing last played song: ${lastPlayedSong?.title}")
                musicService?.startPlayback(arrayListOf(lastPlayedSong!!), 0)
                updatePlayerInfo()
            }
        }

        btnNext.setOnClickListener {
            // Only functional if a queue is loaded in the service
            if (musicService?.getCurrentSong() != null) {
                musicService?.playNext("NONE")
                updatePlayerInfo()
            }
        }
    }

    private fun navigateToNowPlaying() {
        try {
            Log.d(TAG, "Attempting to navigate to NowPlaying")
            val activity = requireActivity() as? MainActivity
            if (activity != null) {
                activity.navigateToNowPlaying()
                Log.d(TAG, "Successfully called navigateToNowPlaying()")
            } else {
                Log.e(TAG, "Activity is not MainActivity or is null")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error navigating to NowPlaying: ${e.message}", e)
            e.printStackTrace()
        }
    }

    /**
     * Core update function with enhanced state handling
     */
    private fun updatePlayerInfo() {
        try {
            musicService = (requireActivity() as MainActivity).getMusicService()
            val currentSong = musicService?.getCurrentSong()

            if (currentSong != null) {
                // --- STATE 1: SONG IS PLAYING/PAUSED ---
                lastPlayedSong = null

                songTitle.text = currentSong.title
                songArtist.text = currentSong.artist ?: "Unknown Artist"

                // Enable buttons
                btnPlay.isEnabled = true
                btnNext.isEnabled = true

                // Load art and set colors
                val artSource = if (currentSong.embeddedArtBytes != null) {
                    currentSong.embeddedArtBytes
                } else {
                    SongUtils.getAlbumArtUri(currentSong.albumId)
                }

                Glide.with(this).load(artSource).placeholder(R.drawable.default_album_art).into(albumArt)

                // Dynamic Color Extraction and Contrast Check
                extractColorFromAlbumArt(artSource)

                updatePlayButton()
                contentLayout.visibility = View.VISIBLE

            } else if (musicService != null) {
                // --- STATE 2: NO SONG ACTIVE IN SERVICE, BUT SERVICE IS BOUND ---
                loadLastPlayedSongDetails()
            } else {
                // --- STATE 3: SERVICE NOT YET BOUND ---
                contentLayout.visibility = View.VISIBLE // Always show, even if no service
                showDefaultState()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in updatePlayerInfo: ${e.message}", e)
            contentLayout.visibility = View.VISIBLE
            showDefaultState()
        }
    }

    private fun updatePlayButton() {
        val isPlaying = musicService?.isPlaying() ?: false
        btnPlay.setImageResource(if (isPlaying) R.drawable.ic_pausen else R.drawable.ic_playn)
    }

    override fun onResume() {
        super.onResume()
        isVisibleToUser = true
        // Get service and start periodic updates
        try {
            musicService = (requireActivity() as MainActivity).getMusicService()
            handler.post(updateRunnable)
            updatePlayerInfo()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onResume: ${e.message}", e)
        }
    }

    override fun onPause() {
        super.onPause()
        isVisibleToUser = false
        handler.removeCallbacks(updateRunnable)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        isVisibleToUser = false
        handler.removeCallbacks(updateRunnable)
    }
}