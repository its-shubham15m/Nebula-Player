package com.shubhamgupta.nebula_player.fragments

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shubhamgupta.nebula_player.MainActivity
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.adapters.SongAdapter
import com.shubhamgupta.nebula_player.models.Song
import com.shubhamgupta.nebula_player.repository.SongRepository
import com.shubhamgupta.nebula_player.utils.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SongsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var loadingProgress: ProgressBar
    private val songList = mutableListOf<Song>()
    private var filteredSongList = mutableListOf<Song>()
    private var currentSortType = MainActivity.SortType.NAME_ASC
    private var currentQuery = ""
    private var isFirstLoad = true
    private val handler = Handler(Looper.getMainLooper())
    private var loadJob: Job? = null
    private var scrollPosition = 0
    private var scrollOffset = 0

    // NEW: ActivityResultLauncher to handle the deletion confirmation result
    private lateinit var deleteResultLauncher: ActivityResultLauncher<IntentSenderRequest>

    // ... (BroadcastReceivers are unchanged) ...
    private val searchReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "SEARCH_QUERY_CHANGED") {
                currentQuery = intent.getStringExtra("query") ?: ""
                filterSongs()
            }
        }
    }

    fun setScrollingEnabled(enabled: Boolean) {
        try {
            if (this::recyclerView.isInitialized) {
                recyclerView.isNestedScrollingEnabled = enabled
                recyclerView.isEnabled = enabled
                if (!enabled) {
                    recyclerView.setOnTouchListener { _, _ -> true }
                } else {
                    recyclerView.setOnTouchListener(null)
                }
            }
        } catch (e: Exception) {
            Log.e("SongsFragment", "Error setting scrolling enabled: $enabled", e)
        }
    }

    // ... (Scroll state methods are unchanged) ...

    fun saveScrollState() {
        if (this::recyclerView.isInitialized) {
            val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
            layoutManager?.let {
                scrollPosition = it.findFirstVisibleItemPosition()
                val firstVisibleView = it.findViewByPosition(scrollPosition)
                scrollOffset = firstVisibleView?.top ?: 0
            }
        }
    }

    fun restoreScrollState() {
        if (this::recyclerView.isInitialized) {
            val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
            layoutManager?.let {
                handler.postDelayed({
                    it.scrollToPositionWithOffset(scrollPosition, scrollOffset)
                }, 100)
            }
        }
    }

    private val sortReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "SORT_SONGS") {
                val sortTypeOrdinal = intent.getIntExtra("sort_type", 0)
                val newSortType = MainActivity.SortType.entries.toTypedArray()[sortTypeOrdinal]
                if (newSortType != currentSortType) {
                    currentSortType = newSortType
                    PreferenceManager.saveSortPreference(requireContext(), "songs", currentSortType)
                    loadSongs()
                }
            }
        }
    }
    private val playbackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "SONG_CHANGED", "PLAYBACK_STATE_CHANGED" -> {
                    recyclerView.adapter?.notifyDataSetChanged()
                }
            }
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "FORCE_REFRESH_SONGS") {
                loadSongs()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentSortType = PreferenceManager.getSortPreferenceWithDefault(requireContext(), "songs")

        // NEW: Initialize the launcher
        deleteResultLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Toast.makeText(requireContext(), "Song deleted successfully", Toast.LENGTH_SHORT).show()
                // The file is deleted, now refresh the UI
                refreshData()
            } else {
                Toast.makeText(requireContext(), "Song could not be deleted", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_songs_list, container, false)
        recyclerView = view.findViewById(R.id.songs_recycler_view)
        emptyView = view.findViewById(R.id.tv_empty_songs)
        loadingProgress = view.findViewById(R.id.loading_progress)
        recyclerView.layoutManager = LinearLayoutManager(context)
        return view
    }

    // ... (onViewCreated, onResume, onPause are mostly unchanged) ...

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (songList.isNotEmpty()) {
            updateAdapter()
            restoreScrollState()
        } else {
            showLoading()
        }
    }

    override fun onResume() {
        super.onResume()
        val searchFilter = IntentFilter("SEARCH_QUERY_CHANGED")
        val sortFilter = IntentFilter("SORT_SONGS")
        val refreshFilter = IntentFilter("FORCE_REFRESH_SONGS")
        val playbackFilter = IntentFilter().apply {
            addAction("SONG_CHANGED")
            addAction("PLAYBACK_STATE_CHANGED")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireActivity().registerReceiver(searchReceiver, searchFilter, Context.RECEIVER_NOT_EXPORTED)
            requireActivity().registerReceiver(sortReceiver, sortFilter, Context.RECEIVER_NOT_EXPORTED)
            requireActivity().registerReceiver(refreshReceiver, refreshFilter, Context.RECEIVER_NOT_EXPORTED)
            requireActivity().registerReceiver(playbackReceiver, playbackFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            requireActivity().registerReceiver(searchReceiver, searchFilter)
            requireActivity().registerReceiver(sortReceiver, sortFilter)
            requireActivity().registerReceiver(refreshReceiver, refreshFilter)
            requireActivity().registerReceiver(playbackReceiver, playbackFilter)
        }

        val savedSortType = PreferenceManager.getSortPreferenceWithDefault(requireContext(), "songs")
        if (savedSortType != currentSortType) {
            currentSortType = savedSortType
            loadSongs()
        } else if (isFirstLoad) {
            loadSongs()
            isFirstLoad = false
        } else {
            loadSongsPreserveState()
        }
    }

    override fun onPause() {
        super.onPause()
        requireActivity().unregisterReceiver(searchReceiver)
        requireActivity().unregisterReceiver(sortReceiver)
        requireActivity().unregisterReceiver(refreshReceiver)
        try {
            requireActivity().unregisterReceiver(playbackReceiver)
        } catch (e: Exception) {
            // Ignore
        }
        loadJob?.cancel()
        saveScrollState()
    }

    // ... (refreshData, showLoading, loadSongs, etc. are unchanged) ...
    fun refreshData() {
        if (isAdded) loadSongs()
    }

    fun refreshDataPreserveState() {
        if (isAdded) loadSongsPreserveState()
    }

    private fun showLoading() {
        loadingProgress.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.GONE
    }

    private fun hideLoading() {
        loadingProgress.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
    }

    private fun loadSongs() {
        if (songList.isEmpty()) {
            showLoading()
        }
        loadJob?.cancel()
        loadJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                val allSongs = SongRepository.getAllSongs(requireContext())
                songList.clear()
                songList.addAll(allSongs)
                applyCurrentSort()
            } catch (e: Exception) {
                hideLoading()
                emptyView.visibility = View.VISIBLE
            }
        }
    }

    private fun loadSongsPreserveState() {
        loadJob?.cancel()
        loadJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                val allSongs = SongRepository.getAllSongs(requireContext())
                songList.clear()
                songList.addAll(allSongs)
                applyCurrentSortPreserveState()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private fun applyCurrentSort() {
        when (currentSortType) {
            MainActivity.SortType.NAME_ASC -> songList.sortBy { it.title.lowercase() }
            MainActivity.SortType.NAME_DESC -> songList.sortByDescending { it.title.lowercase() }
            MainActivity.SortType.DATE_ADDED_ASC -> songList.sortBy { it.dateAdded }
            MainActivity.SortType.DATE_ADDED_DESC -> songList.sortByDescending { it.dateAdded }
            MainActivity.SortType.DURATION -> songList.sortByDescending { it.duration }
        }
        filterSongs()
    }

    private fun applyCurrentSortPreserveState() {
        applyCurrentSort()
        handler.postDelayed({
            restoreScrollState()
        }, 200)
    }

    private fun filterSongs() {
        filteredSongList = if (currentQuery.isBlank()) {
            songList.toMutableList()
        } else {
            songList.filter { song ->
                song.title.contains(currentQuery, true) ||
                        song.artist?.contains(currentQuery, true) == true ||
                        song.album?.contains(currentQuery, true) == true
            }.toMutableList()
        }
        updateAdapter()
    }

    // UPDATED: This now passes the onDeleteRequest lambda to the adapter
    private fun updateAdapter() {
        if (!isAdded) return
        val adapter = SongAdapter(
            context = requireContext(),
            songs = filteredSongList,
            onItemClick = { pos -> openNowPlaying(pos) },
            onDataChanged = { refreshData() },
            onDeleteRequest = { song -> requestDeleteSong(song) }
        )
        recyclerView.adapter = adapter
        hideLoading()
        emptyView.visibility = if (filteredSongList.isEmpty()) View.VISIBLE else View.GONE
    }

    // NEW: This method handles the logic for requesting file deletion
    private fun requestDeleteSong(song: Song) {
        try {
            val intentSender = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val uris = listOf(song.uri)
                MediaStore.createDeleteRequest(requireContext().contentResolver, uris).intentSender
            } else {
                // For Android 10, try to catch RecoverableSecurityException
                // This is a fallback and might not always work as intended
                null
            }

            if (intentSender != null) {
                val request = IntentSenderRequest.Builder(intentSender).build()
                deleteResultLauncher.launch(request)
            } else {
                // Fallback for older versions or if createDeleteRequest fails
                Toast.makeText(requireContext(), "Could not request deletion.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
                val request = IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build()
                deleteResultLauncher.launch(request)
            } else {
                Toast.makeText(requireContext(), "Permission denied to delete this song.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openNowPlaying(position: Int) {
        val service = (requireActivity() as MainActivity).getMusicService()
        service?.startPlayback(ArrayList(filteredSongList), position)
        (requireActivity() as MainActivity).navigateToNowPlaying()
    }
}