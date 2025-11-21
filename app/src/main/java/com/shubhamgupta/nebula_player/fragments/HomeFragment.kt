package com.shubhamgupta.nebula_player.fragments

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.shubhamgupta.nebula_player.MainActivity
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.adapters.SongAdapter
import com.shubhamgupta.nebula_player.models.Song
import com.shubhamgupta.nebula_player.repository.SongRepository
import com.shubhamgupta.nebula_player.utils.PreferenceManager
import com.shubhamgupta.nebula_player.utils.SongUtils

class HomeFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private val songList = mutableListOf<Song>()
    private var filteredSongList = mutableListOf<Song>()
    private var currentSortType = MainActivity.SortType.NAME_ASC
    private var currentQuery = ""
    private var viewMode: String = "songs"

    // Card UI elements
    private var cardFavImage: ImageView? = null
    private var cardFavTitle: TextView? = null
    private var cardFavCount: TextView? = null
    private var cardPlaylistImage: ImageView? = null
    private var cardPlaylistTitle: TextView? = null
    private var cardPlaylistCount: TextView? = null
    private var cardRecentImage: ImageView? = null
    private var cardRecentTitle: TextView? = null
    private var cardRecentCount: TextView? = null

    // Delete request launcher
    private lateinit var deleteResultLauncher: ActivityResultLauncher<IntentSenderRequest>

    // --- Broadcast Receivers (Unchanged) ---
    private val searchReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "SEARCH_QUERY_CHANGED") {
                currentQuery = intent.getStringExtra("query") ?: ""
                filterSongs()
            }
        }
    }
    private val sortReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "SORT_SONGS") {
                val sortTypeOrdinal = intent.getIntExtra("sort_type", 0)
                currentSortType = MainActivity.SortType.entries.toTypedArray()[sortTypeOrdinal]
                sortSongs()
            }
        }
    }
    private val songChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "SONG_CHANGED" || intent?.action == "PLAYLIST_CHANGED" || intent?.action == "SONG_STATE_CHANGED") {
                loadQuickActionCardData()
            }
        }
    }
    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "MAIN_DATA_REFRESH") {
                loadSongs()
                loadQuickActionCardData()
            }
        }
    }
    // --- Permission Launcher (Unchanged) ---
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) loadSongs() else Toast.makeText(
                context, "Permission denied", Toast.LENGTH_SHORT
            ).show()
        }

    companion object { // Unchanged
        fun newInstance(mode: String): HomeFragment {
            val fragment = HomeFragment()
            val args = Bundle()
            args.putString("VIEW_MODE", mode)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) { // Unchanged
        super.onCreate(savedInstanceState)
        viewMode = arguments?.getString("VIEW_MODE") ?: "songs"

        // Initialize delete launcher
        deleteResultLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Toast.makeText(requireContext(), "Song deleted successfully", Toast.LENGTH_SHORT).show()
                loadSongs() // Reload data after deletion
                loadQuickActionCardData() // Update card counts
            } else {
                Toast.makeText(requireContext(), "Song could not be deleted", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView( // Unchanged
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_home, container, false)
        recyclerView = view.findViewById(R.id.recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(context)

        // Bind Card UI elements
        val mainActivity = requireActivity() as MainActivity
        mainActivity.findViewById<View>(R.id.card_favorites)?.let {
            cardFavImage = it.findViewById(R.id.img_favorites_overlay)
            cardFavTitle = it.findViewById(R.id.card_fav_title)
            cardFavCount = it.findViewById(R.id.card_fav_count)
        }
        mainActivity.findViewById<View>(R.id.card_playlists)?.let {
            cardPlaylistImage = it.findViewById(R.id.img_playlists_overlay)
            cardPlaylistTitle = it.findViewById(R.id.card_playlist_title)
            cardPlaylistCount = it.findViewById(R.id.card_playlist_count)
        }
        mainActivity.findViewById<View>(R.id.card_recent)?.let {
            cardRecentImage = it.findViewById(R.id.img_recent_overlay)
            cardRecentTitle = it.findViewById(R.id.card_recent_title)
            cardRecentCount = it.findViewById(R.id.card_recent_count)
        }

        checkPermissions()
        return view
    }

    override fun onResume() { // Unchanged (except receiver flags)
        super.onResume()
        val songChangeFilter = IntentFilter("SONG_CHANGED")
        songChangeFilter.addAction("SONG_STATE_CHANGED")
        val searchFilter = IntentFilter("SEARCH_QUERY_CHANGED")
        val sortFilter = IntentFilter("SORT_SONGS")
        val refreshFilter = IntentFilter("MAIN_DATA_REFRESH")

        val receiverFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_EXPORTED else 0

        ContextCompat.registerReceiver(requireActivity(), songChangeReceiver, songChangeFilter, receiverFlags)
        ContextCompat.registerReceiver(requireActivity(), searchReceiver, searchFilter, receiverFlags)
        ContextCompat.registerReceiver(requireActivity(), sortReceiver, sortFilter, receiverFlags)
        ContextCompat.registerReceiver(requireActivity(), refreshReceiver, refreshFilter, receiverFlags)


        if (songList.isEmpty()) {
            checkPermissions()
        } else {
            loadSongs()
        }
        loadQuickActionCardData()
    }

    override fun onPause() { // Unchanged
        super.onPause()
        requireActivity().unregisterReceiver(songChangeReceiver)
        requireActivity().unregisterReceiver(searchReceiver)
        requireActivity().unregisterReceiver(sortReceiver)
        requireActivity().unregisterReceiver(refreshReceiver)
    }

    private fun checkPermissions() { // Unchanged
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(requireContext(), permission) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(permission)
        } else {
            loadSongs()
        }
    }

    private fun loadSongs() { // Unchanged
        songList.clear()
        songList.addAll(SongRepository.getAllSongs(requireContext()))
        sortSongs() // This now calls filterSongs internally
        loadQuickActionCardData()
    }

    fun loadQuickActionCardData() { // Unchanged
        val context = context ?: return
        val allSongs = SongRepository.getAllSongs(context)
        val mainActivity = requireActivity() as MainActivity
        val musicService = mainActivity.getMusicService()

        // Recent Card
        val recentIds = PreferenceManager.getRecentSongs(context)
        val recentCount = recentIds.size
        val currentlyPlayingSong = musicService?.getCurrentSong()
        val songForRecentCard = currentlyPlayingSong ?: allSongs.firstOrNull { it.id == recentIds.firstOrNull() }
        cardRecentTitle?.text = "Recent"
        cardRecentCount?.text = context.resources.getQuantityString(R.plurals.song_count_plurals, recentCount, recentCount)
        cardRecentImage?.let {
            val artUri = songForRecentCard?.let { s -> SongUtils.getAlbumArtUri(s.albumId) } ?: R.drawable.default_album_art
            Glide.with(context).load(artUri).placeholder(R.drawable.default_album_art).error(R.drawable.default_album_art).centerCrop().into(it)
        }

        // Favorites Card
        val favoriteIds = PreferenceManager.getFavorites(context)
        val favoriteCount = favoriteIds.size
        val lastFavoriteSongId = favoriteIds.lastOrNull()
        val latestFavoriteSong = allSongs.firstOrNull { it.id == lastFavoriteSongId }
        cardFavTitle?.text = "Favorites"
        cardFavCount?.text = context.resources.getQuantityString(R.plurals.song_count_plurals, favoriteCount, favoriteCount)
        cardFavImage?.let {
            val artUri = latestFavoriteSong?.let { s -> SongUtils.getAlbumArtUri(s.albumId) } ?: R.drawable.default_album_art
            Glide.with(context).load(artUri).placeholder(R.drawable.ic_favorite_filled).error(R.drawable.default_album_art).centerCrop().into(it)
        }

        // Playlists Card
        val playlists = PreferenceManager.getPlaylists(context)
        val playlistCount = playlists.size
        val firstPlaylist = playlists.firstOrNull()
        cardPlaylistTitle?.text = "Playlists"
        cardPlaylistCount?.text = context.resources.getQuantityString(R.plurals.playlist_count_plurals, playlistCount, playlistCount)
        val firstSongInPlaylist = firstPlaylist?.songIds?.firstOrNull()?.let { id -> allSongs.firstOrNull { it.id == id } }
        cardPlaylistImage?.let {
            val artUri = firstSongInPlaylist?.let { s -> SongUtils.getAlbumArtUri(s.albumId) } ?: R.drawable.default_album_art
            Glide.with(context).load(artUri).placeholder(R.drawable.ic_playlist).error(R.drawable.default_album_art).centerCrop().into(it)
        }
    }

    private fun sortSongs() { // Unchanged
        if (viewMode == "songs") {
            when (currentSortType) {
                MainActivity.SortType.NAME_ASC -> songList.sortBy { it.title.lowercase() }
                MainActivity.SortType.NAME_DESC -> songList.sortByDescending { it.title.lowercase() }
                MainActivity.SortType.DATE_ADDED_ASC -> songList.sortBy { it.dateAdded }
                MainActivity.SortType.DATE_ADDED_DESC -> songList.sortByDescending { it.dateAdded }
                MainActivity.SortType.DURATION -> songList.sortByDescending { it.duration }
            }
        }
        filterSongs()
    }

    private fun filterSongs() { // Unchanged
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

    // UPDATED: Added onDeleteRequest parameter
    private fun updateAdapter() {
        val adapter = SongAdapter(
            context = requireContext(),
            songs = filteredSongList,
            onItemClick = { pos -> openNowPlaying(pos) },
            onDataChanged = {
                loadSongs() // Reload song list
                loadQuickActionCardData() // Update card counts
            },
            onDeleteRequest = { song -> requestDeleteSong(song) } // Pass the delete request handler
        )
        recyclerView.adapter = adapter
    }

    // NEW: Handles the delete request from the adapter
    private fun requestDeleteSong(song: Song) {
        try {
            val intentSender = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                MediaStore.createDeleteRequest(requireContext().contentResolver, listOf(song.uri)).intentSender
            } else {
                null // Fallback for older APIs handled below
            }

            if (intentSender != null) {
                val request = IntentSenderRequest.Builder(intentSender).build()
                deleteResultLauncher.launch(request)
            } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                // Special handling for Android Q using RecoverableSecurityException
                try {
                    // Attempt the delete operation that might throw RecoverableSecurityException
                    requireContext().contentResolver.delete(song.uri, null, null)
                    // If delete succeeds without exception (rare for non-owned files)
                    Toast.makeText(requireContext(), "Song deleted successfully", Toast.LENGTH_SHORT).show()
                    loadSongs()
                    loadQuickActionCardData()
                } catch (e: SecurityException) {
                    if (e is RecoverableSecurityException) {
                        val request = IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build()
                        deleteResultLauncher.launch(request)
                    } else {
                        throw e // Re-throw other security exceptions
                    }
                }
            }
            else {
                // Fallback for older versions (pre-Q) - Direct deletion (might fail)
                val deletedRows = requireContext().contentResolver.delete(song.uri, null, null)
                if (deletedRows > 0) {
                    Toast.makeText(requireContext(), "Song deleted successfully", Toast.LENGTH_SHORT).show()
                    loadSongs()
                    loadQuickActionCardData()
                } else {
                    Toast.makeText(requireContext(), "Could not delete song (pre-Q).", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e("HomeFragment", "Error requesting delete for ${song.uri}", e)
            Toast.makeText(requireContext(), "Error requesting deletion.", Toast.LENGTH_SHORT).show()
        }
    }


    private fun openNowPlaying(position: Int) { // Unchanged
        if (position < 0 || position >= filteredSongList.size) return
        val songToPlay = filteredSongList[position]
        PreferenceManager.addRecentSong(requireContext(), songToPlay.id)
        val service = (requireActivity() as MainActivity).getMusicService()
        service?.startPlayback(ArrayList(filteredSongList), position)
        (requireActivity() as MainActivity).navigateToNowPlaying()
    }
}