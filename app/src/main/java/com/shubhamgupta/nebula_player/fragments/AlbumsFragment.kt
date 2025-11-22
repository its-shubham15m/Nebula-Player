package com.shubhamgupta.nebula_player.fragments

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shubhamgupta.nebula_player.MainActivity
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.adapters.AlbumAdapter
import com.shubhamgupta.nebula_player.models.Album
import com.shubhamgupta.nebula_player.repository.SongRepository
import com.shubhamgupta.nebula_player.utils.PreferenceManager
// Explicit import to ensure reference is resolved
import com.shubhamgupta.nebula_player.fragments.AlbumSongsFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class AlbumsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var loadingProgress: ProgressBar
    private val albumList = mutableListOf<Album>()
    private var filteredAlbumList = mutableListOf<Album>()
    private var currentSortType = MainActivity.SortType.NAME_ASC
    private var currentQuery = ""
    private var isFirstLoad = true
    private val handler = Handler(Looper.getMainLooper())
    private var loadJob: Job? = null

    // Scroll state management
    private var scrollPosition = 0
    private var scrollOffset = 0

    private val searchReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "SEARCH_QUERY_CHANGED") {
                currentQuery = intent.getStringExtra("query") ?: ""
                filterAlbums()
            }
        }
    }

    fun setScrollingEnabled(enabled: Boolean) {
        try {
            if (this::recyclerView.isInitialized) {
                recyclerView.isNestedScrollingEnabled = enabled
                recyclerView.isEnabled = enabled

                if (!enabled) {
                    recyclerView.setOnTouchListener { _, _ -> true } // Block touches
                } else {
                    recyclerView.setOnTouchListener(null) // Allow touches
                }
            }
        } catch (e: Exception) {
            Log.e("AlbumsFragment", "Error setting scrolling enabled: $enabled", e)
        }
    }

    fun saveScrollState() {
        if (this::recyclerView.isInitialized) {
            val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
            layoutManager?.let {
                scrollPosition = it.findFirstVisibleItemPosition()
                val firstVisibleView = it.findViewByPosition(scrollPosition)
                scrollOffset = firstVisibleView?.top ?: 0
                Log.d("AlbumsFragment", "Saved scroll state: position=$scrollPosition, offset=$scrollOffset")
            }
        }
    }

    fun restoreScrollState() {
        if (this::recyclerView.isInitialized) {
            val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
            layoutManager?.let {
                handler.postDelayed({
                    it.scrollToPositionWithOffset(scrollPosition, scrollOffset)
                    Log.d("AlbumsFragment", "Restored scroll state: position=$scrollPosition, offset=$scrollOffset")
                }, 100)
            }
        }
    }

    private val sortReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "SORT_ALBUMS") {
                val sortTypeOrdinal = intent.getIntExtra("sort_type", 0)
                val newSortType = MainActivity.SortType.entries.toTypedArray()[sortTypeOrdinal]
                if (newSortType != currentSortType) {
                    currentSortType = newSortType
                    PreferenceManager.saveSortPreference(requireContext(), "albums", currentSortType)
                    loadAlbums()
                }
            }
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "FORCE_REFRESH_ALBUMS") {
                loadAlbums()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentSortType = PreferenceManager.getSortPreferenceWithDefault(requireContext(), "albums")
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_category_list, container, false)
        recyclerView = view.findViewById(R.id.category_recycler_view)
        emptyView = view.findViewById(R.id.tv_empty_category)
        loadingProgress = view.findViewById(R.id.loading_progress)
        recyclerView.layoutManager = LinearLayoutManager(context)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Add padding to bottom so last item isn't hidden by miniplayer
        val bottomPadding = (140 * resources.displayMetrics.density).toInt()
        recyclerView.clipToPadding = false
        recyclerView.setPadding(0, 0, 0, bottomPadding)

        if (albumList.isNotEmpty()) {
            updateAdapter()
            restoreScrollState()
        } else {
            showLoading()
        }
    }

    override fun onResume() {
        super.onResume()
        val searchFilter = IntentFilter("SEARCH_QUERY_CHANGED")
        val sortFilter = IntentFilter("SORT_ALBUMS")
        val refreshFilter = IntentFilter("FORCE_REFRESH_ALBUMS")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireActivity().registerReceiver(searchReceiver, searchFilter, Context.RECEIVER_NOT_EXPORTED)
            requireActivity().registerReceiver(sortReceiver, sortFilter, Context.RECEIVER_NOT_EXPORTED)
            requireActivity().registerReceiver(refreshReceiver, refreshFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            requireActivity().registerReceiver(searchReceiver, searchFilter)
            requireActivity().registerReceiver(sortReceiver, sortFilter)
            requireActivity().registerReceiver(refreshReceiver, refreshFilter)
        }

        val savedSortType = PreferenceManager.getSortPreferenceWithDefault(requireContext(), "albums")
        if (savedSortType != currentSortType) {
            currentSortType = savedSortType
            loadAlbums()
        } else if (isFirstLoad) {
            loadAlbums()
            isFirstLoad = false
        } else {
            loadAlbumsPreserveState()
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            requireActivity().unregisterReceiver(searchReceiver)
            requireActivity().unregisterReceiver(sortReceiver)
            requireActivity().unregisterReceiver(refreshReceiver)
        } catch (e: Exception) { Log.e("AlbumsFragment", "Error unregistering receivers", e) }
        loadJob?.cancel()
        saveScrollState()
    }

    fun refreshData() {
        if (isAdded) loadAlbums()
    }

    fun refreshDataPreserveState() {
        if (isAdded) loadAlbumsPreserveState()
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

    private fun loadAlbums() {
        if (albumList.isEmpty()) {
            showLoading()
        }
        loadJob?.cancel()
        loadJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                val songs = SongRepository.getAllSongs(requireContext())
                val albumMap = songs.groupBy { it.album ?: "Unknown Album" }
                    .mapValues { entry ->
                        val firstSong = entry.value.first()
                        Album(
                            name = entry.key,
                            artist = firstSong.artist ?: "Unknown Artist",
                            songCount = entry.value.size,
                            songs = entry.value.toMutableList(),
                            albumId = firstSong.albumId
                        )
                    }
                albumList.clear()
                albumList.addAll(albumMap.values)
                applyCurrentSort()
            } catch (e: Exception) {
                Log.e("AlbumsFragment", "Error loading albums", e)
                hideLoading()
                emptyView.visibility = View.VISIBLE
            }
        }
    }

    private fun loadAlbumsPreserveState() {
        loadJob?.cancel()
        loadJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                val songs = SongRepository.getAllSongs(requireContext())
                val albumMap = songs.groupBy { it.album ?: "Unknown Album" }
                    .mapValues { entry ->
                        val firstSong = entry.value.first()
                        Album(
                            name = entry.key,
                            artist = firstSong.artist ?: "Unknown Artist",
                            songCount = entry.value.size,
                            songs = entry.value.toMutableList(),
                            albumId = firstSong.albumId
                        )
                    }
                albumList.clear()
                albumList.addAll(albumMap.values)
                applyCurrentSortPreserveState()
            } catch (e: Exception) {
                Log.e("AlbumsFragment", "Error in loadAlbumsPreserveState", e)
            }
        }
    }

    private fun applyCurrentSort() {
        when (currentSortType) {
            MainActivity.SortType.NAME_ASC -> albumList.sortBy { it.name.lowercase() }
            MainActivity.SortType.NAME_DESC -> albumList.sortByDescending { it.name.lowercase() }
            else -> albumList.sortBy { it.name.lowercase() }
        }
        filterAlbums()
    }

    private fun applyCurrentSortPreserveState() {
        applyCurrentSort()
        handler.postDelayed({ restoreScrollState() }, 200)
    }

    private fun filterAlbums() {
        filteredAlbumList = if (currentQuery.isBlank()) {
            albumList.toMutableList()
        } else {
            albumList.filter { album ->
                album.name.contains(currentQuery, true) || album.artist.contains(currentQuery, true)
            }.toMutableList()
        }
        updateAdapter()
    }

    private fun updateAdapter() {
        if (!isAdded) return
        val adapter = AlbumAdapter(
            albums = filteredAlbumList,
            onAlbumClick = { position -> openAlbumSongs(position) }
        )
        recyclerView.adapter = adapter
        hideLoading()
        emptyView.visibility = if (filteredAlbumList.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openAlbumSongs(position: Int) {
        if (position < 0 || position >= filteredAlbumList.size) return
        val album = filteredAlbumList[position]
        val fragment = AlbumSongsFragment.newInstance(album)

        // Navigation Logic: Check if we are inside HomePageFragment to handle MiniPlayer correctly
        // (MusicPageFragment -> HomePageFragment)
        val parent = parentFragment?.parentFragment // MusicPage -> Home

        if (parent is HomePageFragment) {
            parent.childFragmentManager.beginTransaction()
                .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right)
                .replace(R.id.home_content_container, fragment)
                .addToBackStack("album_songs")
                .commit()
            parent.updateMiniPlayerPosition()
        } else {
            // Fallback for flat structure
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .addToBackStack("album_songs")
                .commit()
        }
    }
}