package com.shubhamgupta.nebula_player.fragments

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.palette.graphics.Palette
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearSmoothScroller
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.shubhamgupta.nebula_player.MainActivity
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.api.LrcLibApiClient
import com.shubhamgupta.nebula_player.models.Song
import com.shubhamgupta.nebula_player.service.MusicService
import com.shubhamgupta.nebula_player.utils.SongUtils
import com.shubhamgupta.nebula_player.utils.Utils
import com.shubhamgupta.nebula_player.utils.PreferenceManager
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import androidx.core.graphics.createBitmap
import com.shubhamgupta.nebula_player.adapters.LyricLine
import com.shubhamgupta.nebula_player.adapters.LyricsAdapter
import com.shubhamgupta.nebula_player.models.LrcLibLyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class NowPlayingFragment : Fragment() {

    private var musicService: MusicService? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isSeeking = false
    private lateinit var bottomSheetDialog: BottomSheetDialog
    private var isFragmentVisible = false

    private var isSharing = false

    private lateinit var seekBar: SeekBar
    private lateinit var tvCurrent: TextView
    private lateinit var tvTotal: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnBack: ImageButton
    private lateinit var btnRepeat: ImageButton
    private lateinit var btnShare: ImageButton
    private lateinit var btnDetails: ImageButton
    private lateinit var btnQueue: ImageButton
    private lateinit var ivFavorite: ImageView
    private lateinit var ivAlbumArt: ImageView
    private lateinit var tvSongTitle: TextView
    private lateinit var tvSongArtist: TextView
    private lateinit var tvSongDetails: TextView
    private lateinit var backgroundGradient: ImageView
    private lateinit var backgroundOverlay: View
    private lateinit var titleScrollView: HorizontalScrollView
    private lateinit var artistScrollView: HorizontalScrollView
    private lateinit var detailsScrollView: HorizontalScrollView

    // Lyrics UI Components
    private lateinit var lyricsOverlay: View
    private lateinit var lyricsLoadingProgress: ProgressBar
    private lateinit var lyricsRecyclerView: RecyclerView
    private lateinit var lyricsPlainScrollView: View
    private lateinit var tvLyricsPlain: TextView

    private lateinit var lyricsAdapter: LyricsAdapter
    private var currentLyricsList: List<LyricLine> = emptyList()
    private var currentLyricsSongId: Long = -1
    private var isLyricsVisible = false

    private lateinit var queueManager: NowPlayingQueueManager

    // Smooth scroller to center items
    private val smoothScroller by lazy {
        object : LinearSmoothScroller(context) {
            override fun getVerticalSnapPreference(): Int {
                return SNAP_TO_START
            }
            override fun calculateDtToFit(viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int): Int {
                return (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2)
            }
        }
    }

    private val updateSeekBar = object : Runnable {
        override fun run() {
            musicService?.let { service ->
                if (!isSeeking && service.isPlaying()) {
                    val currentPosition = service.getCurrentPosition()
                    val duration = service.getDuration()

                    if (duration > 0) {
                        seekBar.progress = currentPosition
                        tvCurrent.text = Utils.formatTime(currentPosition.toLong())

                        // Sync Lyrics
                        syncLyrics(currentPosition.toLong())
                    }
                }
                handler.postDelayed(this, 250)
            }
        }
    }

    private fun syncLyrics(currentPosition: Long) {
        if (!isLyricsVisible || currentLyricsList.isEmpty()) return

        // Find the line that is currently playing
        val activeIndex = currentLyricsList.indexOfLast { it.startTime <= currentPosition }

        if (activeIndex != -1 && activeIndex != lyricsAdapter.activeIndex) {
            lyricsAdapter.updateActiveLine(activeIndex)

            val layoutManager = lyricsRecyclerView.layoutManager as? LinearLayoutManager
            if (layoutManager != null) {
                smoothScroller.targetPosition = activeIndex
                layoutManager.startSmoothScroll(smoothScroller)
            }
        }
    }

    private var titleAnimator: ValueAnimator? = null
    private var artistAnimator: ValueAnimator? = null
    private var detailsAnimator: ValueAnimator? = null

    private val songChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "SONG_CHANGED" -> {
                    musicService?.getCurrentSong()?.let { song ->
                        song.isFavorite = PreferenceManager.isFavorite(requireContext(), song.id)
                        if (isLyricsVisible) {
                            fetchLyrics(song)
                        } else {
                            currentLyricsSongId = -1
                        }
                    }
                    updateSongInfo()
                    updatePlaybackControls()
                    queueManager.refreshQueueDialog()
                }
                "PLAYBACK_STATE_CHANGED" -> {
                    updatePlayButton()
                }
                "PLAYBACK_MODE_CHANGED" -> {
                    updateRepeatButton()
                }
                "SEEK_POSITION_CHANGED" -> {
                    val position = intent.getIntExtra("position", 0)
                    if (!isSeeking) {
                        seekBar.progress = position
                        tvCurrent.text = Utils.formatTime(position.toLong())
                        syncLyrics(position.toLong())
                    }
                }
                "QUEUE_CHANGED" -> {
                    queueManager.refreshQueueDialog()
                }
            }
        }
    }

    companion object {
        fun newInstance(): NowPlayingFragment = NowPlayingFragment()
        private const val SCROLL_SPEED_PIXELS_PER_SECOND = 40
        private const val BOTTOM_CONTROLS_OFFSET_DP = 90
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        musicService = (requireActivity() as MainActivity).getMusicService()
        queueManager = NowPlayingQueueManager(this)

        val filter = IntentFilter().apply {
            addAction("SONG_CHANGED")
            addAction("PLAYBACK_STATE_CHANGED")
            addAction("PLAYBACK_MODE_CHANGED")
            addAction("SEEK_POSITION_CHANGED")
            addAction("QUEUE_CHANGED")
        }

        ContextCompat.registerReceiver(
            requireActivity(),
            songChangeReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_now_playing, container, false)
        initializeViews(view)
        setupClickListeners()
        setupBottomSheet()
        updatePlaybackControls()
        startSeekBarUpdates()

        return view
    }

    private fun initializeViews(view: View) {
        seekBar = view.findViewById(R.id.seek_bar)
        tvCurrent = view.findViewById(R.id.tv_current)
        tvTotal = view.findViewById(R.id.tv_total)
        btnPlay = view.findViewById(R.id.btn_play)
        btnPrev = view.findViewById(R.id.btn_prev)
        btnNext = view.findViewById(R.id.btn_next)
        btnBack = view.findViewById(R.id.btn_back)
        btnRepeat = view.findViewById(R.id.btn_repeat)
        btnShare = view.findViewById(R.id.btn_share)
        btnDetails = view.findViewById(R.id.btn_details)
        btnQueue = view.findViewById(R.id.btn_queue)
        ivFavorite = view.findViewById(R.id.iv_fav)
        ivAlbumArt = view.findViewById(R.id.album_art)
        tvSongTitle = view.findViewById(R.id.song_title)
        tvSongArtist = view.findViewById(R.id.song_artist)
        tvSongDetails = view.findViewById(R.id.song_details)
        backgroundGradient = view.findViewById(R.id.background_gradient)
        backgroundOverlay = view.findViewById(R.id.background_overlay)
        titleScrollView = view.findViewById(R.id.title_scroll_view)
        artistScrollView = view.findViewById(R.id.artist_scroll_view)
        detailsScrollView = view.findViewById(R.id.details_scroll_view)

        // Initialize Lyrics Views
        lyricsOverlay = view.findViewById(R.id.lyrics_overlay)
        lyricsLoadingProgress = view.findViewById(R.id.lyrics_loading_progress)
        lyricsRecyclerView = view.findViewById(R.id.lyrics_recycler_view)
        lyricsPlainScrollView = view.findViewById(R.id.lyrics_scroll_view)
        tvLyricsPlain = view.findViewById(R.id.tv_lyrics_plain)

        setupLyricsAdapter()
        setupSeekBar()
        applySystemWindowInsets(view)
    }

    private fun setupLyricsAdapter() {
        // On lyric click, seek to that timestamp
        lyricsAdapter = LyricsAdapter { line ->
            musicService?.seekTo(line.startTime.toInt())
        }
        lyricsRecyclerView.layoutManager = LinearLayoutManager(context)
        lyricsRecyclerView.adapter = lyricsAdapter
    }

    private fun applySystemWindowInsets(view: View) {
        val topControls = view.findViewById<LinearLayout>(R.id.top_controls)
        val mainPlayControls = view.findViewById<LinearLayout>(R.id.main_play_controls)
        val rootLayout = view as ViewGroup

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, insets ->
            val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            val totalTopPadding = systemBarsInsets.top
            topControls.setPadding(
                topControls.paddingLeft,
                totalTopPadding,
                topControls.paddingRight,
                topControls.paddingBottom
            )

            val mainPlayControlsLayoutParams = mainPlayControls.layoutParams as ViewGroup.MarginLayoutParams
            val totalBottomMargin = systemBarsInsets.bottom + dpToPx(BOTTOM_CONTROLS_OFFSET_DP)

            mainPlayControlsLayoutParams.bottomMargin = totalBottomMargin
            mainPlayControls.layoutParams = mainPlayControlsLayoutParams

            insets
        }
    }

    private fun setupBottomSheet() {
        val bottomSheetView = LayoutInflater.from(requireContext())
            .inflate(R.layout.bottom_sheet_song_details, null)

        bottomSheetDialog = BottomSheetDialog(requireContext())
        bottomSheetDialog.setContentView(bottomSheetView)

        bottomSheetDialog.behavior.peekHeight = resources.displayMetrics.heightPixels
        bottomSheetDialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED

        bottomSheetView.findViewById<ImageButton>(R.id.btn_close_sheet).setOnClickListener {
            bottomSheetDialog.dismiss()
        }

        bottomSheetView.findViewById<View>(R.id.btn_share_song).setOnClickListener {
            shareCurrentSong()
            bottomSheetDialog.dismiss()
        }

        bottomSheetView.findViewById<View>(R.id.btn_add_to_playlist).setOnClickListener {
            showAddToPlaylistDialog()
            bottomSheetDialog.dismiss()
        }
    }

    private fun setupSeekBar() {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvCurrent.text = Utils.formatTime(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeeking = false
                musicService?.seekTo(seekBar?.progress ?: 0)
            }
        })
    }

    private fun setupClickListeners() {
        btnBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        btnPlay.setOnClickListener { togglePlayPause() }
        btnPrev.setOnClickListener { musicService?.playPrevious() }
        btnNext.setOnClickListener { musicService?.playNext() }
        btnRepeat.setOnClickListener { toggleRepeat() }
        btnShare.setOnClickListener { shareCurrentSong() }
        btnDetails.setOnClickListener { showSongDetailsSheet() }
        btnQueue.setOnClickListener { queueManager.showQueueDialog() }
        ivFavorite.setOnClickListener { toggleFavorite() }

        // --- Lyrics Toggles ---
        ivAlbumArt.setOnClickListener { toggleLyricsVisibility() }

        // Close lyrics on tapping empty space
        lyricsOverlay.setOnClickListener { toggleLyricsVisibility() }
        lyricsPlainScrollView.setOnClickListener { toggleLyricsVisibility() }
        tvLyricsPlain.setOnClickListener { toggleLyricsVisibility() }
    }

    private fun toggleLyricsVisibility() {
        isLyricsVisible = !isLyricsVisible
        if (isLyricsVisible) {
            // Show with Fade In
            lyricsOverlay.alpha = 0f
            lyricsOverlay.visibility = View.VISIBLE
            lyricsOverlay.animate().alpha(1f).setDuration(300).start()

            val song = musicService?.getCurrentSong()
            if (song != null) {
                fetchLyrics(song)
            }
        } else {
            // Hide with Fade Out
            lyricsOverlay.animate().alpha(0f).setDuration(300).withEndAction {
                lyricsOverlay.visibility = View.GONE
            }.start()
        }
    }

    /**
     * Helper function to clean Metadata before sending to API
     * Removes:
     * - Content in brackets () [] {}
     * - File extensions
     * - Common spam domains/suffixes (e.g. - Pagalworld, www...)
     */
    private fun cleanMetaData(text: String?): String {
        if (text.isNullOrEmpty()) return ""

        var cleaned = text

        // 1. Remove file extensions
        cleaned = cleaned.replace(Regex("(?i)\\.(mp3|m4a|flac|wav|aac|ogg)$"), "")

        // 2. Remove content in brackets/parentheses
        cleaned = cleaned.replace(Regex("\\(.*?\\)"), "")
        cleaned = cleaned.replace(Regex("\\[.*?\\]"), "")
        cleaned = cleaned.replace(Regex("\\{.*?\\}"), "")

        // 3. Remove specific website/spam patterns
        // Pattern: " - WebsiteName", " @ Website", "www.Website.com"
        // Aggressive strip of known domains/keywords often found in pirated tags
        cleaned = cleaned.replace(Regex("(?i)\\s*[-_]?\\s*(?:www\\.|pagal|hindi|mr[-]?jatt|dj|wap|songs\\.pk|\\d+kbps|org|net|com|mobi|info|ru).*"), "")

        return cleaned.trim()
    }

    private fun fetchLyrics(song: Song) {
        if (currentLyricsSongId == song.id && (currentLyricsList.isNotEmpty() || tvLyricsPlain.text.isNotEmpty())) {
            return
        }

        currentLyricsSongId = song.id
        currentLyricsList = emptyList()
        lyricsAdapter.submitList(emptyList())
        tvLyricsPlain.text = ""

        lyricsLoadingProgress.visibility = View.VISIBLE
        lyricsRecyclerView.visibility = View.GONE
        lyricsPlainScrollView.visibility = View.GONE

        lifecycleScope.launch {
            try {
                // --- APPLY CLEANING HERE ---
                val cleanTitle = cleanMetaData(song.title)
                val cleanArtist = cleanMetaData(song.artist ?: "")
                val cleanAlbum = cleanMetaData(song.album ?: "")
                val durationSeconds = (song.duration / 1000).toInt()

                Log.d("Lyrics", "Fetching for: '$cleanTitle' by '$cleanArtist'")

                val lyricResult = withContext(Dispatchers.IO) {
                    var result: LrcLibLyrics? = null
                    try {
                        // 1. Try Strict Matching with cleaned data
                        result = LrcLibApiClient.api.getLyrics(cleanTitle, cleanArtist, cleanAlbum, durationSeconds)
                    } catch (e: Exception) {
                        try {
                            // 2. Fallback to Search
                            val query = "$cleanTitle $cleanArtist"
                            val searchResults = LrcLibApiClient.api.searchLyrics(query)
                            result = searchResults.minByOrNull { abs((it.duration ?: 0) - durationSeconds) }
                        } catch (searchEx: Exception) {
                            Log.e("NowPlayingFragment", "Search failed: ${searchEx.message}")
                        }
                    }
                    result
                }

                lyricsLoadingProgress.visibility = View.GONE

                if (lyricResult != null) {
                    if (!lyricResult.syncedLyrics.isNullOrEmpty()) {
                        currentLyricsList = LyricsAdapter.parseLrc(lyricResult.syncedLyrics)
                        lyricsAdapter.submitList(currentLyricsList)
                        lyricsRecyclerView.visibility = View.VISIBLE
                        lyricsPlainScrollView.visibility = View.GONE
                    }
                    else if (!lyricResult.plainLyrics.isNullOrEmpty()) {
                        tvLyricsPlain.text = lyricResult.plainLyrics
                        lyricsPlainScrollView.visibility = View.VISIBLE
                        lyricsRecyclerView.visibility = View.GONE
                    } else {
                        showNoLyricsFound()
                    }
                } else {
                    showNoLyricsFound()
                }

            } catch (e: Exception) {
                Log.e("NowPlayingFragment", "Error fetching lyrics: ${e.message}")
                lyricsLoadingProgress.visibility = View.GONE
                showNoLyricsFound()
            }
        }
    }

    private fun showNoLyricsFound() {
        tvLyricsPlain.text = "No lyrics found"
        lyricsPlainScrollView.visibility = View.VISIBLE
        lyricsRecyclerView.visibility = View.GONE
    }

    private fun toggleRepeat() {
        musicService?.toggleRepeatMode()
    }

    private fun updateRepeatButton() {
        val repeatMode = musicService?.getRepeatMode() ?: MusicService.RepeatMode.ALL

        val iconRes = when (repeatMode) {
            MusicService.RepeatMode.ONE -> R.drawable.repeat_one
            MusicService.RepeatMode.SHUFFLE -> R.drawable.shuffle
            else -> R.drawable.repeat
        }
        btnRepeat.setImageResource(iconRes)

        val color = ContextCompat.getColor(requireContext(), R.color.white)
        btnRepeat.setColorFilter(color)
    }

    private fun showSongDetailsSheet() {
        val currentSong = musicService?.getCurrentSong() ?: return
        val sheetView = bottomSheetDialog.findViewById<View>(R.id.bottom_sheet_song_details) ?: return
        sheetView.findViewById<TextView>(R.id.sheet_song_title).text = currentSong.title
        sheetView.findViewById<TextView>(R.id.sheet_song_artist).text = currentSong.artist ?: "Unknown Artist"
        sheetView.findViewById<TextView>(R.id.sheet_song_album).text = currentSong.album ?: "Unknown Album"

        val artLoader = if (currentSong.embeddedArtBytes != null) {
            Glide.with(this).load(currentSong.embeddedArtBytes)
        } else {
            Glide.with(this).load(SongUtils.getAlbumArtUri(currentSong.albumId))
        }

        artLoader.placeholder(R.drawable.default_album_art)
            .error(R.drawable.default_album_art)
            .into(sheetView.findViewById(R.id.sheet_album_art))

        loadSongDetailsIntoSheet(currentSong, sheetView)
        bottomSheetDialog.show()
    }

    private fun loadSongDetailsIntoSheet(song: Song, sheetView: View) {
        val metadataContainer = sheetView.findViewById<LinearLayout>(R.id.sheet_metadata_container)
        metadataContainer.removeAllViews()
        addMetadataRow(metadataContainer, "Title", song.title)
        addMetadataRow(metadataContainer, "Artist", song.artist ?: "Unknown")
        addMetadataRow(metadataContainer, "Album", song.album ?: "Unknown")
        addMetadataRow(metadataContainer, "Genre", song.genre ?: "Unknown")
        addMetadataRow(metadataContainer, "Year", song.year ?: "Unknown")
        val duration = musicService?.getDuration() ?: 0
        addMetadataRow(metadataContainer, "Duration", Utils.formatTime(duration.toLong()))
        loadAudioMetadata(song, metadataContainer)
    }

    private fun addMetadataRow(container: LinearLayout, label: String, value: String) {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dpToPx(8), 0, dpToPx(8))
            }
        }
        val labelView = TextView(requireContext()).apply {
            text = "$label:"
            setTextAppearance(android.R.style.TextAppearance_Small)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        val valueView = TextView(requireContext()).apply {
            text = value
            setTextAppearance(android.R.style.TextAppearance_Small)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        layout.addView(labelView)
        layout.addView(valueView)
        container.addView(layout)
    }

    private fun loadAudioMetadata(song: Song, container: LinearLayout) {
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(requireContext(), song.uri)
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            val sampleRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
            } else {
                null
            }
            val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            val channels = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS)
            bitrate?.let {
                val kbps = (it.toIntOrNull() ?: 0) / 1000
                addMetadataRow(container, "Bitrate", "$kbps kbps")
            }
            sampleRate?.let { addMetadataRow(container, "Sample Rate", "$it Hz") }
            mimeType?.let { addMetadataRow(container, "Format", it) }
            channels?.let { addMetadataRow(container, "Channels", it) }
            retriever.release()
        } catch (e: Exception) {
            // Ignore metadata errors
        }
    }

    private fun showAddToPlaylistDialog() {
        Toast.makeText(requireContext(), "Add to Playlist feature (needs implementation)", Toast.LENGTH_SHORT).show()
    }

    private fun togglePlayPause() {
        musicService?.togglePlayPause()
    }

    private fun shareCurrentSong() {
        val currentSong = musicService?.getCurrentSong() ?: return
        isSharing = true
        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT,
                "Listening to \"${currentSong.title}\" by ${currentSong.artist ?: "Unknown Artist"} on Nebula Music")
        }
        startActivity(Intent.createChooser(shareIntent, "Share Song"))
    }

    private fun toggleFavorite() {
        val currentSong = musicService?.getCurrentSong() ?: return
        currentSong.isFavorite = !currentSong.isFavorite
        if (currentSong.isFavorite) {
            PreferenceManager.addFavorite(requireContext(), currentSong.id)
        } else {
            PreferenceManager.removeFavorite(requireContext(), currentSong.id)
        }
        ivFavorite.setImageResource(
            if (currentSong.isFavorite) R.drawable.ic_favorite_filled
            else R.drawable.ic_favorite_outline
        )
        Toast.makeText(requireContext(),
            if (currentSong.isFavorite) "Added to favorites" else "Removed from favorites",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updatePlaybackControls() {
        updateSongInfo()
        updatePlayButton()
        updateRepeatButton()
    }

    private fun updateSongInfo() {
        val currentSong = musicService?.getCurrentSong() ?: return
        currentSong.isFavorite = PreferenceManager.isFavorite(requireContext(), currentSong.id)
        tvSongTitle.text = currentSong.title
        tvSongArtist.text = currentSong.artist ?: "Unknown Artist"

        val detailsText = StringBuilder()
        currentSong.album?.let { detailsText.append(it) }
        currentSong.year?.let {
            if (detailsText.isNotEmpty()) detailsText.append(" | ")
            detailsText.append(it)
        }
        tvSongDetails.text = detailsText

        resetScrollPositions()

        val artLoader = if (currentSong.embeddedArtBytes != null) {
            Glide.with(this).load(currentSong.embeddedArtBytes)
        } else {
            Glide.with(this).load(SongUtils.getAlbumArtUri(currentSong.albumId))
        }

        artLoader.placeholder(R.drawable.default_album_art)
            .error(R.drawable.default_album_art)
            .listener(object : RequestListener<android.graphics.drawable.Drawable> {
                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<android.graphics.drawable.Drawable>, isFirstResource: Boolean): Boolean { return false }
                override fun onResourceReady(resource: android.graphics.drawable.Drawable, model: Any, target: Target<android.graphics.drawable.Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                    generateGradientBackground(resource)
                    return false
                }
            })
            .into(ivAlbumArt)

        val duration = musicService?.getDuration() ?: 0
        if (duration > 0) {
            seekBar.max = duration
            tvTotal.text = Utils.formatTime(duration.toLong())

            val savedState = PreferenceManager.loadPlaybackState(requireContext())
            savedState?.let { state ->
                if (state.lastPlayedSongId == currentSong.id && state.lastSeekPosition > 0) {
                    Log.d("NowPlayingFragment", "Restoring seek position: ${state.lastSeekPosition}")
                    handler.post {
                        seekBar.progress = state.lastSeekPosition
                        tvCurrent.text = Utils.formatTime(state.lastSeekPosition.toLong())
                    }
                }
            }
        }

        ivFavorite.setImageResource(
            if (currentSong.isFavorite) R.drawable.ic_favorite_filled
            else R.drawable.ic_favorite_outline
        )
        startSmoothAutoScrolling()
    }

    private fun resetScrollPositions() {
        titleScrollView.scrollTo(0, 0)
        artistScrollView.scrollTo(0, 0)
        detailsScrollView.scrollTo(0, 0)
    }

    private fun generateGradientBackground(drawable: android.graphics.drawable.Drawable) {
        try {
            val bitmap = if (drawable is BitmapDrawable) {
                drawable.bitmap
            } else {
                val bitmap = createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight)
                val canvas = android.graphics.Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bitmap
            }

            Palette.from(bitmap).generate { palette ->
                val dominantColor = palette?.dominantSwatch?.rgb ?: 0x000000
                val mutedColor = palette?.mutedSwatch?.rgb ?: dominantColor
                val vibrantColor = palette?.vibrantSwatch?.rgb ?: dominantColor

                val gradientDrawable = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(
                        ColorUtils.setAlphaComponent(vibrantColor, 150),
                        ColorUtils.setAlphaComponent(mutedColor, 100),
                        ColorUtils.setAlphaComponent(dominantColor, 50)
                    )
                )
                backgroundGradient.setImageDrawable(gradientDrawable)
            }
        } catch (e: Exception) {
            // Fallback gradient
        }
    }

    private fun updatePlayButton() {
        val isPlaying = musicService?.isPlaying() ?: false
        btnPlay.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun setSystemBarAppearance(isNowPlaying: Boolean) {
        val window = requireActivity().window
        val windowController = WindowCompat.getInsetsController(window, window.decorView)

        if (isNowPlaying) {
            // In NowPlayingFragment, we want light-colored icons for the status bar
            // to be visible on the dark gradient background.
            windowController.isAppearanceLightStatusBars = false
        } else {
            // When leaving the fragment, restore MainActivity's default UI appearance.
            (activity as? MainActivity)?.updateSystemUiColors()
        }
    }

    private fun startSeekBarUpdates() {
        handler.post(updateSeekBar)
    }

    private fun stopSeekBarUpdates() {
        handler.removeCallbacks(updateSeekBar)
    }

    private fun startSmoothAutoScrolling() {
        stopAutoScrolling()
        titleScrollView.post {
            val maxScroll = tvSongTitle.width - titleScrollView.width
            if (maxScroll > 0) {
                val duration = (maxScroll.toFloat() / SCROLL_SPEED_PIXELS_PER_SECOND * 1000).toLong()
                titleAnimator = ValueAnimator.ofInt(0, maxScroll).apply {
                    this.duration = duration
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener { animator -> titleScrollView.scrollTo(animator.animatedValue as Int, 0) }
                    startDelay = 2000
                    start()
                }
            }
        }
        artistScrollView.post {
            val maxScroll = tvSongArtist.width - artistScrollView.width
            if (maxScroll > 0) {
                val duration = (maxScroll.toFloat() / SCROLL_SPEED_PIXELS_PER_SECOND * 1000).toLong()
                artistAnimator = ValueAnimator.ofInt(0, maxScroll).apply {
                    this.duration = duration
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener { animator -> artistScrollView.scrollTo(animator.animatedValue as Int, 0) }
                    startDelay = 2000
                    start()
                }
            }
        }
        detailsScrollView.post {
            val maxScroll = tvSongDetails.width - detailsScrollView.width
            if (maxScroll > 0) {
                val duration = (maxScroll.toFloat() / SCROLL_SPEED_PIXELS_PER_SECOND * 1000).toLong()
                detailsAnimator = ValueAnimator.ofInt(0, maxScroll).apply {
                    this.duration = duration
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener { animator -> detailsScrollView.scrollTo(animator.animatedValue as Int, 0) }
                    startDelay = 2000
                    start()
                }
            }
        }
    }

    private fun stopAutoScrolling() {
        titleAnimator?.cancel()
        artistAnimator?.cancel()
        detailsAnimator?.cancel()
    }

    override fun onResume() {
        super.onResume()
        isFragmentVisible = true
        isSharing = false
        setSystemBarAppearance(true)

        musicService?.getCurrentSong()?.let { song ->
            song.isFavorite = PreferenceManager.isFavorite(requireContext(), song.id)
        }

        startSeekBarUpdates()
        updatePlaybackControls()
        startSmoothAutoScrolling()
    }

    override fun onPause() {
        super.onPause()
        isFragmentVisible = false
        if (!isSharing) {
            setSystemBarAppearance(false)
        }
        stopSeekBarUpdates()
        stopAutoScrolling()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        setSystemBarAppearance(false)
        isFragmentVisible = false
        queueManager.stopScrollMonitoring()
        stopSeekBarUpdates()
        stopAutoScrolling()
        if (bottomSheetDialog.isShowing) {
            bottomSheetDialog.dismiss()
        }
        queueManager.dismissQueueDialog()
    }

    override fun onDestroy() {
        super.onDestroy()
        queueManager.stopScrollMonitoring()
        try {
            requireActivity().unregisterReceiver(songChangeReceiver)
        } catch (e: Exception) {
            // Ignore if receiver was not registered
        }
        queueManager.clearCache()
    }

    private fun dpToPx(dp: Int): Int {
        return try {
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp.toFloat(),
                resources.displayMetrics
            ).toInt()
        } catch (e: Exception) {
            (dp * resources.displayMetrics.density).toInt()
        }
    }

    fun getMusicService(): MusicService? = musicService
    fun getCurrentQueuePosition(): Int = musicService?.getCurrentQueuePosition() ?: 0
}