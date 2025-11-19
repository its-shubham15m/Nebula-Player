package com.shubhamgupta.nebula_music

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shubhamgupta.nebula_music.models.Video
import com.shubhamgupta.nebula_music.repository.VideoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class VideoPlayerActivity : AppCompatActivity() {

    // Views
    private lateinit var videoView: VideoView
    private lateinit var titleView: TextView
    private lateinit var currentTimeView: TextView
    private lateinit var totalTimeView: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var subtitleView: TextView

    private lateinit var backButton: ImageButton
    private lateinit var playPauseButton: ImageButton
    private lateinit var prevButton: ImageButton
    private lateinit var nextButton: ImageButton

    // New VLC-style buttons
    private lateinit var orientationButton: ImageButton
    private lateinit var aspectRatioButton: ImageButton
    private lateinit var tracksButton: ImageButton // Combined Audio + Subs
    private lateinit var optionsButton: ImageButton // Speed, Info
    private lateinit var lockButton: ImageButton
    private lateinit var unlockButton: ImageButton // Appears when locked

    // Overlays
    private lateinit var topControls: LinearLayout
    private lateinit var bottomControls: LinearLayout
    private lateinit var touchOverlay: View
    private lateinit var rewindOverlay: FrameLayout
    private lateinit var forwardOverlay: FrameLayout
    private lateinit var centerInfoLayout: LinearLayout
    private lateinit var centerInfoIcon: ImageView
    private lateinit var centerInfoText: TextView
    private lateinit var lockOverlay: FrameLayout

    // Logic Variables
    private var mediaPlayer: MediaPlayer? = null
    private var videoList: List<Video> = emptyList()
    private var currentVideoIndex = -1
    private var currentVideoUri: Uri? = null

    // Logic State
    private var isLocked = false
    private var currentAspectRatioMode = AspectRatioMode.BEST_FIT
    private var currentAspectRatioValue = 0f // For custom ratios like 16:9

    private lateinit var gestureDetector: GestureDetectorCompat
    private lateinit var audioManager: AudioManager

    private val handler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideSystemUI() }
    private val hideCenterInfoRunnable = Runnable {
        centerInfoLayout.visibility = View.GONE
    }

    // Gesture Accumulators for smooth control
    private var scrollAccumulator = 0f
    private val SCROLL_THRESHOLD = 20f // Accumulate pixels before triggering change

    // Seek Logic
    private var isSeeking = false
    private var seekDirection = 0
    private val seekRunnable = object : Runnable {
        override fun run() {
            if (isSeeking && mediaPlayer != null) {
                val current = videoView.currentPosition
                val target = current + (seekDirection * 2000)
                videoView.seekTo(target.coerceIn(0, videoView.duration))
                seekBar.progress = target
                currentTimeView.text = formatDuration(target.toLong())
                handler.postDelayed(this, 100)
            }
        }
    }

    private val updateProgressAction = object : Runnable {
        override fun run() {
            if (videoView.isPlaying && !isSeeking) {
                val current = videoView.currentPosition
                seekBar.progress = current
                currentTimeView.text = formatDuration(current.toLong())
            }
            handler.postDelayed(this, 1000)
        }
    }

    // Enums for Ratios
    private enum class AspectRatioMode {
        BEST_FIT, FIT_SCREEN, FILL, CUSTOM
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full screen setup
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_video_player)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        initViews()
        hideSystemUI()

        val initialVideoId = intent.getLongExtra("VIDEO_ID", -1L)
        loadVideoList(initialVideoId)

        setupClickListeners()
        setupHoldToSeek()
        setupSeekBar()
        setupGestures()

        videoView.setOnPreparedListener { mp ->
            mediaPlayer = mp
            mp.start()

            // Fix Subtitles: Default to GONE so no empty box is shown
            subtitleView.visibility = View.GONE

            // Listener for embedded subtitles (TimedText)
            mp.setOnTimedTextListener { _, text ->
                handler.post {
                    if (text != null && !text.text.isNullOrEmpty()) {
                        subtitleView.text = text.text.replace("\n", " ")
                        subtitleView.visibility = View.VISIBLE
                    } else {
                        // Use GONE instead of INVISIBLE to hide the background
                        subtitleView.visibility = View.GONE
                    }
                }
            }

            // Try to enable first subtitle track by default if available
            autoSelectSubtitle(mp)

            seekBar.max = videoView.duration
            totalTimeView.text = formatDuration(videoView.duration.toLong())
            playPauseButton.setImageResource(R.drawable.ic_pausen)

            handler.removeCallbacks(updateProgressAction)
            handler.post(updateProgressAction)

            // Default Aspect Ratio
            applyAspectRatio()

            showSystemUI()
            resetAutoHideTimer()
        }

        videoView.setOnCompletionListener { playNextVideo() }
        videoView.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, "Cannot play video", Toast.LENGTH_SHORT).show()
            playNextVideo()
            true
        }
    }

    private fun initViews() {
        videoView = findViewById(R.id.player_video_view)
        titleView = findViewById(R.id.player_title)
        currentTimeView = findViewById(R.id.tv_current_time)
        totalTimeView = findViewById(R.id.tv_total_time)
        seekBar = findViewById(R.id.player_seekbar)
        subtitleView = findViewById(R.id.subtitle_view)

        backButton = findViewById(R.id.player_back_btn)
        playPauseButton = findViewById(R.id.player_play_pause_btn)
        prevButton = findViewById(R.id.player_prev_btn)
        nextButton = findViewById(R.id.player_next_btn)

        // New Controls
        orientationButton = findViewById(R.id.player_orientation_btn)
        aspectRatioButton = findViewById(R.id.player_aspect_ratio_btn)
        tracksButton = findViewById(R.id.player_tracks_btn)
        optionsButton = findViewById(R.id.player_options_btn)
        lockButton = findViewById(R.id.player_lock_btn)
        unlockButton = findViewById(R.id.unlock_btn)
        lockOverlay = findViewById(R.id.lock_overlay)

        topControls = findViewById(R.id.top_controls)
        bottomControls = findViewById(R.id.bottom_controls)
        touchOverlay = findViewById(R.id.touch_overlay)
        rewindOverlay = findViewById(R.id.rewind_overlay)
        forwardOverlay = findViewById(R.id.forward_overlay)
        centerInfoLayout = findViewById(R.id.center_info_layout)
        centerInfoIcon = findViewById(R.id.center_info_icon)
        centerInfoText = findViewById(R.id.center_info_text)
    }

    private fun autoSelectSubtitle(mp: MediaPlayer) {
        try {
            val trackInfo = mp.trackInfo ?: return
            for ((index, track) in trackInfo.withIndex()) {
                if (track.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) {
                    mp.selectTrack(index)
                    break
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun loadVideoList(startId: Long) {
        CoroutineScope(Dispatchers.IO).launch {
            val videos = VideoRepository.getAllVideos(applicationContext)
            withContext(Dispatchers.Main) {
                videoList = videos
                currentVideoIndex = videos.indexOfFirst { it.id == startId }
                if (currentVideoIndex != -1) {
                    playVideo(videoList[currentVideoIndex])
                } else if (videos.isNotEmpty()) {
                    currentVideoIndex = 0
                    playVideo(videos[0])
                }
            }
        }
    }

    private fun playVideo(video: Video) {
        titleView.text = video.title
        subtitleView.text = ""
        subtitleView.visibility = View.GONE
        currentVideoUri = video.uri
        videoView.setVideoURI(video.uri)
    }

    private fun setupClickListeners() {
        backButton.setOnClickListener { finish() }

        playPauseButton.setOnClickListener {
            if (videoView.isPlaying) {
                videoView.pause()
                playPauseButton.setImageResource(R.drawable.ic_playn)
                handler.removeCallbacks(updateProgressAction)
            } else {
                videoView.start()
                playPauseButton.setImageResource(R.drawable.ic_pausen)
                handler.post(updateProgressAction)
                resetAutoHideTimer()
            }
        }

        orientationButton.setOnClickListener { showOrientationDialog() }
        aspectRatioButton.setOnClickListener { showAspectRatioDialog() }
        tracksButton.setOnClickListener { showTrackSelectionDialog() }
        optionsButton.setOnClickListener { showOptionsDialog() }

        lockButton.setOnClickListener { lockScreen() }
        unlockButton.setOnClickListener { unlockScreen() }
    }

    // --- Lock Screen Logic ---
    private fun lockScreen() {
        isLocked = true
        hideSystemUI()
        lockOverlay.visibility = View.VISIBLE
        handler.postDelayed({ lockOverlay.visibility = View.GONE }, 2000) // Briefly show unlock btn hint
        Toast.makeText(this, "Screen Locked", Toast.LENGTH_SHORT).show()
    }

    private fun unlockScreen() {
        isLocked = false
        lockOverlay.visibility = View.GONE
        showSystemUI()
        resetAutoHideTimer()
    }

    // --- Dialogs ---

    private fun showOrientationDialog() {
        val options = arrayOf("Landscape", "Portrait", "Sensor (Auto)")
        AlertDialog.Builder(this)
            .setTitle("Screen Orientation")
            .setItems(options) { _, which ->
                requestedOrientation = when (which) {
                    0 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
                }
                resetAutoHideTimer()
            }
            .show()
    }

    private fun showAspectRatioDialog() {
        val options = arrayOf(
            "Best Fit (Original)",
            "Fit Screen",
            "Fill Screen",
            "16:9", "4:3", "16:10", "2.35:1 (Cinema)", "2:1", "1:1", "5:4"
        )

        AlertDialog.Builder(this)
            .setTitle("Aspect Ratio")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { currentAspectRatioMode = AspectRatioMode.BEST_FIT }
                    1 -> { currentAspectRatioMode = AspectRatioMode.FIT_SCREEN }
                    2 -> { currentAspectRatioMode = AspectRatioMode.FILL }
                    3 -> { currentAspectRatioMode = AspectRatioMode.CUSTOM; currentAspectRatioValue = 16f/9f }
                    4 -> { currentAspectRatioMode = AspectRatioMode.CUSTOM; currentAspectRatioValue = 4f/3f }
                    5 -> { currentAspectRatioMode = AspectRatioMode.CUSTOM; currentAspectRatioValue = 16f/10f }
                    6 -> { currentAspectRatioMode = AspectRatioMode.CUSTOM; currentAspectRatioValue = 2.35f }
                    7 -> { currentAspectRatioMode = AspectRatioMode.CUSTOM; currentAspectRatioValue = 2f }
                    8 -> { currentAspectRatioMode = AspectRatioMode.CUSTOM; currentAspectRatioValue = 1f }
                    9 -> { currentAspectRatioMode = AspectRatioMode.CUSTOM; currentAspectRatioValue = 5f/4f }
                }
                applyAspectRatio()
                resetAutoHideTimer()
            }
            .show()
    }

    private fun showTrackSelectionDialog() {
        val mp = mediaPlayer ?: return
        val trackInfos = mp.trackInfo ?: return

        val audioTracks = mutableListOf<String>()
        val audioTrackIndices = mutableListOf<Int>()
        val subTracks = mutableListOf<String>()
        val subTrackIndices = mutableListOf<Int>()

        subTracks.add("Disable Subtitles")
        subTrackIndices.add(-1) // Helper index for disabling

        for ((index, info) in trackInfos.withIndex()) {
            if (info.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                audioTracks.add("Audio Track ${audioTracks.size + 1} (${info.language})")
                audioTrackIndices.add(index)
            } else if (info.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT ||
                info.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE) {
                subTracks.add("Subtitle ${subTracks.size} (${info.language})")
                subTrackIndices.add(index)
            }
        }

        // Create a custom dialog with two lists or a chooser
        val combinedOptions = mutableListOf<String>()
        combinedOptions.add("--- AUDIO ---")
        combinedOptions.addAll(audioTracks)
        combinedOptions.add("--- SUBTITLES ---")
        combinedOptions.addAll(subTracks)

        AlertDialog.Builder(this)
            .setTitle("Audio & Subtitles")
            .setItems(combinedOptions.toTypedArray()) { _, which ->
                // Calculate index logic based on headers
                val audioStartIndex = 1
                val subStartIndex = audioTracks.size + 2

                if (which in audioStartIndex until (audioStartIndex + audioTracks.size)) {
                    // Audio Selected
                    val realIndex = which - audioStartIndex
                    mp.selectTrack(audioTrackIndices[realIndex])
                    Toast.makeText(this, "Audio Changed", Toast.LENGTH_SHORT).show()
                } else if (which in subStartIndex until (subStartIndex + subTracks.size)) {
                    // Subtitle Selected
                    val realIndex = which - subStartIndex
                    val trackIndex = subTrackIndices[realIndex]

                    if (trackIndex == -1) {
                        // Disable
                        subtitleView.visibility = View.GONE
                        // Attempt to deselect using getSelectedTrack
                        try {
                            val currentTrack = mp.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT)
                            if (currentTrack != -1) {
                                mp.deselectTrack(currentTrack)
                            }
                        } catch(e: Exception){}
                    } else {
                        mp.selectTrack(trackIndex)
                        subtitleView.visibility = View.VISIBLE
                    }
                    Toast.makeText(this, "Subtitle Changed", Toast.LENGTH_SHORT).show()
                }
                resetAutoHideTimer()
            }
            .show()
    }

    private fun showOptionsDialog() {
        val options = arrayOf("Playback Speed", "Video Information")
        AlertDialog.Builder(this)
            .setTitle("Options")
            .setItems(options) { _, which ->
                when(which) {
                    0 -> showPlaybackSpeedDialog()
                    1 -> showVideoDetails()
                }
            }
            .show()
    }

    private fun showPlaybackSpeedDialog() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(this, "Not supported on this device", Toast.LENGTH_SHORT).show()
            return
        }
        val speeds = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x")
        val values = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

        AlertDialog.Builder(this)
            .setTitle("Playback Speed")
            .setItems(speeds) { _, which ->
                try {
                    mediaPlayer?.playbackParams = PlaybackParams().setSpeed(values[which])
                } catch (e: Exception) {
                    Toast.makeText(this, "Error changing speed", Toast.LENGTH_SHORT).show()
                }
                resetAutoHideTimer()
            }
            .show()
    }

    // --- Core Logic ---

    private fun applyAspectRatio() {
        val mp = mediaPlayer ?: return
        if (mp.videoWidth == 0 || mp.videoHeight == 0) return

        val viewWidth = findViewById<View>(R.id.root_layout).width
        val viewHeight = findViewById<View>(R.id.root_layout).height

        // Default Layout Params
        val params = videoView.layoutParams

        val videoRatio = mp.videoWidth.toFloat() / mp.videoHeight.toFloat()
        val screenRatio = viewWidth.toFloat() / viewHeight.toFloat()

        when (currentAspectRatioMode) {
            AspectRatioMode.BEST_FIT -> {
                // Maintain aspect ratio, fit inside screen
                if (videoRatio > screenRatio) {
                    params.width = viewWidth
                    params.height = (viewWidth / videoRatio).toInt()
                } else {
                    params.width = (viewHeight * videoRatio).toInt()
                    params.height = viewHeight
                }
            }
            AspectRatioMode.FIT_SCREEN -> {
                // Force fit (stretch if needed, acts like Best Fit usually)
                params.width = viewWidth
                params.height = viewHeight // May distort if VideoView doesn't handle it
                // To strictly fit inside:
                if (videoRatio > screenRatio) {
                    params.width = viewWidth
                    params.height = (viewWidth / videoRatio).toInt()
                } else {
                    params.width = (viewHeight * videoRatio).toInt()
                    params.height = viewHeight
                }
            }
            AspectRatioMode.FILL -> {
                // Zoom and Crop
                if (videoRatio > screenRatio) {
                    params.width = (viewHeight * videoRatio).toInt()
                    params.height = viewHeight
                } else {
                    params.width = viewWidth
                    params.height = (viewWidth / videoRatio).toInt()
                }
            }
            AspectRatioMode.CUSTOM -> {
                // Force the ratio
                val targetRatio = currentAspectRatioValue
                if (targetRatio > screenRatio) {
                    // Limited by width
                    params.width = viewWidth
                    params.height = (viewWidth / targetRatio).toInt()
                } else {
                    // Limited by height
                    params.width = (viewHeight * targetRatio).toInt()
                    params.height = viewHeight
                }
            }
        }
        videoView.layoutParams = params
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        gestureDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            private var isVolume = false
            private var isBrightness = false

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isLocked) return false
                val screenWidth = videoView.width
                if (e.x < screenWidth / 2) {
                    seekRelative(-10000)
                } else {
                    seekRelative(10000)
                }
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isLocked) {
                    // Show unlock button briefly
                    lockOverlay.visibility = View.VISIBLE
                    handler.removeCallbacksAndMessages("HIDE_LOCK")
                    handler.postDelayed({ lockOverlay.visibility = View.GONE }, 3000)
                    return true
                }
                toggleControls()
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (e1 == null || isLocked) return false

                val screenWidth = findViewById<View>(R.id.root_layout).width
                val deltaY = distanceY

                // Determine side only on initial scroll touch (when accumulator is 0)
                if (!isVolume && !isBrightness) {
                    if (e1.x > screenWidth / 2) {
                        isVolume = true
                    } else {
                        isBrightness = true
                    }
                }

                // Accumulate distance for smoothness
                scrollAccumulator += deltaY

                if (abs(scrollAccumulator) > SCROLL_THRESHOLD) {
                    val steps = (scrollAccumulator / SCROLL_THRESHOLD).toInt()

                    if (isVolume) {
                        adjustVolume(steps)
                    } else if (isBrightness) {
                        adjustBrightness(steps)
                    }

                    // Reset accumulator but keep remainder to maintain continuous smooth feel
                    scrollAccumulator %= SCROLL_THRESHOLD
                }
                return true
            }

            override fun onDown(e: MotionEvent): Boolean {
                isVolume = false
                isBrightness = false
                scrollAccumulator = 0f
                return true
            }
        })

        touchOverlay.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                handler.postDelayed(hideCenterInfoRunnable, 500)
                scrollAccumulator = 0f // Reset on lift
            }
            gestureDetector.onTouchEvent(event)
        }
    }

    private fun adjustVolume(steps: Int) {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        // steps is +ve for scrolling UP (distanceY is +ve when scrolling UP on some devices, check direction)
        // Actually onScroll distanceY is (lastY - currentY). So scrolling UP gives POSITIVE distanceY.

        var newVolume = currentVolume + steps
        newVolume = newVolume.coerceIn(0, maxVolume)

        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
        showCenterInfo(R.drawable.ic_volume, "$newVolume / $maxVolume")
    }

    private fun adjustBrightness(steps: Int) {
        val lp = window.attributes
        var brightness = lp.screenBrightness
        if (brightness == -1f) brightness = 0.5f

        // Smaller steps for brightness (0.0 to 1.0)
        val change = steps * 0.02f
        brightness = (brightness + change).coerceIn(0.01f, 1f)

        lp.screenBrightness = brightness
        window.attributes = lp

        val percent = (brightness * 100).toInt()
        showCenterInfo(R.drawable.ic_brightness, "$percent%")
    }

    private fun showCenterInfo(iconRes: Int, text: String) {
        handler.removeCallbacks(hideCenterInfoRunnable)
        centerInfoLayout.visibility = View.VISIBLE
        centerInfoIcon.setImageResource(iconRes)
        centerInfoText.text = text
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupHoldToSeek() {
        val seekDelay = 400L
        // Previous/Next Logic (Same as before but checked for nulls)
        prevButton.setOnTouchListener { _, event ->
            if(isLocked) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isSeeking = false
                    handler.postDelayed({
                        if (prevButton.isPressed) {
                            isSeeking = true
                            seekDirection = -1
                            showRewindOverlay()
                            handler.post(seekRunnable)
                        }
                    }, seekDelay)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacksAndMessages(null)
                    if (isSeeking) {
                        isSeeking = false
                        rewindOverlay.visibility = View.GONE
                        resetAutoHideTimer()
                    } else {
                        handlePrevious()
                    }
                    handler.post(updateProgressAction)
                    true
                }
                else -> false
            }
        }

        nextButton.setOnTouchListener { _, event ->
            if(isLocked) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isSeeking = false
                    handler.postDelayed({
                        if (nextButton.isPressed) {
                            isSeeking = true
                            seekDirection = 1
                            showForwardOverlay()
                            handler.post(seekRunnable)
                        }
                    }, seekDelay)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacksAndMessages(null)
                    if (isSeeking) {
                        isSeeking = false
                        forwardOverlay.visibility = View.GONE
                        resetAutoHideTimer()
                    } else {
                        playNextVideo()
                    }
                    handler.post(updateProgressAction)
                    true
                }
                else -> false
            }
        }
    }

    private fun handlePrevious() {
        if (videoView.currentPosition > 3000) {
            videoView.seekTo(0)
            if (!videoView.isPlaying) videoView.start()
        } else {
            playPreviousVideo()
        }
    }

    private fun playPreviousVideo() {
        if (videoList.isEmpty()) return
        currentVideoIndex = if (currentVideoIndex - 1 < 0) videoList.size - 1 else currentVideoIndex - 1
        playVideo(videoList[currentVideoIndex])
    }

    private fun playNextVideo() {
        if (videoList.isEmpty()) return
        currentVideoIndex = (currentVideoIndex + 1) % videoList.size
        playVideo(videoList[currentVideoIndex])
    }

    private fun seekRelative(offsetMs: Int) {
        val target = (videoView.currentPosition + offsetMs).coerceIn(0, videoView.duration)
        videoView.seekTo(target)

        if (offsetMs < 0) showRewindOverlay() else showForwardOverlay()
        seekBar.progress = target
        currentTimeView.text = formatDuration(target.toLong())
        resetAutoHideTimer()

        handler.postDelayed({
            rewindOverlay.visibility = View.GONE
            forwardOverlay.visibility = View.GONE
        }, 600)
    }

    private fun showRewindOverlay() { rewindOverlay.visibility = View.VISIBLE }
    private fun showForwardOverlay() { forwardOverlay.visibility = View.VISIBLE }

    private fun setupSeekBar() {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) currentTimeView.text = formatDuration(progress.toLong())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                handler.removeCallbacks(updateProgressAction)
                resetAutoHideTimer()
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let {
                    videoView.seekTo(it.progress)
                    if (!videoView.isPlaying) {
                        videoView.start()
                        playPauseButton.setImageResource(R.drawable.ic_pausen)
                    }
                    handler.post(updateProgressAction)
                    resetAutoHideTimer()
                }
            }
        })
    }

    private fun showVideoDetails() {
        if (currentVideoUri == null) return
        CoroutineScope(Dispatchers.IO).launch {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this@VideoPlayerActivity, currentVideoUri)
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)

                withContext(Dispatchers.Main) {
                    val details = "Resolution: ${width}x${height}\nDuration: ${formatDuration(durationMs?.toLong() ?: 0)}\nBitrate: ${bitrate?.toLong()?.div(1000)} kbps"
                    AlertDialog.Builder(this@VideoPlayerActivity)
                        .setTitle("Video Info")
                        .setMessage(details)
                        .setPositiveButton("OK", null)
                        .show()
                }
                retriever.release()
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        topControls.visibility = View.GONE
        bottomControls.visibility = View.GONE
    }

    private fun showSystemUI() {
        topControls.visibility = View.VISIBLE
        bottomControls.visibility = View.VISIBLE
    }

    private fun toggleControls() {
        if (topControls.visibility == View.VISIBLE) {
            hideSystemUI()
            handler.removeCallbacks(hideControlsRunnable)
        } else {
            showSystemUI()
            resetAutoHideTimer()
        }
    }

    private fun resetAutoHideTimer() {
        handler.removeCallbacks(hideControlsRunnable)
        if (videoView.isPlaying && !isSeeking && !isLocked) {
            handler.postDelayed(hideControlsRunnable, 3000)
        }
    }

    @SuppressLint("DefaultLocale")
    private fun formatDuration(millis: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return if (hours > 0) String.format("%02d:%02d:%02d", hours, minutes, seconds)
        else String.format("%02d:%02d", minutes, seconds)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateProgressAction)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}