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
import com.shubhamgupta.nebula_player.adapters.GenreAdapter
import com.shubhamgupta.nebula_player.models.Genre
import com.shubhamgupta.nebula_player.repository.SongRepository
import com.shubhamgupta.nebula_player.utils.PreferenceManager
// Explicit import
import com.shubhamgupta.nebula_player.fragments.GenreSongsFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class GenresFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var loadingProgress: ProgressBar
    private val genreList = mutableListOf<Genre>()
    private var filteredGenreList = mutableListOf<Genre>()
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
                filterGenres()
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
            Log.e("GenresFragment", "Error setting scrolling enabled: $enabled", e)
        }
    }

    fun saveScrollState() {
        if (this::recyclerView.isInitialized) {
            val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
            layoutManager?.let {
                scrollPosition = it.findFirstVisibleItemPosition()
                val firstVisibleView = it.findViewByPosition(scrollPosition)
                scrollOffset = firstVisibleView?.top ?: 0
                Log.d("GenresFragment", "Saved scroll state: position=$scrollPosition, offset=$scrollOffset")
            }
        }
    }

    fun restoreScrollState() {
        if (this::recyclerView.isInitialized) {
            val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
            layoutManager?.let {
                handler.postDelayed({
                    it.scrollToPositionWithOffset(scrollPosition, scrollOffset)
                    Log.d("GenresFragment", "Restored scroll state: position=$scrollPosition, offset=$scrollOffset")
                }, 100)
            }
        }
    }

    private val sortReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "SORT_GENRES") {
                val sortTypeOrdinal = intent.getIntExtra("sort_type", 0)
                val newSortType = MainActivity.SortType.entries.toTypedArray()[sortTypeOrdinal]
                if (newSortType != currentSortType) {
                    currentSortType = newSortType
                    PreferenceManager.saveSortPreference(requireContext(), "genres", currentSortType)
                    loadGenres()
                }
            }
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "FORCE_REFRESH_GENRES") {
                loadGenres()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentSortType = PreferenceManager.getSortPreferenceWithDefault(requireContext(), "genres")
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
        // Fix: Add Padding for MiniPlayer
        val bottomPadding = (140 * resources.displayMetrics.density).toInt()
        recyclerView.clipToPadding = false
        recyclerView.setPadding(0, 0, 0, bottomPadding)

        if (genreList.isNotEmpty()) {
            updateAdapter()
            restoreScrollState()
        } else {
            showLoading()
        }
    }

    override fun onResume() {
        super.onResume()
        val searchFilter = IntentFilter("SEARCH_QUERY_CHANGED")
        val sortFilter = IntentFilter("SORT_GENRES")
        val refreshFilter = IntentFilter("FORCE_REFRESH_GENRES")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireActivity().registerReceiver(searchReceiver, searchFilter, Context.RECEIVER_NOT_EXPORTED)
            requireActivity().registerReceiver(sortReceiver, sortFilter, Context.RECEIVER_NOT_EXPORTED)
            requireActivity().registerReceiver(refreshReceiver, refreshFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            requireActivity().registerReceiver(searchReceiver, searchFilter)
            requireActivity().registerReceiver(sortReceiver, sortFilter)
            requireActivity().registerReceiver(refreshReceiver, refreshFilter)
        }

        val savedSortType = PreferenceManager.getSortPreferenceWithDefault(requireContext(), "genres")
        if (savedSortType != currentSortType) {
            currentSortType = savedSortType
            loadGenres()
        } else if (isFirstLoad) {
            loadGenres()
            isFirstLoad = false
        } else {
            loadGenresPreserveState()
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            requireActivity().unregisterReceiver(searchReceiver)
            requireActivity().unregisterReceiver(sortReceiver)
            requireActivity().unregisterReceiver(refreshReceiver)
        } catch (e: Exception) {}
        loadJob?.cancel()
        saveScrollState()
    }

    fun refreshData() {
        if (isAdded) loadGenres()
    }

    fun refreshDataPreserveState() {
        if (isAdded) loadGenresPreserveState()
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

    private fun loadGenres() {
        if (genreList.isEmpty()) {
            showLoading()
        }
        loadJob?.cancel()
        loadJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                val songs = SongRepository.getAllSongs(requireContext())
                val genreMap = songs.groupBy { it.genre ?: "Unknown Genre" }
                    .mapValues { entry ->
                        Genre(
                            name = entry.key,
                            songCount = entry.value.size,
                            songs = entry.value.toMutableList()
                        )
                    }
                genreList.clear()
                genreList.addAll(genreMap.values)
                applyCurrentSort()
            } catch (e: Exception) {
                Log.e("GenresFragment", "Error loading genres", e)
                hideLoading()
                emptyView.visibility = View.VISIBLE
            }
        }
    }

    private fun loadGenresPreserveState() {
        loadJob?.cancel()
        loadJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                val songs = SongRepository.getAllSongs(requireContext())
                val genreMap = songs.groupBy { it.genre ?: "Unknown Genre" }
                    .mapValues { entry ->
                        Genre(
                            name = entry.key,
                            songCount = entry.value.size,
                            songs = entry.value.toMutableList()
                        )
                    }
                genreList.clear()
                genreList.addAll(genreMap.values)
                applyCurrentSortPreserveState()
            } catch (e: Exception) {
                Log.e("GenresFragment", "Error in loadGenresPreserveState", e)
            }
        }
    }

    private fun applyCurrentSort() {
        when (currentSortType) {
            MainActivity.SortType.NAME_ASC -> genreList.sortBy { it.name.lowercase() }
            MainActivity.SortType.NAME_DESC -> genreList.sortByDescending { it.name.lowercase() }
            else -> genreList.sortBy { it.name.lowercase() }
        }
        filterGenres()
    }

    private fun applyCurrentSortPreserveState() {
        applyCurrentSort()
        handler.postDelayed({ restoreScrollState() }, 200)
    }

    private fun filterGenres() {
        filteredGenreList = if (currentQuery.isBlank()) {
            genreList.toMutableList()
        } else {
            genreList.filter { it.name.contains(currentQuery, true) }.toMutableList()
        }
        updateAdapter()
    }

    private fun updateAdapter() {
        if (!isAdded) return
        val adapter = GenreAdapter(
            genres = filteredGenreList,
            onGenreClick = { position -> openGenreSongs(position) }
        )
        recyclerView.adapter = adapter
        hideLoading()
        emptyView.visibility = if (filteredGenreList.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openGenreSongs(position: Int) {
        if (position < 0 || position >= filteredGenreList.size) return
        val genre = filteredGenreList[position]
        val fragment = GenreSongsFragment.newInstance(genre)

        // Navigation Logic
        val parent = parentFragment?.parentFragment // MusicPage -> Home

        if (parent is HomePageFragment) {
            parent.childFragmentManager.beginTransaction()
                .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right)
                .replace(R.id.home_content_container, fragment)
                .addToBackStack("genre_songs")
                .commit()
            parent.updateMiniPlayerPosition()
        } else {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .addToBackStack("genre_songs")
                .commit()
        }
    }
}