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
import com.shubhamgupta.nebula_player.adapters.ArtistAdapter
import com.shubhamgupta.nebula_player.models.Artist
import com.shubhamgupta.nebula_player.repository.SongRepository
import com.shubhamgupta.nebula_player.utils.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ArtistsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var loadingProgress: ProgressBar
    private val artistList = mutableListOf<Artist>()
    private var filteredArtistList = mutableListOf<Artist>()
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
                filterArtists()
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
            Log.e("ArtistsFragment", "Error setting scrolling enabled: $enabled", e)
        }
    }

    fun saveScrollState() {
        if (this::recyclerView.isInitialized) {
            val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
            layoutManager?.let {
                scrollPosition = it.findFirstVisibleItemPosition()
                val firstVisibleView = it.findViewByPosition(scrollPosition)
                scrollOffset = firstVisibleView?.top ?: 0
                Log.d("ArtistsFragment", "Saved scroll state: position=$scrollPosition, offset=$scrollOffset")
            }
        }
    }

    fun restoreScrollState() {
        if (this::recyclerView.isInitialized) {
            val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
            layoutManager?.let {
                handler.postDelayed({
                    it.scrollToPositionWithOffset(scrollPosition, scrollOffset)
                    Log.d("ArtistsFragment", "Restored scroll state: position=$scrollPosition, offset=$scrollOffset")
                }, 100)
            }
        }
    }

    private val sortReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "SORT_ARTISTS") {
                val sortTypeOrdinal = intent.getIntExtra("sort_type", 0)
                val newSortType = MainActivity.SortType.entries.toTypedArray()[sortTypeOrdinal]
                if (newSortType != currentSortType) {
                    currentSortType = newSortType
                    PreferenceManager.saveSortPreference(requireContext(), "artists", currentSortType)
                    loadArtists()
                }
            }
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "FORCE_REFRESH_ARTISTS") {
                loadArtists()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentSortType = PreferenceManager.getSortPreferenceWithDefault(requireContext(), "artists")
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
        if (artistList.isNotEmpty()) {
            updateAdapter()
            restoreScrollState()
        } else {
            showLoading()
        }
    }

    override fun onResume() {
        super.onResume()
        val searchFilter = IntentFilter("SEARCH_QUERY_CHANGED")
        val sortFilter = IntentFilter("SORT_ARTISTS")
        val refreshFilter = IntentFilter("FORCE_REFRESH_ARTISTS")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireActivity().registerReceiver(searchReceiver, searchFilter, Context.RECEIVER_NOT_EXPORTED)
            requireActivity().registerReceiver(sortReceiver, sortFilter, Context.RECEIVER_NOT_EXPORTED)
            requireActivity().registerReceiver(refreshReceiver, refreshFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            requireActivity().registerReceiver(searchReceiver, searchFilter)
            requireActivity().registerReceiver(sortReceiver, sortFilter)
            requireActivity().registerReceiver(refreshReceiver, refreshFilter)
        }

        val savedSortType = PreferenceManager.getSortPreferenceWithDefault(requireContext(), "artists")
        if (savedSortType != currentSortType) {
            currentSortType = savedSortType
            loadArtists()
        } else if (isFirstLoad) {
            loadArtists()
            isFirstLoad = false
        } else {
            loadArtistsPreserveState()
        }
    }

    override fun onPause() {
        super.onPause()
        requireActivity().unregisterReceiver(searchReceiver)
        requireActivity().unregisterReceiver(sortReceiver)
        requireActivity().unregisterReceiver(refreshReceiver)
        loadJob?.cancel()
        saveScrollState()
    }

    fun refreshData() {
        if (isAdded) loadArtists()
    }

    fun refreshDataPreserveState() {
        if (isAdded) loadArtistsPreserveState()
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

    private fun loadArtists() {
        if (artistList.isEmpty()) {
            showLoading()
        }
        loadJob?.cancel()
        loadJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                val songs = SongRepository.getAllSongs(requireContext())
                val artistMap = songs.groupBy { it.artist ?: "Unknown Artist" }
                    .mapValues { entry ->
                        Artist(
                            name = entry.key,
                            songCount = entry.value.size,
                            songs = entry.value.toMutableList()
                        )
                    }
                artistList.clear()
                artistList.addAll(artistMap.values)
                applyCurrentSort()
            } catch (e: Exception) {
                Log.e("ArtistsFragment", "Error loading artists", e)
                hideLoading()
                emptyView.visibility = View.VISIBLE
            }
        }
    }

    private fun loadArtistsPreserveState() {
        loadJob?.cancel()
        loadJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                val songs = SongRepository.getAllSongs(requireContext())
                val artistMap = songs.groupBy { it.artist ?: "Unknown Artist" }
                    .mapValues { entry ->
                        Artist(
                            name = entry.key,
                            songCount = entry.value.size,
                            songs = entry.value.toMutableList()
                        )
                    }
                artistList.clear()
                artistList.addAll(artistMap.values)
                applyCurrentSortPreserveState()
            } catch (e: Exception) {
                Log.e("ArtistsFragment", "Error in loadArtistsPreserveState", e)
            }
        }
    }

    private fun applyCurrentSort() {
        when (currentSortType) {
            MainActivity.SortType.NAME_ASC -> artistList.sortBy { it.name.lowercase() }
            MainActivity.SortType.NAME_DESC -> artistList.sortByDescending { it.name.lowercase() }
            else -> artistList.sortBy { it.name.lowercase() }
        }
        filterArtists()
    }

    private fun applyCurrentSortPreserveState() {
        applyCurrentSort()
        handler.postDelayed({ restoreScrollState() }, 200)
    }

    private fun filterArtists() {
        filteredArtistList = if (currentQuery.isBlank()) {
            artistList.toMutableList()
        } else {
            artistList.filter { it.name.contains(currentQuery, true) }.toMutableList()
        }
        updateAdapter()
    }

    private fun updateAdapter() {
        if (!isAdded) return
        val adapter = ArtistAdapter(
            artists = filteredArtistList,
            onArtistClick = { position -> openArtistSongs(position) }
        )
        recyclerView.adapter = adapter
        hideLoading()
        emptyView.visibility = if (filteredArtistList.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openArtistSongs(position: Int) {
        val artist = filteredArtistList[position]
        val fragment = ArtistSongsFragment.newInstance(artist)
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack("artist_songs")
            .commit()
    }
}