package com.shubhamgupta.nebula_player.fragments

import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.models.Song
import com.shubhamgupta.nebula_player.service.MusicService
import com.shubhamgupta.nebula_player.utils.SongUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class NowPlayingQueueManager(private val fragment: NowPlayingFragment) {

    // Optimized queue management with caching
    private val queueItemsCache = ConcurrentHashMap<Int, View>()
    private var currentQueuePosition = 0
    private var isQueueLoading = false
    private var cachedQueueSongs: List<Song> = emptyList()
    private var queueDialog: BottomSheetDialog? = null

    // Queue scrolling state management
    private var isUserScrolling = false
    private var lastScrollY = 0
    private var scrollCheckHandler = Handler(Looper.getMainLooper())
    private var scrollCheckRunnable: Runnable? = null
    private var currentlyRenderedRange = Pair(0, 0)
    private val VISIBLE_RANGE = 15 // Number of songs to render initially

    /**
     * CORRECTED: Show queue dialog with current song fixed at top position
     */
    fun showQueueDialog() {
        if (isQueueLoading) {
            Toast.makeText(fragment.requireContext(), "Queue is loading...", Toast.LENGTH_SHORT).show()
            return
        }

        val queueSongs = fragment.getMusicService()?.getQueueSongs() ?: emptyList()
        currentQueuePosition = fragment.getCurrentQueuePosition()

        if (queueSongs.isEmpty()) {
            Toast.makeText(fragment.requireContext(), "Queue is empty", Toast.LENGTH_SHORT).show()
            return
        }

        // Cache the queue songs
        cachedQueueSongs = queueSongs

        val dialogView = LayoutInflater.from(fragment.requireContext()).inflate(R.layout.dialog_queue, null)
        val queueListView = dialogView.findViewById<LinearLayout>(R.id.queue_list)
        val btnCloseBottom = dialogView.findViewById<MaterialButton>(R.id.btn_close_queue_bottom)
        val scrollView = dialogView.findViewById<ScrollView>(R.id.queue_scroll_view)

        queueDialog = BottomSheetDialog(fragment.requireContext())
        queueDialog?.setContentView(dialogView)

        // Configure BottomSheetBehavior
        queueDialog?.setOnShowListener { dialogInterface ->
            val bottomSheet = (dialogInterface as BottomSheetDialog).findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                val displayMetrics = fragment.requireContext().resources.displayMetrics
                val screenHeight = displayMetrics.heightPixels
                val targetHeight = (screenHeight * 0.65).toInt()

                val layoutParams = it.layoutParams
                layoutParams?.height = targetHeight
                it.layoutParams = layoutParams

                behavior.isHideable = false
                behavior.peekHeight = targetHeight
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }

        queueListView.removeAllViews()

        // Mark as loading
        isQueueLoading = true

        // Load initial visible range - FIXED: Current song always at position 0
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // CORRECTED: Always start from current position and show next songs
                val startIndex = currentQueuePosition // Current song is at position 0 in the view
                val endIndex = (currentQueuePosition + VISIBLE_RANGE).coerceAtMost(queueSongs.size)
                currentlyRenderedRange = Pair(startIndex, endIndex)

                // Preload all visible items in background first for smooth experience
                preloadQueueItems(queueSongs, startIndex, endIndex, currentQueuePosition)

                // Render initial visible range on main thread
                withContext(Dispatchers.Main) {
                    renderQueueRange(queueListView, queueSongs, startIndex, endIndex, currentQueuePosition)

                    // CORRECTED: Force scroll to top to ensure current song is visible at top
                    scrollView?.post {
                        scrollView.scrollTo(0, 0)

                        // Highlight current song
                        val currentSongView = queueItemsCache[currentQueuePosition]
                        if (currentSongView != null) {
                            updateQueueItemAppearance(currentSongView, currentQueuePosition, currentQueuePosition)
                        }
                    }

                    isQueueLoading = false

                    // Start continuous scroll monitoring for dynamic loading
                    setupContinuousScrollMonitoring(scrollView, queueListView, queueSongs)
                }

                // Preload next batch in background
                preloadNextBatch(queueSongs, endIndex)

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isQueueLoading = false
                    Toast.makeText(fragment.requireContext(), "Error loading queue", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnCloseBottom?.setOnClickListener {
            stopScrollMonitoring()
            queueDialog?.dismiss()
            queueDialog = null
        }

        queueDialog?.setOnDismissListener {
            stopScrollMonitoring()
            queueDialog = null
            // DON'T clear cache here - keep it for next open
        }

        queueDialog?.show()
    }

    /**
     * Preload queue items in background before rendering
     */
    private fun preloadQueueItems(
        queueSongs: List<Song>,
        startIndex: Int,
        endIndex: Int,
        currentPosition: Int
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Pre-create all views in background for smooth scrolling
                for (index in startIndex until endIndex) {
                    if (!queueItemsCache.containsKey(index)) {
                        val queueItemView = createQueueItemView(queueSongs[index], index, currentPosition)
                        queueItemsCache[index] = queueItemView
                    }
                }
            } catch (e: Exception) {
                // Silent fail for background preloading
            }
        }
    }

    /**
     * Refresh queue dialog when songs change
     */
    fun refreshQueueDialog() {
        queueDialog?.let { dialog ->
            val queueListView = dialog.findViewById<LinearLayout>(R.id.queue_list)
            val queueSongs = fragment.getMusicService()?.getQueueSongs() ?: emptyList()
            currentQueuePosition = fragment.getCurrentQueuePosition()

            if (queueListView != null) {
                // Update all visible items
                for (i in 0 until queueListView.childCount) {
                    val child = queueListView.getChildAt(i)
                    val position = queueListView.indexOfChild(child)
                    val actualIndex = currentlyRenderedRange.first + position
                    if (actualIndex in queueSongs.indices) {
                        updateQueueItemAppearance(child, actualIndex, currentQueuePosition)
                    }
                }
            }
        }
    }

    /**
     * Continuous scroll monitoring for dynamic loading - SIMPLIFIED
     */
    private fun setupContinuousScrollMonitoring(
        scrollView: ScrollView?,
        queueListView: LinearLayout,
        queueSongs: List<Song>
    ) {
        scrollCheckRunnable = object : Runnable {
            override fun run() {
                if (!isUserScrolling && scrollView != null) {
                    checkAndLoadMoreItems(scrollView, queueListView, queueSongs)
                }
                scrollCheckHandler.postDelayed(this, 200) // Check every 200ms
            }
        }

        scrollCheckHandler.post(scrollCheckRunnable!!)

        // Track user scrolling - SIMPLIFIED
        scrollView?.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            val scrollDelta = Math.abs(scrollY - oldScrollY)
            isUserScrolling = scrollDelta > 5 // More sensitive scrolling detection

            lastScrollY = scrollY

            // Reset user scrolling flag after a delay
            if (isUserScrolling) {
                scrollCheckHandler.removeCallbacks(scrollCheckRunnable!!)
                scrollCheckHandler.postDelayed({
                    isUserScrolling = false
                    scrollCheckHandler.post(scrollCheckRunnable!!)
                }, 1000) // Longer delay for user control
            }
        }
    }

    fun stopScrollMonitoring() {
        scrollCheckRunnable?.let {
            scrollCheckHandler.removeCallbacks(it)
        }
    }

    /**
     * Check and load more items based on scroll position
     */
    private fun checkAndLoadMoreItems(
        scrollView: ScrollView,
        queueListView: LinearLayout,
        queueSongs: List<Song>
    ) {
        if (isQueueLoading || queueSongs.isEmpty()) return

        val scrollY = scrollView.scrollY
        val viewHeight = scrollView.height
        val totalHeight = queueListView.height

        // Check if near bottom and need to load more
        if (scrollY + viewHeight >= totalHeight - (viewHeight / 2)) {
            // Near bottom - load next batch
            val currentEnd = currentlyRenderedRange.second
            if (currentEnd < queueSongs.size) {
                loadMoreItems(queueListView, queueSongs, currentEnd)
            }
        }

        // Check if near top and need to load previous items
        if (scrollY <= viewHeight / 2 && currentlyRenderedRange.first > 0) {
            // Near top - load previous batch
            val currentStart = currentlyRenderedRange.first
            if (currentStart > 0) {
                loadPreviousItems(queueListView, queueSongs, currentStart)
            }
        }
    }

    /**
     * Load more items at the end
     */
    private fun loadMoreItems(
        queueListView: LinearLayout,
        queueSongs: List<Song>,
        startIndex: Int
    ) {
        if (isQueueLoading) return

        val endIndex = (startIndex + 10).coerceAtMost(queueSongs.size) // Load 10 more items
        if (startIndex >= endIndex) return

        isQueueLoading = true

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Preload in background first
                preloadQueueItems(queueSongs, startIndex, endIndex, currentQueuePosition)

                withContext(Dispatchers.Main) {
                    renderQueueRange(queueListView, queueSongs, startIndex, endIndex, currentQueuePosition)
                    currentlyRenderedRange = Pair(currentlyRenderedRange.first, endIndex)
                    isQueueLoading = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isQueueLoading = false
                }
            }
        }
    }

    /**
     * Load previous items at the start
     */
    private fun loadPreviousItems(
        queueListView: LinearLayout,
        queueSongs: List<Song>,
        endIndex: Int
    ) {
        if (isQueueLoading) return

        val startIndex = (endIndex - 10).coerceAtLeast(0) // Load 10 previous items
        if (startIndex >= endIndex) return

        isQueueLoading = true

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Preload in background first
                preloadQueueItems(queueSongs, startIndex, endIndex, currentQueuePosition)

                withContext(Dispatchers.Main) {
                    insertQueueRangeAtStart(queueListView, queueSongs, startIndex, endIndex, currentQueuePosition)
                    currentlyRenderedRange = Pair(startIndex, currentlyRenderedRange.second)
                    isQueueLoading = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isQueueLoading = false
                }
            }
        }
    }

    /**
     * CORRECTED: Insert items at the beginning of the list
     */
    private fun insertQueueRangeAtStart(
        queueListView: LinearLayout,
        queueSongs: List<Song>,
        start: Int,
        end: Int,
        currentPosition: Int
    ) {
        val viewsToAdd = mutableListOf<View>()

        for (index in start until end) {
            val cachedView = queueItemsCache[index]
            if (cachedView != null) {
                updateQueueItemAppearance(cachedView, index, currentPosition)
                if (cachedView.parent == null) {
                    viewsToAdd.add(cachedView)
                }
            } else {
                val queueItemView = createQueueItemView(queueSongs[index], index, currentPosition)
                viewsToAdd.add(queueItemView)
                queueItemsCache[index] = queueItemView
            }
        }

        // Add views in correct order at the beginning
        for (i in viewsToAdd.indices.reversed()) {
            queueListView.addView(viewsToAdd[i], 0)
        }
    }

    /**
     * Preload next batch in background
     */
    private fun preloadNextBatch(queueSongs: List<Song>, startIndex: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val endIndex = (startIndex + 10).coerceAtMost(queueSongs.size)
                if (startIndex < endIndex) {
                    for (index in startIndex until endIndex) {
                        if (!queueItemsCache.containsKey(index)) {
                            createQueueItemView(queueSongs[index], index, currentQueuePosition)
                        }
                    }
                }
            } catch (e: Exception) {
                // Silent fail for background preloading
            }
        }
    }

    /**
     * CORRECTED: Render a specific range of queue items
     */
    private fun renderQueueRange(
        queueListView: LinearLayout,
        queueSongs: List<Song>,
        start: Int,
        end: Int,
        currentPosition: Int
    ) {
        for (index in start until end) {
            val cachedView = queueItemsCache[index]
            if (cachedView != null) {
                updateQueueItemAppearance(cachedView, index, currentPosition)
                if (cachedView.parent == null) {
                    queueListView.addView(cachedView)
                }
            } else {
                val queueItemView = createQueueItemView(queueSongs[index], index, currentPosition)
                queueListView.addView(queueItemView)
                queueItemsCache[index] = queueItemView
            }
        }
    }

    /**
     * Create and cache a queue item view
     */
    private fun createQueueItemView(song: Song, index: Int, currentPosition: Int): View {
        val queueItemView = LayoutInflater.from(fragment.requireContext()).inflate(R.layout.item_queue_song, null)

        val tvSongTitle = queueItemView.findViewById<TextView>(R.id.tv_song_title)
        val tvSongArtist = queueItemView.findViewById<TextView>(R.id.tv_song_artist)
        val tvSongPosition = queueItemView.findViewById<TextView>(R.id.tv_song_position)
        val ivAlbumArt = queueItemView.findViewById<ImageView>(R.id.iv_album_art)

        // Set basic data
        tvSongTitle.text = song.title
        tvSongArtist.text = song.artist ?: "Unknown Artist"
        tvSongPosition.text = "${index + 1}"

        // Update appearance
        updateQueueItemAppearance(queueItemView, index, currentPosition)

        // Load album art efficiently (deferred loading)
        loadAlbumArtDeferred(ivAlbumArt, song)

        // Set click listener
        queueItemView.setOnClickListener {
            if (index != currentPosition) {
                fragment.getMusicService()?.playFromQueue(index)
                queueDialog?.dismiss()
            }
        }

        // Set options click listener
        queueItemView.findViewById<ImageView>(R.id.btn_queue_options).setOnClickListener {
            showQueueItemOptions(song, index)
        }

        return queueItemView
    }

    /**
     * Update queue item appearance without recreating the view
     */
    private fun updateQueueItemAppearance(queueItemView: View, index: Int, currentPosition: Int) {
        val tvSongTitle = queueItemView.findViewById<TextView>(R.id.tv_song_title)
        val tvSongArtist = queueItemView.findViewById<TextView>(R.id.tv_song_artist)
        val tvSongPosition = queueItemView.findViewById<TextView>(R.id.tv_song_position)

        if (index == currentPosition) {
            // Highlight current playing song
            tvSongTitle.setTextColor(Color.parseColor("#FF018786"))
            tvSongArtist.setTextColor(Color.parseColor("#FF018786"))
            tvSongPosition.setTextColor(Color.parseColor("#FF018786"))
            queueItemView.setBackgroundColor(Color.parseColor("#1A018786"))
        } else {
            // Standard appearance
            tvSongTitle.setTextColor(Color.WHITE)
            tvSongArtist.setTextColor(Color.parseColor("#B3FFFFFF"))
            tvSongPosition.setTextColor(ContextCompat.getColor(fragment.requireContext(), R.color.white))
            queueItemView.setBackgroundResource(android.R.color.transparent)
        }
    }

    /**
     * Deferred album art loading to prevent UI blocking
     */
    private fun loadAlbumArtDeferred(imageView: ImageView, song: Song) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val artLoader = if (song.embeddedArtBytes != null) {
                    Glide.with(fragment.requireContext()).load(song.embeddedArtBytes)
                } else {
                    Glide.with(fragment.requireContext()).load(SongUtils.getAlbumArtUri(song.albumId))
                }

                withContext(Dispatchers.Main) {
                    artLoader.placeholder(R.drawable.default_album_art)
                        .error(R.drawable.default_album_art)
                        .into(imageView)
                }
            } catch (e: Exception) {
                // Silent fail for background loading
            }
        }
    }

    /**
     * Show options menu for queue item
     */
    private fun showQueueItemOptions(song: Song, position: Int) {
        val options = arrayOf("Remove from queue", "Add to playlist", "Share")

        AlertDialog.Builder(fragment.requireContext())
            .setTitle(song.title)
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> removeFromQueue(position)
                    1 -> showAddToPlaylistDialog(song)
                    2 -> shareSong(song)
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun removeFromQueue(position: Int) {
        Toast.makeText(fragment.requireContext(), "Remove from queue: $position", Toast.LENGTH_SHORT).show()
    }

    private fun showAddToPlaylistDialog(song: Song) {
        Toast.makeText(fragment.requireContext(), "Add to playlist: ${song.title}", Toast.LENGTH_SHORT).show()
    }

    private fun shareSong(song: Song) {
        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT,
                "Check out \"${song.title}\" by ${song.artist ?: "Unknown Artist"} on Nebula Music")
        }
        fragment.startActivity(Intent.createChooser(shareIntent, "Share Song"))
    }

    fun dismissQueueDialog() {
        queueDialog?.dismiss()
        queueDialog = null
    }

    fun clearCache() {
        queueItemsCache.clear()
    }
}