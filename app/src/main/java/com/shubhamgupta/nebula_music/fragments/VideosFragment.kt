package com.shubhamgupta.nebula_music.fragments

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.fragment.app.Fragment
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shubhamgupta.nebula_music.MainActivity
import com.shubhamgupta.nebula_music.R
import com.shubhamgupta.nebula_music.VideoPlayerActivity
import com.shubhamgupta.nebula_music.adapters.VideoAdapter
import com.shubhamgupta.nebula_music.models.Video
import com.shubhamgupta.nebula_music.repository.VideoRepository
import com.shubhamgupta.nebula_music.utils.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class VideosFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var loadingProgress: ProgressBar

    private val videoList = mutableListOf<Video>()
    private var filteredVideoList = mutableListOf<Video>()
    private var currentQuery = ""
    private var currentSortType = MainActivity.SortType.DATE_ADDED_DESC

    private val handler = Handler(Looper.getMainLooper())
    private var loadJob: Job? = null
    private var scrollPosition = 0
    private var scrollOffset = 0

    // FIX: Auto-refresh runnable for periodic updates
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshDataPreserveState()
            handler.postDelayed(this, 10000) // Refresh every 10 seconds
        }
    }

    private lateinit var videoContentObserver: VideoContentObserver

    private val searchReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "SEARCH_QUERY_CHANGED") {
                currentQuery = intent.getStringExtra("query") ?: ""
                filterVideos()
            }
        }
    }

    private val sortReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "SORT_VIDEOS") {
                val sortTypeOrdinal = intent.getIntExtra("sort_type", 0)
                val newSortType = MainActivity.SortType.entries.toTypedArray()[sortTypeOrdinal]
                if (newSortType != currentSortType) {
                    currentSortType = newSortType
                    PreferenceManager.saveSortPreference(requireContext(), "videos", currentSortType)
                    loadVideos()
                }
            }
        }
    }

    inner class VideoContentObserver(handler: Handler) : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            handler.postDelayed({ refreshDataPreserveState() }, 1000)
        }
    }

    fun setScrollingEnabled(enabled: Boolean) {
        if (this::recyclerView.isInitialized) {
            recyclerView.isNestedScrollingEnabled = enabled
            recyclerView.isEnabled = enabled
        }
    }

    fun saveScrollState() {
        if (this::recyclerView.isInitialized) {
            val layoutManager = recyclerView.layoutManager as? GridLayoutManager
            layoutManager?.let {
                scrollPosition = it.findFirstVisibleItemPosition()
                val v = it.findViewByPosition(scrollPosition)
                scrollOffset = v?.top ?: 0
            }
        }
    }

    fun restoreScrollState() {
        if (this::recyclerView.isInitialized) {
            val layoutManager = recyclerView.layoutManager as? GridLayoutManager
            layoutManager?.let {
                handler.postDelayed({
                    it.scrollToPositionWithOffset(scrollPosition, scrollOffset)
                }, 100)
            }
        }
    }

    fun refreshData() {
        if (isAdded) loadVideos()
    }

    fun refreshDataPreserveState() {
        if (isAdded) loadVideos(preserveState = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentSortType = PreferenceManager.getSortPreferenceWithDefault(requireContext(), "videos")
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_songs_list, container, false)
        recyclerView = view.findViewById(R.id.songs_recycler_view)
        emptyView = view.findViewById(R.id.tv_empty_songs)
        loadingProgress = view.findViewById(R.id.loading_progress)

        emptyView.text = "No videos found"
        recyclerView.layoutManager = GridLayoutManager(context, 2)
        videoContentObserver = VideoContentObserver(handler)

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadVideos()
    }

    override fun onResume() {
        super.onResume()
        val searchFilter = IntentFilter("SEARCH_QUERY_CHANGED")
        val sortFilter = IntentFilter("SORT_VIDEOS")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireActivity().registerReceiver(searchReceiver, searchFilter, Context.RECEIVER_NOT_EXPORTED)
            requireActivity().registerReceiver(sortReceiver, sortFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            requireActivity().registerReceiver(searchReceiver, searchFilter)
            requireActivity().registerReceiver(sortReceiver, sortFilter)
        }

        try {
            requireContext().contentResolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                true,
                videoContentObserver
            )
        } catch (e: Exception) {
            Log.e("VideosFragment", "Error registering content observer", e)
        }

        // FIX: Start periodic refresh
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        requireActivity().unregisterReceiver(searchReceiver)
        requireActivity().unregisterReceiver(sortReceiver)
        try { requireContext().contentResolver.unregisterContentObserver(videoContentObserver) } catch (e: Exception) {}

        loadJob?.cancel()
        handler.removeCallbacks(refreshRunnable) // FIX: Stop refresh
        saveScrollState()
    }

    private fun loadVideos(preserveState: Boolean = false) {
        if (!preserveState) {
            loadingProgress.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.GONE
        }

        loadJob?.cancel()
        loadJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                val videos = VideoRepository.getAllVideos(requireContext())
                videoList.clear()
                videoList.addAll(videos)
                applyCurrentSort()

                if (!preserveState) {
                    loadingProgress.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                } else {
                    restoreScrollState()
                }
            } catch (e: Exception) {
                Log.e("VideosFragment", "Error loading videos", e)
                loadingProgress.visibility = View.GONE
            }
        }
    }

    private fun applyCurrentSort() {
        when (currentSortType) {
            MainActivity.SortType.NAME_ASC -> videoList.sortBy { it.title.lowercase() }
            MainActivity.SortType.NAME_DESC -> videoList.sortByDescending { it.title.lowercase() }
            MainActivity.SortType.DATE_ADDED_ASC -> videoList.sortBy { it.dateAdded }
            MainActivity.SortType.DATE_ADDED_DESC -> videoList.sortByDescending { it.dateAdded }
            MainActivity.SortType.DURATION -> videoList.sortByDescending { it.duration }
        }
        filterVideos()
    }

    private fun filterVideos() {
        filteredVideoList = if (currentQuery.isBlank()) {
            videoList.toMutableList()
        } else {
            videoList.filter { it.title.contains(currentQuery, true) }.toMutableList()
        }
        updateAdapter()
    }

    @OptIn(UnstableApi::class)
    private fun updateAdapter() {
        if (!isAdded) return
        val adapter = VideoAdapter(requireContext(), filteredVideoList) { video ->
            val intent = Intent(requireContext(), VideoPlayerActivity::class.java).apply {
                putExtra("VIDEO_ID", video.id)
                putExtra("VIDEO_TITLE", video.title)
            }
            startActivity(intent)
        }
        recyclerView.adapter = adapter

        if (filteredVideoList.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyView.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }
}