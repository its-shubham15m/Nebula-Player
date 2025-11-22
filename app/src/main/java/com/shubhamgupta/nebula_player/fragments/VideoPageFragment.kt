package com.shubhamgupta.nebula_player.fragments

import android.app.Activity
import android.app.RecoverableSecurityException
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
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shubhamgupta.nebula_player.MainActivity
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.VideoPlayerActivity
import com.shubhamgupta.nebula_player.adapters.VideoAdapter
import com.shubhamgupta.nebula_player.adapters.VideoUiModel
import com.shubhamgupta.nebula_player.models.Video
import com.shubhamgupta.nebula_player.repository.VideoRepository
import com.shubhamgupta.nebula_player.utils.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.regex.Pattern

class VideoPageFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var loadingProgress: ProgressBar
    private lateinit var pageTitle: TextView

    private val videoList = mutableListOf<Video>()
    private val currentUiList = mutableListOf<VideoUiModel>() // Can be Videos or Folders

    private var currentSortType = MainActivity.SortType.DATE_ADDED_DESC
    private var isFolderSort = false
    private var isFolderSortAsc = true

    // Folder Navigation State
    private var isInFolderView = false
    private var activeFolderName: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private var loadJob: Job? = null
    private var scrollPosition = 0
    private var scrollOffset = 0

    private lateinit var deleteResultLauncher: ActivityResultLauncher<IntentSenderRequest>

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshDataPreserveState()
            handler.postDelayed(this, 10000)
        }
    }

    private lateinit var videoContentObserver: VideoContentObserver

    private val searchReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "SEARCH_QUERY_CHANGED") {
                // Search resets folder view to show all matching videos flat
                val query = intent.getStringExtra("query") ?: ""
                filterVideos(query)
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
                    isFolderSort = false
                    isInFolderView = false // Reset folder view on global sort
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentSortType = PreferenceManager.getSortPreferenceWithDefault(requireContext(), "videos")

        // Initialize Delete Launcher
        deleteResultLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Toast.makeText(requireContext(), "Video deleted", Toast.LENGTH_SHORT).show()
                loadVideos()
            } else {
                Toast.makeText(requireContext(), "Could not delete video", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_video_page, container, false)

        recyclerView = view.findViewById(R.id.video_recycler_view)
        emptyView = view.findViewById(R.id.tv_empty_videos)
        loadingProgress = view.findViewById(R.id.loading_progress)
        pageTitle = view.findViewById(R.id.page_title)

        recyclerView.layoutManager = GridLayoutManager(context, 2)
        videoContentObserver = VideoContentObserver(handler)

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeViews(view)
        loadVideos()
    }

    private fun initializeViews(view: View) {
        view.findViewById<ImageButton>(R.id.btn_sort).setOnClickListener {
            showSortDialog()
        }
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
            Log.e("VideoPageFragment", "Error registering content observer", e)
        }

        handler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        requireActivity().unregisterReceiver(searchReceiver)
        requireActivity().unregisterReceiver(sortReceiver)
        try { requireContext().contentResolver.unregisterContentObserver(videoContentObserver) } catch (e: Exception) {}

        loadJob?.cancel()
        handler.removeCallbacks(refreshRunnable)
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

                generateUiList()

                if (!preserveState) {
                    loadingProgress.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                Log.e("VideoPageFragment", "Error loading videos", e)
                loadingProgress.visibility = View.GONE
            }
        }
    }

    // Logic to switch between Showing Folders vs Showing Videos
    private fun generateUiList() {
        currentUiList.clear()

        if (isInFolderView && activeFolderName != null) {
            // 1. Show Videos inside a specific Folder
            pageTitle.text = activeFolderName
            val folderVideos = videoList.filter { getSmartGroupKey(it) == activeFolderName }

            // Sort videos inside folder
            val sortedVideos = if (currentSortType == MainActivity.SortType.NAME_ASC) {
                folderVideos.sortedBy { it.title.lowercase() }
            } else {
                folderVideos.sortedByDescending { it.dateAdded }
            }

            currentUiList.addAll(sortedVideos.map { VideoUiModel.VideoItem(it) })

        } else if (isFolderSort) {
            // 2. Show List of Folders
            pageTitle.text = "Folders"
            val grouped = videoList.groupBy { getSmartGroupKey(it) }

            val folders = grouped.map { (name, videos) ->
                VideoUiModel.FolderItem(name, videos.size, videos.firstOrNull())
            }

            // Sort folders
            val sortedFolders = if (isFolderSortAsc) {
                folders.sortedBy { it.name.lowercase() }
            } else {
                folders.sortedByDescending { it.name.lowercase() }
            }

            currentUiList.addAll(sortedFolders)

        } else {
            // 3. Show All Videos Flat
            pageTitle.text = "Videos"
            applyStandardSort() // Sorts videoList
            currentUiList.addAll(videoList.map { VideoUiModel.VideoItem(it) })
        }

        updateAdapter()
    }

    private fun applyStandardSort() {
        when (currentSortType) {
            MainActivity.SortType.NAME_ASC -> videoList.sortBy { it.title.lowercase() }
            MainActivity.SortType.NAME_DESC -> videoList.sortByDescending { it.title.lowercase() }
            MainActivity.SortType.DATE_ADDED_ASC -> videoList.sortBy { it.dateAdded }
            MainActivity.SortType.DATE_ADDED_DESC -> videoList.sortByDescending { it.dateAdded }
            MainActivity.SortType.DURATION -> videoList.sortByDescending { it.duration }
        }
    }

    private fun getSmartGroupKey(video: Video): String {
        val title = video.title.trim()
        if (title.startsWith("VID-WA", ignoreCase = true) || title.startsWith("WhatsApp Video", ignoreCase = true)) {
            return "WhatsApp Videos"
        }
        val seriesPattern = Pattern.compile("^(.*?)[\\s\\.\\-_]+S\\d+", Pattern.CASE_INSENSITIVE)
        val matcher = seriesPattern.matcher(title)
        if (matcher.find()) {
            val seriesName = matcher.group(1)?.trim()
            if (!seriesName.isNullOrEmpty()) return seriesName
        }
        return try {
            File(video.path).parentFile?.name ?: "Unknown Folder"
        } catch (e: Exception) {
            "Unknown Folder"
        }
    }

    private fun filterVideos(query: String) {
        // Search always searches everything and shows flat list
        if (query.isNotBlank()) {
            currentUiList.clear()
            val filtered = videoList.filter { it.title.contains(query, true) }
            currentUiList.addAll(filtered.map { VideoUiModel.VideoItem(it) })
            updateAdapter()
        } else {
            generateUiList() // Restore original view
        }
    }

    @OptIn(UnstableApi::class)
    private fun updateAdapter() {
        if (!isAdded) return

        val adapter = VideoAdapter(
            requireContext(),
            currentUiList,
            onItemClick = { item ->
                when (item) {
                    is VideoUiModel.VideoItem -> {
                        // Play Video
                        val intent = Intent(requireContext(), VideoPlayerActivity::class.java).apply {
                            putExtra("VIDEO_ID", item.video.id)
                            putExtra("VIDEO_TITLE", item.video.title)
                        }
                        startActivity(intent)
                    }
                    is VideoUiModel.FolderItem -> {
                        // Enter Folder
                        enterFolder(item.name)
                    }
                }
            },
            onDeleteRequest = { video -> requestDeleteVideo(video) }
        )
        recyclerView.adapter = adapter

        emptyView.visibility = if (currentUiList.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (currentUiList.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun enterFolder(folderName: String) {
        isInFolderView = true
        activeFolderName = folderName
        generateUiList()
    }

    // Public method for HomePageFragment to call
    fun handleBackPress(): Boolean {
        if (isInFolderView) {
            isInFolderView = false
            activeFolderName = null
            generateUiList() // Go back to folder list
            return true
        }
        return false
    }

    private fun requestDeleteVideo(video: Video) {
        try {
            val intentSender = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val uris = listOf(video.uri)
                MediaStore.createDeleteRequest(requireContext().contentResolver, uris).intentSender
            } else {
                null
            }

            if (intentSender != null) {
                val request = IntentSenderRequest.Builder(intentSender).build()
                deleteResultLauncher.launch(request)
            } else {
                // Pre-R deletion
                requireContext().contentResolver.delete(video.uri, null, null)
                loadVideos()
            }
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
                val request = IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build()
                deleteResultLauncher.launch(request)
            } else {
                Toast.makeText(requireContext(), "Cannot delete video", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showSortDialog() {
        val sortOptions = mutableListOf(
            "Name (A-Z)" to MainActivity.SortType.NAME_ASC,
            "Name (Z-A)" to MainActivity.SortType.NAME_DESC,
            "Date Added (Newest)" to MainActivity.SortType.DATE_ADDED_DESC,
            "Date Added (Oldest)" to MainActivity.SortType.DATE_ADDED_ASC,
            "Duration" to MainActivity.SortType.DURATION
        )

        val optionsList = sortOptions.map { it.first }.toMutableList()
        optionsList.add("Folder (A-Z)")
        optionsList.add("Folder (Z-A)")

        val items = optionsList.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("Sort videos by")
            .setItems(items) { _, which ->
                if (which < sortOptions.size) {
                    isFolderSort = false
                    val selectedSort = sortOptions[which].second
                    currentSortType = selectedSort
                    PreferenceManager.saveSortPreference(requireContext(), "videos", selectedSort)
                } else {
                    isFolderSort = true
                    isInFolderView = false // Reset navigation
                    isFolderSortAsc = (items[which] == "Folder (A-Z)")
                }

                loadVideos()
                recyclerView.smoothScrollToPosition(0)
            }
            .show()
    }

    // Helpers for state preservation
    fun refreshData() { if (isAdded) loadVideos() }
    fun refreshDataPreserveState() { if (isAdded) loadVideos(preserveState = true) }
}