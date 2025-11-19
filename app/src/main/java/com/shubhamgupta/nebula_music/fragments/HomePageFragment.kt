package com.shubhamgupta.nebula_music.fragments

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.shubhamgupta.nebula_music.MainActivity
import com.shubhamgupta.nebula_music.R
import com.shubhamgupta.nebula_music.models.Playlist
import com.shubhamgupta.nebula_music.models.Song
import com.shubhamgupta.nebula_music.repository.SongRepository
import com.shubhamgupta.nebula_music.utils.PreferenceManager
import com.shubhamgupta.nebula_music.utils.SongUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class HomePageFragment : Fragment() {
    private var currentCategory = "songs"
    private lateinit var searchBar: EditText
    private lateinit var handler: Handler
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var viewPager: ViewPager2

    // Cache for child fragments
    private val childFragmentCache = mutableMapOf<String, Fragment>()
    private var isDataLoaded = false

    private lateinit var imgFavoritesOverlay: ImageView
    private lateinit var imgPlaylistsOverlay: ImageView
    private lateinit var imgRecentOverlay: ImageView

    private var isDrawerOpen = false

    private val voiceRecognitionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val results = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            results?.get(0)?.let { spokenText ->
                // 1. Navigate to Search Page
                (requireActivity() as? MainActivity)?.showSearchPage()

                // 2. Wait for transition, then broadcast the query so SearchFragment picks it up
                handler.postDelayed({
                    val intent = Intent("SEARCH_QUERY_CHANGED").apply {
                        putExtra("query", spokenText)
                    }
                    requireContext().sendBroadcast(intent)
                }, 300)
            }
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "FORCE_REFRESH_ALL", "FORCE_REFRESH_CURRENT", "QUEUE_CHANGED" -> {
                    Log.d("HomePageFragment", "Refresh broadcast received: ${intent.action}")
                    handler.post {
                        refreshData()
                    }
                }
            }
        }
    }

    // Adapter for the ViewPager2 - UPDATED FOR 5 TABS
    private inner class CategoryPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = 5 // Songs, Artists, Albums, Genres, Videos

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> childFragmentCache.getOrPut("songs") { SongsFragment() }
                1 -> childFragmentCache.getOrPut("artists") { ArtistsFragment() }
                2 -> childFragmentCache.getOrPut("albums") { AlbumsFragment() }
                3 -> childFragmentCache.getOrPut("genres") { GenresFragment() }
                4 -> childFragmentCache.getOrPut("videos") { VideosFragment() } // New Fragment
                else -> throw IllegalStateException("Invalid pager position $position")
            }
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handler = Handler(Looper.getMainLooper())

        // Restore saved state if available
        savedInstanceState?.let {
            currentCategory = it.getString("currentCategory", "songs")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home_page, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeViews(view)

        // Setup ViewPager
        viewPager = view.findViewById(R.id.home_view_pager)
        viewPager.adapter = CategoryPagerAdapter(this)
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                val newCategory = when (position) {
                    0 -> "songs"
                    1 -> "artists"
                    2 -> "albums"
                    3 -> "genres"
                    4 -> "videos"
                    else -> "songs"
                }
                currentCategory = newCategory
                updateTabUI(position)

                // Only apply sort preference for audio categories
                if (position < 4) {
                    handler.post {
                        val currentSortType = PreferenceManager.getSortPreferenceWithDefault(requireContext(), newCategory)
                        applySortToCurrentFragment(currentSortType)
                    }
                }
            }
        })

        setupCategoryTabs()

        // Search Bar just opens the Search Fragment
        searchBar.setOnClickListener {
            (requireActivity() as? MainActivity)?.showSearchPage()
        }

        // Also handle focus incase
        searchBar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                searchBar.clearFocus()
                (requireActivity() as? MainActivity)?.showSearchPage()
            }
        }

        // Load data only once or when needed
        if (!isDataLoaded) {
            loadCardAlbumArt()
            loadQuickActionCardData()
            isDataLoaded = true
        }

        // Set initial tab based on saved state or default
        val initialPosition = when (currentCategory) {
            "artists" -> 1
            "albums" -> 2
            "genres" -> 3
            "videos" -> 4
            else -> 0
        }
        viewPager.setCurrentItem(initialPosition, false)
        updateTabUI(initialPosition)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("currentCategory", currentCategory)
        saveCurrentScrollState()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction("FORCE_REFRESH_ALL")
            addAction("FORCE_REFRESH_CURRENT")
            addAction("QUEUE_CHANGED")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(refreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            requireContext().registerReceiver(refreshReceiver, filter)
        }
        handler.postDelayed({
            refreshDataPreserveState()
        }, 300)
    }

    override fun onPause() {
        super.onPause()
        try {
            requireContext().unregisterReceiver(refreshReceiver)
        } catch (e: Exception) {
            // Ignore if not registered
        }
        saveCurrentScrollState()
    }

    private fun initializeViews(view: View) {
        searchBar = view.findViewById(R.id.search_bar)
        drawerLayout = requireActivity().findViewById(R.id.drawer_layout)

        imgFavoritesOverlay = view.findViewById(R.id.img_favorites_overlay)
        imgPlaylistsOverlay = view.findViewById(R.id.img_playlists_overlay)
        imgRecentOverlay = view.findViewById(R.id.img_recent_overlay)

        view.findViewById<CardView>(R.id.shuffle_all_card).setOnClickListener { shuffleAllSongs() }
        view.findViewById<ImageView>(R.id.btn_shuffle).setOnClickListener {
            (requireActivity() as? MainActivity)?.getMusicService()?.toggleShuffle()
        }
        view.findViewById<ImageButton>(R.id.btn_sort).setOnClickListener { showSortDialog() }
        view.findViewById<View>(R.id.card_favorites).setOnClickListener {
            (requireActivity() as? MainActivity)?.showFavoritesPage()
        }
        view.findViewById<View>(R.id.card_playlists).setOnClickListener {
            (requireActivity() as? MainActivity)?.showPlaylistsPage()
        }
        view.findViewById<View>(R.id.card_recent).setOnClickListener {
            (requireActivity() as? MainActivity)?.showRecentPage()
        }
        view.findViewById<ImageButton>(R.id.settings_icon).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }
        // Mic listener
        view.findViewById<ImageButton>(R.id.voice_search_btn).setOnClickListener {
            startVoiceRecognition()
        }
    }

    private fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to search")
        }
        try {
            voiceRecognitionLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Voice recognition not supported", Toast.LENGTH_SHORT).show()
        }
    }

    fun setDrawerOpen(isOpen: Boolean) {
        isDrawerOpen = isOpen
        updateScrollingState()
    }

    private fun updateScrollingState() {
        try {
            // Use the fragment cache to get all managed fragments
            val fragments = childFragmentCache.values
            if (isDrawerOpen) {
                fragments.forEach { disableScrollingInFragment(it) }
                view?.findViewById<View>(R.id.main_content_container)?.isEnabled = false
                view?.isEnabled = false
                view?.isClickable = false
            } else {
                fragments.forEach { enableScrollingInFragment(it) }
                view?.findViewById<View>(R.id.main_content_container)?.isEnabled = true
                view?.isEnabled = true
                view?.isClickable = true
            }
        } catch (e: Exception) {
            Log.e("HomePageFragment", "Error updating scrolling state: ${e.message}")
        }
    }

    private fun disableScrollingInFragment(fragment: Fragment?) {
        when (fragment) {
            is SongsFragment -> {
                fragment.setScrollingEnabled(false)
                saveFragmentScrollState(fragment, "songs")
            }
            is ArtistsFragment -> {
                fragment.setScrollingEnabled(false)
                saveFragmentScrollState(fragment, "artists")
            }
            is AlbumsFragment -> {
                fragment.setScrollingEnabled(false)
                saveFragmentScrollState(fragment, "albums")
            }
            is GenresFragment -> {
                fragment.setScrollingEnabled(false)
                saveFragmentScrollState(fragment, "genres")
            }
            is VideosFragment -> {
                fragment.setScrollingEnabled(false)
                saveFragmentScrollState(fragment, "videos")
            }
            else -> {
                fragment?.view?.let { fragmentView ->
                    findAndDisableScrollViews(fragmentView)
                    disableViewAndChildren(fragmentView)
                }
            }
        }
    }

    private fun enableScrollingInFragment(fragment: Fragment?) {
        when (fragment) {
            is SongsFragment -> {
                fragment.setScrollingEnabled(true)
                restoreFragmentScrollState(fragment, "songs")
            }
            is ArtistsFragment -> {
                fragment.setScrollingEnabled(true)
                restoreFragmentScrollState(fragment, "artists")
            }
            is AlbumsFragment -> {
                fragment.setScrollingEnabled(true)
                restoreFragmentScrollState(fragment, "albums")
            }
            is GenresFragment -> {
                fragment.setScrollingEnabled(true)
                restoreFragmentScrollState(fragment, "genres")
            }
            is VideosFragment -> {
                fragment.setScrollingEnabled(true)
                restoreFragmentScrollState(fragment, "videos")
            }
            else -> {
                fragment?.view?.let { fragmentView ->
                    findAndEnableScrollViews(fragmentView)
                    enableViewAndChildren(fragmentView)
                }
            }
        }
    }

    private fun saveFragmentScrollState(fragment: Fragment, tabName: String) {
        when (fragment) {
            is SongsFragment -> fragment.saveScrollState()
            is ArtistsFragment -> fragment.saveScrollState()
            is AlbumsFragment -> fragment.saveScrollState()
            is GenresFragment -> fragment.saveScrollState()
            is VideosFragment -> fragment.saveScrollState()
        }
    }

    private fun restoreFragmentScrollState(fragment: Fragment, tabName: String) {
        when (fragment) {
            is SongsFragment -> fragment.restoreScrollState()
            is ArtistsFragment -> fragment.restoreScrollState()
            is AlbumsFragment -> fragment.restoreScrollState()
            is GenresFragment -> fragment.restoreScrollState()
            is VideosFragment -> fragment.restoreScrollState()
        }
    }

    private fun findAndDisableScrollViews(view: View) {
        when (view) {
            is RecyclerView -> {
                view.isNestedScrollingEnabled = false
                view.isEnabled = false
                view.setOnTouchListener { _, _ -> true } // Block all touch events
            }
            is androidx.core.widget.NestedScrollView -> {
                view.isNestedScrollingEnabled = false
                view.isEnabled = false
                view.setOnTouchListener { _, _ -> true } // Block all touch events
            }
            is ViewGroup -> {
                for (i in 0 until view.childCount) {
                    findAndDisableScrollViews(view.getChildAt(i))
                }
            }
        }
    }

    private fun findAndEnableScrollViews(view: View) {
        when (view) {
            is RecyclerView -> {
                view.isNestedScrollingEnabled = true
                view.isEnabled = true
                view.setOnTouchListener(null) // Remove touch blocker
            }
            is androidx.core.widget.NestedScrollView -> {
                view.isNestedScrollingEnabled = true
                view.isEnabled = true
                view.setOnTouchListener(null) // Remove touch blocker
            }
            is ViewGroup -> {
                for (i in 0 until view.childCount) {
                    findAndEnableScrollViews(view.getChildAt(i))
                }
            }
        }
    }

    private fun disableViewAndChildren(view: View) {
        view.isEnabled = false
        view.isClickable = false
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                disableViewAndChildren(view.getChildAt(i))
            }
        }
    }

    private fun enableViewAndChildren(view: View) {
        view.isEnabled = true
        view.isClickable = true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                enableViewAndChildren(view.getChildAt(i))
            }
        }
    }

    private fun setupCategoryTabs() {
        val tabs = mapOf(
            R.id.tab_songs to 0,
            R.id.tab_artists to 1,
            R.id.tab_albums to 2,
            R.id.tab_genres to 3,
            R.id.tab_videos to 4
        )
        tabs.forEach { (tabId, position) ->
            view?.findViewById<TextView>(tabId)?.setOnClickListener {
                viewPager.setCurrentItem(position, true)
            }
        }
    }

    private fun updateTabUI(position: Int) {
        val selectedColor = ContextCompat.getColor(requireContext(), R.color.purple_500)
        val unselectedColor = ContextCompat.getColor(requireContext(), android.R.color.darker_gray)
        val tabs = listOf(R.id.tab_songs, R.id.tab_artists, R.id.tab_albums, R.id.tab_genres, R.id.tab_videos)

        tabs.forEachIndexed { index, tabId ->
            val tab = view?.findViewById<TextView>(tabId)
            if (index == position) {
                tab?.setTextColor(selectedColor)
                tab?.setTypeface(null, android.graphics.Typeface.BOLD)
                tab?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            } else {
                tab?.setTextColor(unselectedColor)
                tab?.setTypeface(null, android.graphics.Typeface.NORMAL)
                tab?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            }
        }
    }

    private fun applySortToCurrentFragment(sortType: MainActivity.SortType) {
        val intent = when (currentCategory) {
            "songs" -> Intent("SORT_SONGS")
            "artists" -> Intent("SORT_ARTISTS")
            "albums" -> Intent("SORT_ALBUMS")
            "genres" -> Intent("SORT_GENRES")
            else -> return
        }.apply {
            putExtra("sort_type", sortType.ordinal)
        }
        requireContext().sendBroadcast(intent)
    }

    private fun shuffleAllSongs() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val allSongs = SongRepository.getAllSongs(requireContext())
                withContext(Dispatchers.Main) {
                    if (allSongs.isNotEmpty()) {
                        (requireActivity() as? MainActivity)?.getMusicService()?.let { service ->
                            val shuffledSongs = allSongs.shuffled()
                            service.startPlayback(shuffledSongs as ArrayList<Song>, 0)
                            service.toggleShuffle()
                            Toast.makeText(requireContext(), "Shuffling ${allSongs.size} songs", Toast.LENGTH_SHORT).show()
                        } ?: Toast.makeText(requireContext(), "Music service not available", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), "No songs found", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Error shuffling songs: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun loadCardAlbumArt() {
        CoroutineScope(Dispatchers.IO).launch {
            val context = requireContext()
            val favoritesId = PreferenceManager.getFavorites(context).toList().lastOrNull()
            loadLastAlbumArt(favoritesId, imgFavoritesOverlay)
            val playlists: List<Playlist> = PreferenceManager.getPlaylists(context)
            val playlistId = playlists.firstOrNull()?.songIds?.firstOrNull()
            loadLastAlbumArt(playlistId, imgPlaylistsOverlay)
            val recentId = PreferenceManager.getRecentSongs(context).firstOrNull()
            loadLastAlbumArt(recentId, imgRecentOverlay)
        }
    }

    private suspend fun loadLastAlbumArt(songId: Long?, imageView: ImageView) {
        val context = requireContext()
        val albumArtUri: android.net.Uri? = if (songId != null) {
            val song = SongRepository.getAllSongs(context).firstOrNull { it.id == songId }
            song?.let { SongUtils.getAlbumArtUri(it.albumId) }
        } else {
            null
        }
        withContext(Dispatchers.Main) {
            imageView.clearColorFilter()
            if (albumArtUri != null) {
                Glide.with(context).load(albumArtUri).centerCrop().placeholder(R.drawable.default_album_art).into(imageView)
            } else {
                imageView.setImageResource(R.drawable.default_album_art)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    fun loadQuickActionCardData() {
        CoroutineScope(Dispatchers.IO).launch {
            val context = requireContext()
            val favoritesCount = PreferenceManager.getFavorites(context).size
            withContext(Dispatchers.Main) {
                view?.findViewById<TextView>(R.id.card_fav_count)?.text = "$favoritesCount songs"
            }
            val playlistsCount = PreferenceManager.getPlaylists(context).size
            withContext(Dispatchers.Main) {
                view?.findViewById<TextView>(R.id.card_playlist_count)?.text = "$playlistsCount playlists"
            }
            val recentCount = PreferenceManager.getRecentSongs(context).size
            withContext(Dispatchers.Main) {
                view?.findViewById<TextView>(R.id.card_recent_count)?.text = "$recentCount songs"
            }
        }
    }

    private fun showSortDialog() {
        // Video sorting not implemented yet
        if (currentCategory == "videos") {
            Toast.makeText(requireContext(), "Sorting not available for videos yet", Toast.LENGTH_SHORT).show()
            return
        }

        val sortOptions: List<Pair<String, MainActivity.SortType>> = when (currentCategory) {
            "songs" -> listOf(
                "Name (A-Z)" to MainActivity.SortType.NAME_ASC,
                "Name (Z-A)" to MainActivity.SortType.NAME_DESC,
                "Date Added (Newest)" to MainActivity.SortType.DATE_ADDED_DESC,
                "Date Added (Oldest)" to MainActivity.SortType.DATE_ADDED_ASC,
                "Duration" to MainActivity.SortType.DURATION
            )
            "artists" -> listOf(
                "Artist Name (A-Z)" to MainActivity.SortType.NAME_ASC,
                "Artist Name (Z-A)" to MainActivity.SortType.NAME_DESC
            )
            "albums" -> listOf(
                "Album Name (A-Z)" to MainActivity.SortType.NAME_ASC,
                "Album Name (Z-A)" to MainActivity.SortType.NAME_DESC
            )
            "genres" -> listOf(
                "Genre Name (A-Z)" to MainActivity.SortType.NAME_ASC,
                "Genre Name (Z-A)" to MainActivity.SortType.NAME_DESC
            )
            else -> return
        }
        val items = sortOptions.map { it.first }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Sort by")
            .setItems(items) { _, which ->
                sortCategory(sortOptions[which].second)
            }
            .show()
    }

    private fun sortCategory(sortType: MainActivity.SortType) {
        Log.d("HomePageFragment", "sortCategory - Setting sort to: $sortType for category: $currentCategory")
        PreferenceManager.saveSortPreference(requireContext(), currentCategory, sortType)
        applySortToCurrentFragment(sortType)
        handler.postDelayed({ refreshCurrentFragment() }, 100)
    }

    fun refreshData() {
        Log.d("HomePageFragment", "refreshData called")
        loadCardAlbumArt()
        loadQuickActionCardData()
        refreshCurrentFragment()
    }

    private fun refreshDataPreserveState() {
        Log.d("HomePageFragment", "refreshDataPreserveState called")
        loadCardAlbumArt()
        loadQuickActionCardData()
        refreshCurrentFragmentPreserveState()
    }

    private fun getCurrentActiveFragment(): Fragment? {
        if (!this::viewPager.isInitialized) return null
        // ViewPager2 fragments are tagged by the FragmentManager as "f" + position
        return childFragmentManager.findFragmentByTag("f${viewPager.currentItem}")
    }

    private fun refreshCurrentFragment() {
        val fragment = getCurrentActiveFragment()
        try {
            when (fragment) {
                is SongsFragment -> if (fragment.isAdded) fragment.refreshData()
                is ArtistsFragment -> if (fragment.isAdded) fragment.refreshData()
                is AlbumsFragment -> if (fragment.isAdded) fragment.refreshData()
                is GenresFragment -> if (fragment.isAdded) fragment.refreshData()
                is VideosFragment -> if (fragment.isAdded) fragment.refreshData()
            }
        } catch (e: Exception) {
            Log.e("HomePageFragment", "Error refreshing fragment: ${e.message}")
        }
    }

    private fun refreshCurrentFragmentPreserveState() {
        val fragment = getCurrentActiveFragment()
        try {
            when (fragment) {
                is SongsFragment -> if (fragment.isAdded) fragment.refreshDataPreserveState()
                is ArtistsFragment -> if (fragment.isAdded) fragment.refreshDataPreserveState()
                is AlbumsFragment -> if (fragment.isAdded) fragment.refreshDataPreserveState()
                is GenresFragment -> if (fragment.isAdded) fragment.refreshDataPreserveState()
                is VideosFragment -> if (fragment.isAdded) fragment.refreshDataPreserveState()
            }
        } catch (e: Exception) {
            Log.e("HomePageFragment", "Error refreshing fragment with state preservation: ${e.message}")
        }
    }

    private fun saveCurrentScrollState() {
        val fragment = getCurrentActiveFragment()
        when (fragment) {
            is SongsFragment -> fragment.saveScrollState()
            is ArtistsFragment -> fragment.saveScrollState()
            is AlbumsFragment -> fragment.saveScrollState()
            is GenresFragment -> fragment.saveScrollState()
            is VideosFragment -> fragment.saveScrollState()
        }
    }

    companion object {
        fun newInstance(): HomePageFragment = HomePageFragment()
    }

    override fun onDestroy() {
        super.onDestroy()
        childFragmentCache.clear()
    }
}