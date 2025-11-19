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
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shubhamgupta.nebula_music.R
import com.shubhamgupta.nebula_music.VideoPlayerActivity
import com.shubhamgupta.nebula_music.adapters.VideoAdapter
import com.shubhamgupta.nebula_music.models.Video
import com.shubhamgupta.nebula_music.repository.VideoRepository
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

    private val handler = Handler(Looper.getMainLooper())
    private var loadJob: Job? = null

    // Scroll state
    private var scrollPosition = 0
    private var scrollOffset = 0

    // Content Observer for Auto-Refresh
    private lateinit var videoContentObserver: VideoContentObserver

    private val searchReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "SEARCH_QUERY_CHANGED") {
                currentQuery = intent.getStringExtra("query") ?: ""
                filterVideos()
            }
        }
    }

    // Observer Class to detect MediaStore changes
    inner class VideoContentObserver(handler: Handler) : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            Log.d("VideosFragment", "External Video Content Changed - Refreshing List")
            refreshDataPreserveState()
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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_songs_list, container, false)
        recyclerView = view.findViewById(R.id.songs_recycler_view)
        emptyView = view.findViewById(R.id.tv_empty_songs)
        loadingProgress = view.findViewById(R.id.loading_progress)

        emptyView.text = "No videos found"

        // Use GridLayoutManager with span count 2
        recyclerView.layoutManager = GridLayoutManager(context, 2)

        // Initialize Observer
        videoContentObserver = VideoContentObserver(handler)

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadVideos()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("SEARCH_QUERY_CHANGED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireActivity().registerReceiver(searchReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            requireActivity().registerReceiver(searchReceiver, filter)
        }

        // Register Content Observer
        try {
            requireContext().contentResolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                true,
                videoContentObserver
            )
        } catch (e: Exception) {
            Log.e("VideosFragment", "Error registering content observer", e)
        }
    }

    override fun onPause() {
        super.onPause()
        requireActivity().unregisterReceiver(searchReceiver)

        // Unregister Content Observer
        try {
            requireContext().contentResolver.unregisterContentObserver(videoContentObserver)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        loadJob?.cancel()
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
                filterVideos()

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

    private fun filterVideos() {
        filteredVideoList = if (currentQuery.isBlank()) {
            videoList.toMutableList()
        } else {
            videoList.filter { it.title.contains(currentQuery, true) }.toMutableList()
        }
        updateAdapter()
    }

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