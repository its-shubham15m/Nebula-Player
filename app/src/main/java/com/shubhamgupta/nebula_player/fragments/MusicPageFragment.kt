package com.shubhamgupta.nebula_player.fragments

import android.annotation.SuppressLint
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
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.shubhamgupta.nebula_player.MainActivity
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.models.Playlist
import com.shubhamgupta.nebula_player.models.Song
import com.shubhamgupta.nebula_player.repository.SongRepository
import com.shubhamgupta.nebula_player.utils.PreferenceManager
import com.shubhamgupta.nebula_player.utils.SongUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MusicPageFragment : Fragment() {
    private var currentCategory = "songs"
    private lateinit var handler: Handler
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var viewPager: ViewPager2

    // Cache for child fragments
    private val childFragmentCache = mutableMapOf<String, Fragment>()
    private var isDataLoaded = false

    private lateinit var imgFavoritesOverlay: ImageView
    private lateinit var imgPlaylistsOverlay: ImageView
    private lateinit var imgRecentOverlay: ImageView

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "FORCE_REFRESH_ALL", "FORCE_REFRESH_CURRENT", "QUEUE_CHANGED" -> {
                    Log.d("MusicPageFragment", "Refresh broadcast received: ${intent.action}")
                    handler.post { refreshData() }
                }
            }
        }
    }

    private inner class CategoryPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = 4 // Removed Videos

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> childFragmentCache.getOrPut("songs") { SongsFragment() }
                1 -> childFragmentCache.getOrPut("artists") { ArtistsFragment() }
                2 -> childFragmentCache.getOrPut("albums") { AlbumsFragment() }
                3 -> childFragmentCache.getOrPut("genres") { GenresFragment() }
                else -> throw IllegalStateException("Invalid pager position $position")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handler = Handler(Looper.getMainLooper())
        savedInstanceState?.let {
            currentCategory = it.getString("currentCategory", "songs")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflating the updated layout for Music Page
        return inflater.inflate(R.layout.fragment_music_page, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeViews(view)

        viewPager = view.findViewById(R.id.music_view_pager)
        viewPager.adapter = CategoryPagerAdapter(this)
        viewPager.offscreenPageLimit = 1

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                val newCategory = when (position) {
                    0 -> "songs"
                    1 -> "artists"
                    2 -> "albums"
                    3 -> "genres"
                    else -> "songs"
                }
                currentCategory = newCategory
                updateTabUI(position)

                handler.post {
                    val currentSortType = PreferenceManager.getSortPreferenceWithDefault(requireContext(), newCategory)
                    applySortToCurrentFragment(currentSortType)
                }
            }
        })

        setupCategoryTabs()

        if (!isDataLoaded) {
            loadCardAlbumArt()
            loadQuickActionCardData()
            isDataLoaded = true
        }

        val initialPosition = when (currentCategory) {
            "artists" -> 1
            "albums" -> 2
            "genres" -> 3
            else -> 0
        }
        viewPager.setCurrentItem(initialPosition, false)
        updateTabUI(initialPosition)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("currentCategory", currentCategory)
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
        handler.postDelayed({ refreshDataPreserveState() }, 300)
    }

    override fun onPause() {
        super.onPause()
        try { requireContext().unregisterReceiver(refreshReceiver) } catch (e: Exception) {}
    }

    private fun initializeViews(view: View) {
        drawerLayout = requireActivity().findViewById(R.id.drawer_layout)
        imgFavoritesOverlay = view.findViewById(R.id.img_favorites_overlay)
        imgPlaylistsOverlay = view.findViewById(R.id.img_playlists_overlay)
        imgRecentOverlay = view.findViewById(R.id.img_recent_overlay)

        view.findViewById<CardView>(R.id.shuffle_all_card).setOnClickListener { shuffleAllSongs() }
        view.findViewById<ImageView>(R.id.btn_shuffle).setOnClickListener {
            (requireActivity() as? MainActivity)?.getMusicService()?.toggleShuffle()
        }
        view.findViewById<ImageButton>(R.id.btn_sort).setOnClickListener { showSortDialog() }
        view.findViewById<View>(R.id.card_favorites).setOnClickListener { (requireActivity() as? MainActivity)?.showFavoritesPage() }
        view.findViewById<View>(R.id.card_playlists).setOnClickListener { (requireActivity() as? MainActivity)?.showPlaylistsPage() }
        view.findViewById<View>(R.id.card_recent).setOnClickListener { (requireActivity() as? MainActivity)?.showRecentPage() }

        // Open sidebar from the new header
        view.findViewById<ImageButton>(R.id.sidebar_toggle).setOnClickListener { drawerLayout.openDrawer(GravityCompat.START) }
    }

    private fun setupCategoryTabs() {
        val tabs = mapOf(
            R.id.tab_songs to 0, R.id.tab_artists to 1, R.id.tab_albums to 2, R.id.tab_genres to 3
        )
        tabs.forEach { (tabId, position) ->
            view?.findViewById<TextView>(tabId)?.setOnClickListener { viewPager.setCurrentItem(position, true) }
        }
    }

    private fun updateTabUI(position: Int) {
        val selectedColor = ContextCompat.getColor(requireContext(), R.color.purple_500)
        val unselectedColor = ContextCompat.getColor(requireContext(), android.R.color.darker_gray)
        val tabs = listOf(R.id.tab_songs, R.id.tab_artists, R.id.tab_albums, R.id.tab_genres)

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
                withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show() }
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
        } else null
        withContext(Dispatchers.Main) {
            imageView.clearColorFilter()
            if (albumArtUri != null) Glide.with(context).load(albumArtUri).centerCrop().placeholder(R.drawable.default_album_art).into(imageView)
            else imageView.setImageResource(R.drawable.default_album_art)
        }
    }

    @SuppressLint("SetTextI18n")
    fun loadQuickActionCardData() {
        CoroutineScope(Dispatchers.IO).launch {
            val context = requireContext()
            val favoritesCount = PreferenceManager.getFavorites(context).size
            val playlistsCount = PreferenceManager.getPlaylists(context).size
            val recentCount = PreferenceManager.getRecentSongs(context).size
            withContext(Dispatchers.Main) {
                view?.findViewById<TextView>(R.id.card_fav_count)?.text = "$favoritesCount songs"
                view?.findViewById<TextView>(R.id.card_playlist_count)?.text = "$playlistsCount playlists"
                view?.findViewById<TextView>(R.id.card_recent_count)?.text = "$recentCount songs"
            }
        }
    }

    private fun showSortDialog() {
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
        PreferenceManager.saveSortPreference(requireContext(), currentCategory, sortType)
        applySortToCurrentFragment(sortType)
        handler.postDelayed({ refreshCurrentFragment() }, 100)
    }

    fun refreshData() {
        loadCardAlbumArt()
        loadQuickActionCardData()
        refreshCurrentFragment()
    }

    private fun refreshDataPreserveState() {
        loadCardAlbumArt()
        loadQuickActionCardData()
        refreshCurrentFragmentPreserveState()
    }

    private fun getCurrentActiveFragment(): Fragment? {
        if (!this::viewPager.isInitialized) return null
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
            }
        } catch (e: Exception) { Log.e("MusicPageFragment", "Error refreshing fragment: ${e.message}") }
    }

    private fun refreshCurrentFragmentPreserveState() {
        val fragment = getCurrentActiveFragment()
        try {
            when (fragment) {
                is SongsFragment -> if (fragment.isAdded) fragment.refreshDataPreserveState()
                is ArtistsFragment -> if (fragment.isAdded) fragment.refreshDataPreserveState()
                is AlbumsFragment -> if (fragment.isAdded) fragment.refreshDataPreserveState()
                is GenresFragment -> if (fragment.isAdded) fragment.refreshDataPreserveState()
            }
        } catch (e: Exception) { Log.e("MusicPageFragment", "Error refreshing fragment with state preservation: ${e.message}") }
    }

    companion object {
        fun newInstance(): MusicPageFragment = MusicPageFragment()
    }

    override fun onDestroy() {
        super.onDestroy()
        childFragmentCache.clear()
    }
}