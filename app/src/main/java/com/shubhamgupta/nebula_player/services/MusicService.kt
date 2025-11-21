package com.shubhamgupta.nebula_player.service

import android.app.Service
import android.content.Intent
import android.media.MediaPlayer
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.models.Song
import com.shubhamgupta.nebula_player.utils.EqualizerManager
import com.shubhamgupta.nebula_player.utils.PreferenceManager
import com.shubhamgupta.nebula_player.utils.SongCacheManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.Random

class MusicService : Service(), MediaPlayer.OnCompletionListener, MediaPlayer.OnPreparedListener {

    private var player: MediaPlayer? = null
    private var songList: ArrayList<Song> = ArrayList()
    private var originalSongList: ArrayList<Song> = ArrayList()
    private var songPosition: Int = -1
    private var isPrepared = false
    private var isShuffleMode = false
    private var repeatMode = RepeatMode.ALL
    private val random = Random()

    // Queue management
    private var currentQueue = mutableListOf<Song>()
    private var currentQueuePosition = 0

    // State restoration tracking
    private var isRestoringState = false
    private var restoreSeekPosition = 0

    private lateinit var mediaSession: MediaSessionCompat

    // Notification manager
    private lateinit var notificationManager: MusicNotificationManager

    // State saving - FIXED: Use proper handler
    private val saveStateHandler = Handler(Looper.getMainLooper())
    private val saveStateRunnable = object : Runnable {
        override fun run() {
            savePlaybackState()
            saveStateHandler.postDelayed(this, 5000)
        }
    }

    private companion object {
        const val QUEUE_VISIBLE_RANGE = 10
        const val QUEUE_PRELOAD_RANGE = 5
        // NEW: Constants for custom media session actions
        const val CUSTOM_ACTION_TOGGLE_REPEAT = "com.shubhamgupta.nebula_player.ACTION_TOGGLE_REPEAT"
        const val CUSTOM_ACTION_TOGGLE_FAVORITE = "com.shubhamgupta.nebula_player.ACTION_TOGGLE_FAVORITE"
    }

    fun getAudioSessionId(): Int {
        return player?.audioSessionId ?: 0
    }

    private fun savePlaybackState() {
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // FIXED: Check if we have valid data before saving
                val currentSong = getCurrentSong()
                val currentPosition = getCurrentPosition()

                // Only save if we have a valid state
                if (currentSong != null || currentQueue.isNotEmpty()) {
                    PreferenceManager.savePlaybackStateWithQueueValidation(
                        context = applicationContext,
                        currentSongId = currentSong?.id,
                        seekPosition = currentPosition,
                        repeatMode = repeatMode,
                        isShuffleMode = isShuffleMode,
                        queueSongs = currentQueue,
                        currentQueuePosition = currentQueuePosition,
                        originalQueueSongs = originalSongList
                    )
                } else {
                    Log.d("MusicService", "No valid playback state to save")
                }
            } catch (e: Exception) {
                Log.e("MusicService", "Error saving playback state", e)
            }
        }
    }

    private val binder = MusicBinder()

    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()

        // Initialize notification manager
        notificationManager = MusicNotificationManager(this)
        notificationManager.createNotificationChannel()

        // Initialize media session
        mediaSession = MediaSessionCompat(this, "NebulaMusic").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onSeekTo(pos: Long) {
                    this@MusicService.seekTo(pos.toInt())
                }

                override fun onPlay() {
                    play()
                }

                override fun onPause() {
                    pause()
                }

                override fun onSkipToNext() {
                    playNext("NONE")
                }

                override fun onSkipToPrevious() {
                    playPrevious("NONE")
                }

                // NEW: Handle custom actions sent by the System UI on Android 14+
                override fun onCustomAction(action: String, extras: Bundle?) {
                    when (action) {
                        CUSTOM_ACTION_TOGGLE_REPEAT -> toggleRepeatMode()
                        CUSTOM_ACTION_TOGGLE_FAVORITE -> toggleFavorite()
                    }
                }
            })
            isActive = true
        }

        // Initialize cache
        SongCacheManager.initializeCache(applicationContext)

        // Start background cache updates
        startBackgroundCacheUpdates()

        // Load saved state if available
        kotlinx.coroutines.GlobalScope.launch {
            loadSavedPlaybackState()
        }

        // Start as foreground service immediately
        ensureForegroundService()
        startSaveStateUpdates()
    }

    private fun debugQueueState(tag: String) {
        Log.d("MusicService", "=== QUEUE DEBUG [$tag] ===")
        Log.d("MusicService", "Current Queue (${currentQueue.size} songs):")
        currentQueue.forEachIndexed { index, song ->
            Log.d("MusicService", "  [$index] ${song.title} (ID: ${song.id}) ${if (index == currentQueuePosition) "← CURRENT" else ""}")
        }
        Log.d("MusicService", "Original Queue (${originalSongList.size} songs):")
        originalSongList.forEachIndexed { index, song ->
            Log.d("MusicService", "  [$index] ${song.title} (ID: ${song.id})")
        }
        Log.d("MusicService", "SongList (${songList.size} songs), Position: $songPosition")
        Log.d("MusicService", "Current Song: ${getCurrentSong()?.title ?: "None"}")
        Log.d("MusicService", "Shuffle: $isShuffleMode, Repeat: $repeatMode")
        Log.d("MusicService", "=== END DEBUG ===")
    }

    private fun startBackgroundCacheUpdates() {
        val cacheUpdateHandler = Handler(Looper.getMainLooper())
        val cacheUpdateRunnable = object : Runnable {
            override fun run() {
                if (SongCacheManager.shouldUpdateCache()) {
                    SongCacheManager.refreshCache(applicationContext)
                }
                cacheUpdateHandler.postDelayed(this, 60000)
            }
        }
        cacheUpdateHandler.postDelayed(cacheUpdateRunnable, 60000)
    }

    fun refreshQueueState() {
        Log.d("MusicService", "Forcing queue state refresh")
        sendBroadcast(Intent("QUEUE_CHANGED"))
        sendBroadcast(Intent("SONG_CHANGED"))
        debugQueueState("FORCE_REFRESH")
    }

    private fun startSaveStateUpdates() {
        saveStateHandler.post(saveStateRunnable)
    }

    private fun stopSaveStateUpdates() {
        saveStateHandler.removeCallbacks(saveStateRunnable)
    }

    override fun onPrepared(mp: MediaPlayer?) {
        isPrepared = true

        // Initialize equalizer with current audio session
        val audioSessionId = mp?.audioSessionId ?: 0
        if (audioSessionId != 0) {
            EqualizerManager.initialize(audioSessionId)
            Log.d("MusicService", "Equalizer initialized with audio session: $audioSessionId")

            // Force reapply settings to ensure they take effect
            EqualizerManager.reapplySettings()
        }

        if (isRestoringState && restoreSeekPosition > 0) {
            Log.d("MusicService", "Restoration mode - seeking to position: $restoreSeekPosition but NOT auto-playing")
            mp?.seekTo(restoreSeekPosition)
            restoreSeekPosition = 0
        } else {
            mp?.start()
            Log.d("MusicService", "Normal playback - starting playback")
        }

        updateMediaSessionMetadata()
        notificationManager.updateNotification(this, getCurrentSong(), isPlaying(), repeatMode)
        updateMediaSessionState()

        if (!isRestoringState) {
            notificationManager.startNotificationUpdates(this, getCurrentSong(), isPlaying())
        }

        ensureForegroundService()
        savePlaybackState()

        if (isRestoringState) {
            sendSongChangedBroadcast("RESTORE")
            isRestoringState = false
        } else {
            sendSongChangedBroadcast("NONE")
        }
    }

    // Update onStartCommand to handle Android 14+ restrictions
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Ensure we're in foreground immediately
        ensureForegroundService()

        when (intent?.action) {
            "PREVIOUS" -> playPrevious("NONE")
            "TOGGLE_PLAYBACK" -> togglePlayPause()
            "NEXT" -> playNext("NONE")
            "TOGGLE_REPEAT" -> toggleRepeatMode()
            "TOGGLE_FAVORITE" -> toggleFavorite()
            "SEEK_TO" -> {
                val position = intent.getIntExtra("position", 0)
                seekTo(position)
            }
            "CLOSE" -> {
                stopForeground(true)
                stopSelf()
            }
            "ENSURE_FOREGROUND" -> {
                ensureForegroundService()
            }
            "QUEUE" -> {
                val queueIntent = Intent("SHOW_QUEUE")
                sendBroadcast(queueIntent)
            }
            "RESTORE_PLAYBACK" -> {
                kotlinx.coroutines.GlobalScope.launch {
                    loadSavedPlaybackState()
                }
            }
        }
        return START_STICKY
    }

    override fun onCompletion(mp: MediaPlayer?) {
        notificationManager.stopNotificationUpdates()
        when (repeatMode) {
            RepeatMode.ONE -> playCurrentSong()
            RepeatMode.ALL -> playNext("NONE")
            RepeatMode.SHUFFLE -> playNext("NONE")
        }
    }

    fun startPlayback(songs: ArrayList<Song>, position: Int) {
        // FIXED: Validate input parameters
        if (songs.isEmpty()) {
            Log.w("MusicService", "Cannot start playback with empty song list")
            return
        }

        val safePosition = position.coerceIn(0, songs.size - 1)

        this.originalSongList = ArrayList(songs)
        this.songList = ArrayList(songs)
        this.songPosition = safePosition
        this.currentQueue = ArrayList(songs)
        this.currentQueuePosition = safePosition

        verifyQueueSync()
        playSong()
        ensureForegroundService()
    }

    fun getQueueSongs(): List<Song> = currentQueue

    fun getCurrentQueuePosition(): Int = currentQueuePosition

    fun playFromQueue(position: Int) {
        if (position in currentQueue.indices) {
            Log.d("MusicService", "Playing from queue position: $position")

            currentQueuePosition = position
            val queueSong = currentQueue[position]
            val newSongPosition = songList.indexOfFirst { it.id == queueSong.id }
            if (newSongPosition != -1) {
                songPosition = newSongPosition
                Log.d("MusicService", "Found song in songList at position: $songPosition")
            } else {
                songPosition = position.coerceIn(0, songList.size - 1)
                Log.w("MusicService", "Song not found in songList, using fallback position: $songPosition")
            }

            playSong()
            verifyQueueSync()

            sendBroadcast(Intent("QUEUE_CHANGED"))
            sendBroadcast(Intent("SONG_CHANGED"))
        } else {
            Log.e("MusicService", "Invalid queue position: $position")
        }
    }

    private fun getOptimizedQueueRange(): Pair<Int, Int> {
        val start = (currentQueuePosition - QUEUE_VISIBLE_RANGE).coerceAtLeast(0)
        val end = (currentQueuePosition + QUEUE_VISIBLE_RANGE + 1).coerceAtMost(currentQueue.size)
        return Pair(start, end)
    }

    private fun verifyQueueSync(): Boolean {
        if (songList.isEmpty() || currentQueue.isEmpty()) {
            Log.d("MusicService", "Queue sync check: One or both lists are empty")
            return true
        }

        val currentSong = getCurrentSong()
        if (currentSong == null) {
            Log.w("MusicService", "Queue sync check: No current song")
            return false
        }

        val songInQueue = currentQueue.getOrNull(currentQueuePosition)
        val syncOk = songInQueue?.id == currentSong.id

        if (!syncOk) {
            Log.w("MusicService", "QUEUE SYNC MISMATCH: SongList[$songPosition] has '${currentSong.title}' (ID: ${currentSong.id}), Queue[$currentQueuePosition] has '${songInQueue?.title ?: "NONE"}' (ID: ${songInQueue?.id ?: "NONE"})")

            val correctQueuePos = currentQueue.indexOfFirst { it.id == currentSong.id }
            if (correctQueuePos != -1) {
                currentQueuePosition = correctQueuePos
                Log.d("MusicService", "Auto-corrected queue position from ${currentQueuePosition} to $correctQueuePos")
                return true
            } else {
                Log.e("MusicService", "Cannot auto-correct: Song not found in currentQueue")
            }
        } else {
            Log.d("MusicService", "Queue sync OK: SongList[$songPosition] and Queue[$currentQueuePosition] both point to '${currentSong.title}'")
        }

        return syncOk
    }

    private suspend fun loadSavedPlaybackState() {
        try {
            val savedState = PreferenceManager.loadPlaybackState(applicationContext)
            savedState?.let { state ->
                Log.d("MusicService", "Loading saved playback state: lastSongId=${state.lastPlayedSongId}, seekPosition=${state.lastSeekPosition}, queueSize=${state.queueSongIds.size}")

                isRestoringState = true
                restoreSeekPosition = state.lastSeekPosition

                // This no longer requires its own cache initialization
                // SongCacheManager.initializeCache(applicationContext)

                repeatMode = MusicService.RepeatMode.entries.getOrNull(state.repeatMode) ?: MusicService.RepeatMode.ALL
                isShuffleMode = state.isShuffleMode
                Log.d("MusicService", "Restored modes: repeat=$repeatMode, shuffle=$isShuffleMode")

                if (state.queueSongIds.isNotEmpty() || state.originalQueueSongIds.isNotEmpty()) {
                    Log.d("MusicService", "Reconstructing queues...")

                    // OPTIMIZED: Reconstructing the queue from IDs is now lightning-fast
                    // because getSongById is just a HashMap lookup.
                    originalSongList = if (state.originalQueueSongIds.isNotEmpty()) {
                        state.originalQueueSongIds.mapNotNull { id ->
                            SongCacheManager.getSongById(id)
                        }.toMutableList() as ArrayList<Song>
                    } else {
                        ArrayList()
                    }

                    currentQueue = if (state.queueSongIds.isNotEmpty()) {
                        state.queueSongIds.mapNotNull { id ->
                            SongCacheManager.getSongById(id)
                        }.toMutableList()
                    } else {
                        ArrayList(originalSongList)
                    }

                    Log.d("MusicService", "Reconstructed queues - Current: ${currentQueue.size}, Original: ${originalSongList.size}")

                    // ... (rest of the method continues as is)

                    songList = if (isShuffleMode) {
                        ArrayList(currentQueue)
                    } else {
                        ArrayList(originalSongList)
                    }

                    // FIXED: Handle empty queue case
                    currentQueuePosition = if (currentQueue.isNotEmpty()) {
                        state.currentQueuePosition.coerceIn(0, currentQueue.size - 1)
                    } else {
                        0
                    }

                    songPosition = if (songList.isNotEmpty()) {
                        state.lastPlayedSongId?.let { songId ->
                            if (songId != -1L) {
                                val foundPosition = songList.indexOfFirst { it.id == songId }
                                if (foundPosition != -1) {
                                    Log.d("MusicService", "Found song by ID at position: $foundPosition")
                                    foundPosition
                                } else {
                                    currentQueuePosition.coerceIn(0, songList.size - 1)
                                }
                            } else {
                                currentQueuePosition.coerceIn(0, songList.size - 1)
                            }
                        } ?: currentQueuePosition.coerceIn(0, songList.size - 1)
                    } else {
                        0
                    }

                    Log.d("MusicService", "Final positions - Queue: $currentQueuePosition, SongList: $songPosition")

                    if (songList.isNotEmpty() && songPosition in songList.indices) {
                        val currentSong = songList[songPosition]
                        Log.d("MusicService", "Preparing song for restoration: ${currentSong.title}")
                        prepareSongForRestoration(state.lastSeekPosition)
                    } else {
                        Log.w("MusicService", "No valid song to restore")
                        isRestoringState = false
                    }
                } else if (state.lastPlayedSongId != null && state.lastPlayedSongId != -1L) {
                    val song = SongCacheManager.getSongById(state.lastPlayedSongId)
                    if (song != null) {
                        currentQueue = mutableListOf(song)
                        originalSongList = arrayListOf(song)
                        songList = arrayListOf(song)
                        currentQueuePosition = 0
                        songPosition = 0
                        Log.d("MusicService", "Loaded individual song: ${song.title}")
                        prepareSongForRestoration(state.lastSeekPosition)
                    } else {
                        Log.w("MusicService", "Could not load last played song")
                        isRestoringState = false
                    }
                } else {
                    Log.d("MusicService", "No saved state to restore")
                    isRestoringState = false
                }

                sendBroadcast(Intent("PLAYBACK_MODE_CHANGED"))
                sendBroadcast(Intent("SONG_CHANGED"))
                sendBroadcast(Intent("PLAYBACK_STATE_CHANGED"))
                sendBroadcast(Intent("QUEUE_CHANGED"))
                verifyQueueSync()
                Log.d("MusicService", "Playback state restoration completed")
            } ?: run {
                Log.d("MusicService", "No saved state to restore")
                isRestoringState = false
            }
        } catch (e: Exception) {
            Log.e("MusicService", "Error loading saved playback state", e)
            isRestoringState = false
        }
    }

    private fun prepareSongForRestoration(seekPosition: Int = 0) {
        if (songPosition == -1 || songList.isEmpty()) {
            Log.d("MusicService", "Cannot prepare song: invalid position or empty list")
            isRestoringState = false
            return
        }

        val currentSong = songList[songPosition]
        Log.d("MusicService", "Preparing song for restoration: ${currentSong.title} at position: $songPosition, seek: $seekPosition")

        try {
            player?.release()
            player = MediaPlayer().apply {
                setOnCompletionListener(this@MusicService)
                setOnPreparedListener { mp ->
                    isPrepared = true

                    // Initialize equalizer for restored song
                    val audioSessionId = mp.audioSessionId
                    if (audioSessionId != 0) {
                        EqualizerManager.initialize(audioSessionId)
                        Log.d("MusicService", "Equalizer initialized for restored song with audio session: $audioSessionId")
                    }

                    if (seekPosition > 0) {
                        val safeSeekPosition = seekPosition.coerceAtMost(mp.duration)
                        mp.seekTo(safeSeekPosition)
                        Log.d("MusicService", "Seeked to restored position: $safeSeekPosition (duration: ${mp.duration})")
                    }

                    updateMediaSessionMetadata()
                    notificationManager.updateNotification(this@MusicService, getCurrentSong(), isPlaying(), repeatMode)
                    updateMediaSessionState()
                    ensureForegroundService()

                    sendSongChangedBroadcast("RESTORE")
                    sendBroadcast(Intent("QUEUE_CHANGED"))

                    Log.d("MusicService", "Song prepared for restoration - READY but NOT PLAYING")
                    isRestoringState = false
                }
                setOnErrorListener { mp, what, extra ->
                    Log.e("MusicService", "MediaPlayer error during restoration: what=$what, extra=$extra")
                    isRestoringState = false
                    false
                }
                setDataSource(applicationContext, currentSong.uri)
                prepareAsync()
            }
            isPrepared = false
            notificationManager.stopNotificationUpdates()

        } catch (e: Exception) {
            Log.e("MusicService", "Error preparing song for restoration", e)
            isRestoringState = false
        }
    }

    suspend fun getOptimizedQueueForDisplay(): List<Song> {
        if (currentQueue.isEmpty()) return emptyList()

        val (start, end) = getOptimizedQueueRange()

        val visibleSongs = currentQueue.subList(start, end)

        CoroutineScope(Dispatchers.IO).launch {
            val preloadStart = (end).coerceAtMost(currentQueue.size)
            val preloadEnd = (end + QUEUE_PRELOAD_RANGE).coerceAtMost(currentQueue.size)
            if (preloadStart < preloadEnd) {
                currentQueue.subList(preloadStart, preloadEnd)
            }
        }

        return visibleSongs
    }

    private fun playSong() {
        if (songPosition == -1 || songList.isEmpty()) {
            Log.d("MusicService", "Cannot play song: invalid position or empty list")
            return
        }

        val currentSong = songList[songPosition]
        Log.d("MusicService", "Playing song: ${currentSong.title} at songPosition: $songPosition, queuePosition: $currentQueuePosition")

        val queueIndex = currentQueue.indexOfFirst { it.id == currentSong.id }
        if (queueIndex != -1 && queueIndex != currentQueuePosition) {
            Log.d("MusicService", "Syncing queue position from $currentQueuePosition to $queueIndex")
            currentQueuePosition = queueIndex
        }

        PreferenceManager.addRecentSong(applicationContext, currentSong.id)

        try {
            player?.release()
            player = MediaPlayer().apply {
                setOnCompletionListener(this@MusicService)
                setOnPreparedListener(this@MusicService)
                setOnErrorListener { mp, what, extra ->
                    Log.e("MusicService", "MediaPlayer error during playback: what=$what, extra=$extra")
                    if (songList.size > 1) {
                        songPosition = (songPosition + 1) % songList.size
                        val nextSong = songList[songPosition]
                        currentQueuePosition = currentQueue.indexOfFirst { it.id == nextSong.id }.coerceAtLeast(0)
                        playSong()
                    }
                    true
                }
                setDataSource(applicationContext, currentSong.uri)
                prepareAsync()
            }
            isPrepared = false
            notificationManager.stopNotificationUpdates()
            updateMediaSessionMetadata()
            notificationManager.updateNotification(this, getCurrentSong(), isPlaying(), repeatMode)
            updateMediaSessionState()
            ensureForegroundService()

            sendBroadcast(Intent("QUEUE_CHANGED"))
            sendBroadcast(Intent("SONG_CHANGED"))

        } catch (e: Exception) {
            Log.e("MusicService", "Error playing song", e)
            if (songList.size > 1) {
                songPosition = (songPosition + 1) % songList.size
                val nextSong = songList[songPosition]
                currentQueuePosition = currentQueue.indexOfFirst { it.id == nextSong.id }.coerceAtLeast(0)
                playSong()
            }
        }
    }

    private fun playCurrentSong() {
        playSong()
    }

    fun play() {
        if (player?.isPlaying == false && isPrepared) {
            player?.start()
            notificationManager.updateNotification(this, getCurrentSong(), isPlaying(), repeatMode)
            updateMediaSessionState()
            notificationManager.startNotificationUpdates(this, getCurrentSong(), isPlaying())
            ensureForegroundService()
            sendBroadcast(Intent("PLAYBACK_STATE_CHANGED"))
        } else if (!isPrepared && songPosition != -1) {
            playSong()
        }
    }

    fun pause() {
        if (player?.isPlaying == true) {
            player?.pause()
            notificationManager.updateNotification(this, getCurrentSong(), isPlaying(), repeatMode)
            updateMediaSessionState()
            notificationManager.stopNotificationUpdates()
            sendBroadcast(Intent("PLAYBACK_STATE_CHANGED"))
        }
        ensureForegroundService()
    }

    fun togglePlayPause() {
        player?.let {
            if (it.isPlaying && isPrepared) {
                it.pause()
                notificationManager.stopNotificationUpdates()
            } else if (isPrepared) {
                it.start()
                notificationManager.startNotificationUpdates(this, getCurrentSong(), isPlaying())
                ensureForegroundService()
            } else if (songPosition != -1) {
                playSong()
            }
            notificationManager.updateNotification(this, getCurrentSong(), isPlaying(), repeatMode)
            updateMediaSessionState()
            savePlaybackState()
            sendBroadcast(Intent("PLAYBACK_STATE_CHANGED"))
        }
    }

    fun toggleRepeatMode() {
        // Cycle through the three modes: ALL -> ONE -> SHUFFLE -> ALL
        repeatMode = when (repeatMode) {
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.SHUFFLE
            RepeatMode.SHUFFLE -> RepeatMode.ALL
        }

        isShuffleMode = (repeatMode == RepeatMode.SHUFFLE)

        // Apply or revert shuffle based on the new mode
        val currentSong = getCurrentSong()
        if (isShuffleMode) {
            // Shuffle is ON: shuffle the song list but keep the current song playing
            val tempList = ArrayList(originalSongList)
            currentSong?.let { song ->
                tempList.remove(song)
                Collections.shuffle(tempList)
                tempList.add(0, song)
                songList = tempList
                songPosition = 0
            } ?: Collections.shuffle(tempList)

        } else {
            // Shuffle is OFF: revert to the original, non-shuffled list
            songList = ArrayList(originalSongList)
            currentSong?.let { song ->
                // Find the current song's position in the original list
                val newPosition = songList.indexOfFirst { it.id == song.id }
                if (newPosition != -1) songPosition = newPosition
            }
        }

        // Sync the current queue with the updated song list
        currentQueue = ArrayList(songList)
        currentQueuePosition = songPosition

        verifyQueueSync()
        updateMediaSessionState() // CRITICAL: Update the session with the new state
        notificationManager.updateNotification(this, getCurrentSong(), isPlaying(), repeatMode)
        sendBroadcast(Intent("PLAYBACK_MODE_CHANGED"))
        Log.d("MusicService", "Repeat mode changed to: $repeatMode, Shuffle: $isShuffleMode")
    }

    private fun toggleFavorite() {
        val currentSong = getCurrentSong() ?: return

        currentSong.isFavorite = !currentSong.isFavorite
        if (currentSong.isFavorite) {
            PreferenceManager.addFavorite(applicationContext, currentSong.id)
        } else {
            PreferenceManager.removeFavorite(applicationContext, currentSong.id)
        }

        updateMediaSessionState() // CRITICAL: Update the session with the new state
        sendBroadcast(Intent("SONG_CHANGED"))
        notificationManager.updateNotification(this, getCurrentSong(), isPlaying(), repeatMode)
    }

    fun toggleShuffle() {
        val currentSong = getCurrentSong()

        if (isShuffleMode) {
            songList = ArrayList(originalSongList)
            currentSong?.let { song ->
                val newPosition = songList.indexOfFirst { it.id == song.id }
                if (newPosition != -1) {
                    songPosition = newPosition
                    currentQueuePosition = newPosition
                }
            }
            currentQueue = ArrayList(songList)
        } else {
            val tempList = ArrayList(songList)
            currentSong?.let { song ->
                tempList.remove(song)
                Collections.shuffle(tempList)
                tempList.add(0, song)
                songList = tempList
                songPosition = 0
                currentQueuePosition = 0
            } ?: run {
                Collections.shuffle(songList)
            }
            currentQueue = ArrayList(songList)
        }
        verifyQueueSync()

        sendBroadcast(Intent("PLAYBACK_MODE_CHANGED"))
        Log.d("MusicService", "Shuffle mode: $isShuffleMode, Current song position: $songPosition")
    }

    fun setRepeatMode(mode: RepeatMode) {
        repeatMode = mode
        isShuffleMode = (repeatMode == RepeatMode.SHUFFLE)

        if (repeatMode == RepeatMode.SHUFFLE && !isShuffleMode) {
            toggleShuffle()
        } else if (repeatMode != RepeatMode.SHUFFLE && isShuffleMode) {
            toggleShuffle()
        }

        sendBroadcast(Intent("PLAYBACK_MODE_CHANGED"))
    }

    fun isShuffleMode(): Boolean = isShuffleMode
    fun getRepeatMode(): RepeatMode = repeatMode

    fun isPlaying(): Boolean = player?.isPlaying ?: false

    fun getCurrentSong(): Song? =
        if (songPosition >= 0 && songPosition < songList.size) songList[songPosition] else null

    fun getDuration(): Int = if (isPrepared) player?.duration ?: 0 else 0

    fun getCurrentPosition(): Int = if (isPrepared) player?.currentPosition ?: 0 else 0

    fun seekTo(position: Int) {
        if (isPrepared) {
            player?.seekTo(position)
            updateMediaSessionState()
            sendBroadcast(Intent("SEEK_POSITION_CHANGED").apply {
                putExtra("position", position)
            })
        }
    }

    private fun updateMediaSessionMetadata() {
        val currentSong = getCurrentSong() ?: return

        val albumArt = notificationManager.loadAlbumArtBitmap(currentSong)

        val metadataBuilder = android.support.v4.media.MediaMetadataCompat.Builder()
            .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE, currentSong.title)
            .putString(
                android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST,
                currentSong.artist ?: "Unknown Artist"
            )
            .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM, currentSong.album ?: "Unknown Album")
            .putBitmap(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt)
            .putLong(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION, getDuration().toLong())
            .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_GENRE, currentSong.genre)
            .putLong(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_YEAR, currentSong.year?.toLongOrNull() ?: 0L)

        mediaSession.setMetadata(metadataBuilder.build())
    }

    private fun updateMediaSessionState() {
        val state = when {
            !isPrepared -> PlaybackStateCompat.STATE_NONE
            isPlaying() -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }

        val position = getCurrentPosition().toLong()

        val playbackStateBuilder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(state, position, 1.0f)

        // NEW: Add custom actions to inform the system UI about your extra buttons.
        // This is the key to making them appear on Android 14+.

        // Custom Action for the 3-state Repeat/Shuffle button
        val (repeatIcon, repeatTitle) = when (repeatMode) {
            RepeatMode.ONE -> Pair(R.drawable.repeat_one, "Repeat One")
            RepeatMode.SHUFFLE -> Pair(R.drawable.shuffle, "Shuffle")
            else -> Pair(R.drawable.repeat, "Repeat All")
        }
        playbackStateBuilder.addCustomAction(
            PlaybackStateCompat.CustomAction.Builder(
                CUSTOM_ACTION_TOGGLE_REPEAT,
                repeatTitle,
                repeatIcon
            ).build()
        )

        // Custom Action for the Favorite button
        getCurrentSong()?.let {
            val isFavorite = PreferenceManager.isFavorite(applicationContext, it.id)
            val favoriteIcon = if (isFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_outline
            val favoriteTitle = if (isFavorite) "Unfavorite" else "Favorite"
            playbackStateBuilder.addCustomAction(
                PlaybackStateCompat.CustomAction.Builder(
                    CUSTOM_ACTION_TOGGLE_FAVORITE,
                    favoriteTitle,
                    favoriteIcon
                ).build()
            )
        }

        mediaSession.setPlaybackState(playbackStateBuilder.build())
    }

    // In MusicService.kt, update the ensureForegroundService method:
    private fun ensureForegroundService() {
        try {
            val currentSong = getCurrentSong()
            if (currentSong != null) {
                notificationManager.updateNotification(this, currentSong, isPlaying(), repeatMode)
            } else {
                notificationManager.showMinimalNotification(this)
            }
        } catch (e: Exception) {
            Log.e("MusicService", "Error ensuring foreground service", e)
            // Fallback
            notificationManager.showMinimalNotification(this)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d("MusicService", "Task removed but service continues in background")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSaveStateUpdates()
        notificationManager.stopNotificationUpdates()
        mediaSession.release()
        player?.release()
        player = null

        // Release equalizer when service is destroyed
        EqualizerManager.release()

        stopForeground(true)
    }

    enum class RepeatMode {
        ALL, ONE, SHUFFLE
    }

    fun playNext(animationType: String = "NONE") {
        if (songList.isEmpty()) return

        when (repeatMode) {
            RepeatMode.ONE -> {
                Log.d("MusicService", "Repeat One: Playing current song again")
                playCurrentSong()
            }
            RepeatMode.SHUFFLE -> {
                val newPosition = random.nextInt(songList.size)
                Log.d("MusicService", "Shuffle mode: Playing random song at position $newPosition")
                songPosition = newPosition
                currentQueuePosition = newPosition
                playSong()
            }
            RepeatMode.ALL -> {
                songPosition = (songPosition + 1) % songList.size
                currentQueuePosition = songPosition
                Log.d("MusicService", "Repeat All: Playing next song at position $songPosition")
                playSong()
            }
        }
        verifyQueueSync()
        sendSongChangedBroadcast(animationType)
    }

    fun playPrevious(animationType: String = "NONE") {
        if (songList.isEmpty()) return

        when (repeatMode) {
            RepeatMode.ONE -> {
                Log.d("MusicService", "Repeat One: Playing current song again")
                playCurrentSong()
            }
            RepeatMode.SHUFFLE -> {
                val newPosition = random.nextInt(songList.size)
                Log.d("MusicService", "Shuffle mode: Playing random song at position $newPosition")
                songPosition = newPosition
                currentQueuePosition = newPosition
                playSong()
            }
            RepeatMode.ALL -> {
                songPosition = if (songPosition - 1 < 0) songList.size - 1 else songPosition - 1
                currentQueuePosition = songPosition
                Log.d("MusicService", "Repeat All: Playing previous song at position $songPosition")
                playSong()
            }
        }
        verifyQueueSync()
        sendSongChangedBroadcast(animationType)
    }

    private fun sendSongChangedBroadcast(animationType: String) {
        val intent = Intent("SONG_CHANGED").apply {
            putExtra("animationType", animationType)
        }
        sendBroadcast(intent)
    }

    fun triggerStateRestoration() {
        Log.d("MusicService", "Triggering state restoration from MainActivity")
        kotlinx.coroutines.GlobalScope.launch {
            loadSavedPlaybackState()
        }
    }

    fun restorePlaybackStateIfNeeded() {
        Log.d("MusicService", "Checking if playback state restoration is needed")
        if (getCurrentSong() == null) {
            Log.d("MusicService", "No current song, triggering state restoration")
            triggerStateRestoration()
        } else {
            Log.d("MusicService", "Current song exists, no restoration needed")
        }
    }
}