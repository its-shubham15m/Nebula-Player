package com.shubhamgupta.nebula_player.fragments

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.imageview.ShapeableImageView
import com.shubhamgupta.nebula_player.MainActivity // Import MainActivity
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.adapters.SongAdapter
import com.shubhamgupta.nebula_player.models.Playlist
import com.shubhamgupta.nebula_player.models.Song
import com.shubhamgupta.nebula_player.repository.SongRepository
import com.shubhamgupta.nebula_player.service.MusicService
import com.shubhamgupta.nebula_player.utils.PreferenceManager
import com.shubhamgupta.nebula_player.utils.SongUtils
import java.util.concurrent.TimeUnit

class PlaylistSongsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var playlistArt: ShapeableImageView
    private lateinit var playlistName: TextView
    private lateinit var songCount: TextView
    private lateinit var playlistDuration: TextView
    private lateinit var emptyView: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var btnOptions: ImageButton
    private lateinit var btnPlay: com.google.android.material.button.MaterialButton
    private lateinit var btnShuffle: com.google.android.material.button.MaterialButton

    private var currentPlaylist: Playlist? = null
    private var playlistSongsList = mutableListOf<Song>() // Use MutableList
    private var musicService: MusicService? = null

    // Delete request launcher
    private lateinit var deleteResultLauncher: ActivityResultLauncher<IntentSenderRequest>

    private val playbackReceiver = object : BroadcastReceiver() { // Unchanged
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "SONG_CHANGED", "PLAYBACK_STATE_CHANGED" -> {
                    currentPlaylist?.let { setupPlaylistSongs(it) }
                }
            }
        }
    }

    // --- Status Bar Color Methods (Unchanged) ---
    private fun updateStatusBarColor() {
        val activity = requireActivity() as MainActivity
        val currentTheme = com.shubhamgupta.nebula_player.utils.ThemeManager.getCurrentTheme(requireContext())
        val colorRes = R.color.colorPrimaryContainer // Use container color

        activity.window.statusBarColor = ContextCompat.getColor(requireContext(), colorRes)
        val isLightStatus = when (currentTheme) {
            com.shubhamgupta.nebula_player.utils.ThemeManager.THEME_LIGHT -> true
            com.shubhamgupta.nebula_player.utils.ThemeManager.THEME_DARK -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_NO
        }
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars = isLightStatus
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
    }
    private fun resetStatusBarColor() {
        val activity = requireActivity() as MainActivity
        val currentTheme = com.shubhamgupta.nebula_player.utils.ThemeManager.getCurrentTheme(requireContext())
        val colorRes = R.color.colorBackground // Reset to background
        activity.window.statusBarColor = ContextCompat.getColor(requireContext(), colorRes)
        val isLightStatus = when (currentTheme) {
            com.shubhamgupta.nebula_player.utils.ThemeManager.THEME_LIGHT -> true
            com.shubhamgupta.nebula_player.utils.ThemeManager.THEME_DARK -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_NO
        }
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars = isLightStatus
        // Optional: Add back translucent flag if needed, but usually background color is enough
    }
    // --- Companion Object (Unchanged) ---
    companion object {
        private const val ARG_PLAYLIST = "playlist"
        fun newInstance(playlist: Playlist): PlaylistSongsFragment {
            val fragment = PlaylistSongsFragment()
            val args = Bundle()
            args.putSerializable(ARG_PLAYLIST, playlist) // Use Serializable for Playlist
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Initialize delete launcher
        deleteResultLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Toast.makeText(requireContext(), "Song deleted successfully", Toast.LENGTH_SHORT).show()
                refreshData() // Reload data after deletion
            } else {
                Toast.makeText(requireContext(), "Song could not be deleted", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView( // Unchanged
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_playlist_songs, container, false)
        initializeViews(view)
        return view
    }

    override fun onResume() { // Unchanged (except receiver flags)
        super.onResume()
        (requireActivity() as MainActivity).setDrawerLocked(true)
        updateStatusBarColor()
        val filter = IntentFilter().apply {
            addAction("SONG_CHANGED")
            addAction("PLAYBACK_STATE_CHANGED")
        }
        val receiverFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_NOT_EXPORTED else 0
        requireActivity().registerReceiver(playbackReceiver, filter, receiverFlags)
        // Reload playlist from args or preferences in case it was modified
        loadCurrentPlaylist()
    }

    override fun onPause() { // Unchanged
        super.onPause()
        (requireActivity() as MainActivity).setDrawerLocked(false)
        resetStatusBarColor()
        try {
            requireActivity().unregisterReceiver(playbackReceiver)
        } catch (e: Exception) { /* Ignore */ }
    }

    private fun initializeViews(view: View) { // Unchanged
        recyclerView = view.findViewById(R.id.songs_recycler_view)
        playlistArt = view.findViewById(R.id.iv_playlist_art)
        playlistName = view.findViewById(R.id.tv_playlist_name)
        songCount = view.findViewById(R.id.tv_song_count)
        playlistDuration = view.findViewById(R.id.tv_playlist_duration)
        emptyView = view.findViewById(R.id.tv_empty_playlist)
        btnBack = view.findViewById(R.id.btn_back)
        btnOptions = view.findViewById(R.id.btn_playlist_options)
        btnPlay = view.findViewById(R.id.btn_play_playlist)
        btnShuffle = view.findViewById(R.id.btn_shuffle_playlist)
        recyclerView.layoutManager = LinearLayoutManager(context)

        // Load initial playlist from arguments
        currentPlaylist = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arguments?.getSerializable(ARG_PLAYLIST, Playlist::class.java)
        } else {
            @Suppress("DEPRECATION") arguments?.getSerializable(ARG_PLAYLIST) as? Playlist
        }

        currentPlaylist?.let { setupPlaylistSongs(it) } ?: run {
            Log.e("PlaylistSongsFragment", "Current playlist is null on init")
            // Handle error, maybe pop backstack
        }


        btnBack.setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }
        btnPlay.setOnClickListener { playPlaylist() }
        btnShuffle.setOnClickListener { shufflePlaylist() }
        btnOptions.setOnClickListener { v -> showPlaylistOptionsMenu(v) }

        try {
            musicService = (requireActivity() as MainActivity).getMusicService()
        } catch (e: Exception) { e.printStackTrace() }
    }

    // NEW: Load/Reload current playlist from preferences
    private fun loadCurrentPlaylist() {
        val playlistId = currentPlaylist?.id
        if (playlistId != null) {
            val updatedPlaylist = PreferenceManager.getPlaylists(requireContext()).find { it.id == playlistId }
            if (updatedPlaylist != null) {
                currentPlaylist = updatedPlaylist // Update the current playlist object
                setupPlaylistSongs(updatedPlaylist)
            } else {
                // Playlist might have been deleted, handle this case (e.g., pop backstack)
                Log.e("PlaylistSongsFragment", "Playlist with ID $playlistId not found.")
                requireActivity().supportFragmentManager.popBackStack()
            }
        } else {
            Log.e("PlaylistSongsFragment", "Current playlist ID is null.")
            requireActivity().supportFragmentManager.popBackStack()
        }
    }


    // UPDATED: Use MutableList and correct adapter call
    private fun setupPlaylistSongs(playlist: Playlist) {
        playlistName.text = playlist.name
        val songsFromRepo = getPlaylistSongs(playlist) // Get fresh song data

        // Update local list
        playlistSongsList.clear()
        playlistSongsList.addAll(songsFromRepo)

        songCount.text = "${playlistSongsList.size} songs"
        val totalDuration = playlistSongsList.sumOf { it.duration }
        val minutes = TimeUnit.MILLISECONDS.toMinutes(totalDuration)
        playlistDuration.text = "$minutes min"
        loadPlaylistArt(playlist) // Pass the playlist object

        if (playlistSongsList.isNotEmpty()) {
            emptyView.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            val adapter = SongAdapter(
                context = requireContext(),
                songs = playlistSongsList, // Pass mutable list
                onItemClick = { pos -> openNowPlaying(pos) },
                onDataChanged = { refreshData() }, // Use refreshData for consistency
                onDeleteRequest = { song -> requestDeleteSong(song) }
            )
            recyclerView.adapter = adapter
            btnPlay.isEnabled = true
            btnShuffle.isEnabled = true
        } else {
            emptyView.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            btnPlay.isEnabled = false
            btnShuffle.isEnabled = false
        }
    }

    // UPDATED: Pass Playlist object to handle potential deletion
    private fun loadPlaylistArt(playlist: Playlist?) {
        val firstSongId = playlist?.songIds?.firstOrNull()
        if (firstSongId == null) {
            Glide.with(this).load(R.drawable.default_album_art).into(playlistArt)
            return
        }

        val allSongs = SongRepository.getAllSongs(requireContext()) // Consider optimizing if slow
        val firstSong = allSongs.firstOrNull { it.id == firstSongId }

        if (firstSong != null) {
            val artLoader = if (firstSong.embeddedArtBytes != null) {
                Glide.with(this).load(firstSong.embeddedArtBytes)
            } else {
                Glide.with(this).load(SongUtils.getAlbumArtUri(firstSong.albumId))
            }
            artLoader.placeholder(R.drawable.default_album_art)
                .error(R.drawable.default_album_art)
                .into(playlistArt)
        } else {
            Glide.with(this).load(R.drawable.default_album_art).into(playlistArt)
        }
    }


    private fun getPlaylistSongs(playlist: Playlist): List<Song> { // Unchanged
        val allSongs = SongRepository.getAllSongs(requireContext())
        // Ensure songs are returned in the order they appear in the playlist's songIds
        val songIdMap = allSongs.associateBy { it.id }
        return playlist.songIds.mapNotNull { songIdMap[it] }
    }

    private fun showPlaylistOptionsMenu(view: View) { // Unchanged
        val popup = PopupMenu(view.context, view)
        popup.menuInflater.inflate(R.menu.playlist_options_menu, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_play_playlist -> { playPlaylist(); true }
                R.id.menu_add_songs -> { showAddSongsToPlaylistDialog(); true }
                R.id.menu_rename_playlist -> { showRenamePlaylistDialog(); true }
                R.id.menu_delete_playlist -> { showDeletePlaylistDialog(); true }
                else -> false
            }
        }
        popup.show()
    }

    // --- Add/Rename/Delete Playlist Dialogs (Mostly Unchanged, use AppCompat AlertDialog) ---
    private fun showAddSongsToPlaylistDialog() {
        currentPlaylist?.let { playlist ->
            val allSongs = SongRepository.getAllSongs(requireContext())
            val currentPlaylistSongIds = playlist.songIds.toSet()

            val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_songs, null)
            val recyclerView = dialogView.findViewById<RecyclerView>(R.id.songs_recycler_view)
            val searchBar = dialogView.findViewById<android.widget.EditText>(R.id.search_bar)
            val tvTitle = dialogView.findViewById<TextView>(R.id.dialog_title)
            val selectedCount = dialogView.findViewById<TextView>(R.id.selected_count)
            val totalSongs = dialogView.findViewById<TextView>(R.id.total_songs)
            val selectAllButton = dialogView.findViewById<android.widget.Button>(R.id.btn_select_all)
            val submitButton = dialogView.findViewById<android.widget.Button>(R.id.btn_submit)
            // *** FIX: Find the cancel button from the XML layout ***
            val cancelButton = dialogView.findViewById<android.widget.Button>(R.id.btn_cancel)

            // The XML now handles theming with attributes, these lines can be simplified or removed
            // dialogView.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.dialog_background))
            // tvTitle.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            // ... etc.

            tvTitle.text = "Add songs to '${playlist.name}'"
            totalSongs.text = "Total songs: ${allSongs.size}"

            lateinit var songAdapter: com.shubhamgupta.nebula_player.adapters.SongSelectionAdapter
            recyclerView.layoutManager = LinearLayoutManager(requireContext())
            songAdapter = com.shubhamgupta.nebula_player.adapters.SongSelectionAdapter(
                songs = allSongs,
                selectedSongIds = currentPlaylistSongIds,
                onSongSelected = { _, _ -> updateSelectedCount(songAdapter, selectedCount) }
            )
            recyclerView.adapter = songAdapter
            updateSelectedCount(songAdapter, selectedCount)

            searchBar.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    songAdapter.filterSongs(s.toString().trim())
                    updateSelectedCount(songAdapter, selectedCount)
                }
            })
            selectAllButton.setOnClickListener {
                songAdapter.selectAll()
                updateSelectedCount(songAdapter, selectedCount)
            }

            // *** FIX: Remove the programmatic .setNegativeButton() ***
            val dialog = AlertDialog.Builder(requireContext(), R.style.AlertDialogTheme)
                .setView(dialogView)
                .create()

            applyDialogThemeFix(dialog)
            selectAllButton.setTextColor(ContextCompat.getColor(requireContext(), R.color.button_positive))
            submitButton.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))

            submitButton.setOnClickListener {
                val selectedSongs = songAdapter.getSelectedSongs()
                if (selectedSongs.isNotEmpty()) {
                    addSongsToPlaylist(selectedSongs)
                    showToast("Added ${selectedSongs.size} songs")
                    dialog.dismiss()
                    refreshData() // Refresh list
                } else {
                    showToast("Please select at least one song")
                }
            }

            // *** FIX: Add the onClick listener for the button from the XML ***
            cancelButton.setOnClickListener {
                dialog.dismiss()
            }
            // Make text bold for consistency
            cancelButton.setTypeface(null, Typeface.BOLD)

            dialog.show()

            // Make the dialog fullscreen
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            dialog.window?.setBackgroundDrawableResource(R.color.dialog_background)

            // *** FIX: Remove the logic for the old programmatic button ***
            // dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(ContextCompat.getColor(requireContext(), R.color.button_negative))
        }
    }
    private fun updateSelectedCount(songAdapter: com.shubhamgupta.nebula_player.adapters.SongSelectionAdapter, textView: TextView) { // Unchanged
        val count = songAdapter.getSelectedSongsCount()
        textView.text = "$count songs selected"
    }
    private fun addSongsToPlaylist(songIds: List<Long>) { // Unchanged
        currentPlaylist?.let { playlist ->
            val updatedSongIds = playlist.songIds.toMutableList()
            val newSongs = songIds.filter { it !in updatedSongIds }
            updatedSongIds.addAll(newSongs)
            playlist.songIds.clear()
            playlist.songIds.addAll(updatedSongIds)

            val allPlaylists = PreferenceManager.getPlaylists(requireContext()).toMutableList()
            val playlistIndex = allPlaylists.indexOfFirst { it.id == playlist.id }
            if (playlistIndex != -1) {
                allPlaylists[playlistIndex] = playlist
                PreferenceManager.savePlaylists(requireContext(), allPlaylists)
            }
        }
    }
    private fun showRenamePlaylistDialog() { // Unchanged (Use AppCompat AlertDialog)
        currentPlaylist?.let { playlist ->
            val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_create_playlist, null)
            val input = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_playlist_name)
            input.setText(playlist.name)
            input.setSelection(playlist.name.length)
            dialogView.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.dialog_background))

            val dialog = AlertDialog.Builder(requireContext(), R.style.AlertDialogTheme) // Use AppCompat
                .setTitle("Rename Playlist")
                .setView(dialogView)
                .setPositiveButton("RENAME", null)
                .setNegativeButton("CANCEL") { d, _ -> d.dismiss() }
                .create()

            applyDialogThemeFix(dialog)
            dialog.setOnShowListener {
                val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                positiveButton.setOnClickListener {
                    val newName = input.text.toString().trim()
                    if (newName.isEmpty()) {
                        input.error = "Name cannot be empty"; return@setOnClickListener
                    }
                    val allPlaylists = PreferenceManager.getPlaylists(requireContext())
                    if (allPlaylists.any { it.id != playlist.id && it.name.equals(newName, ignoreCase = true) }) {
                        input.error = "Name already exists"; return@setOnClickListener
                    }

                    playlist.name = newName
                    val updatedPlaylists = allPlaylists.toMutableList()
                    val index = updatedPlaylists.indexOfFirst { it.id == playlist.id }
                    if (index != -1) {
                        updatedPlaylists[index] = playlist
                        PreferenceManager.savePlaylists(requireContext(), updatedPlaylists)
                        setupPlaylistSongs(playlist) // Refresh UI with new name
                        showToast("Playlist renamed")
                        dialog.dismiss()
                    }
                }
                setDialogButtonColors(dialog)
            }
            dialog.show()
        }
    }
    private fun showDeletePlaylistDialog() { // Unchanged (Use AppCompat AlertDialog)
        currentPlaylist?.let { playlist ->
            val dialog = AlertDialog.Builder(requireContext(), R.style.AlertDialogTheme) // Use AppCompat
                .setTitle("Delete Playlist")
                .setMessage("Delete '${playlist.name}'?")
                .setPositiveButton("DELETE") { d, _ ->
                    val allPlaylists = PreferenceManager.getPlaylists(requireContext()).toMutableList()
                    allPlaylists.removeAll { it.id == playlist.id }
                    PreferenceManager.savePlaylists(requireContext(), allPlaylists)
                    showToast("Playlist deleted")
                    d.dismiss()
                    requireActivity().supportFragmentManager.popBackStack()
                }
                .setNegativeButton("CANCEL") { d, _ -> d.dismiss() }
                .create()

            applyDialogThemeFix(dialog)
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(ContextCompat.getColor(requireContext(), R.color.error_red))
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(ContextCompat.getColor(requireContext(), R.color.button_negative))
            }
            dialog.show()
        }
    }
    // --- Dialog Theme Helpers (Unchanged, use AppCompat AlertDialog) ---
    private fun applyDialogThemeFix(dialog: AlertDialog) { // Use AppCompat
        val titleTextView = dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle) // Use AppCompat ID
        val messageTextView = dialog.findViewById<TextView>(android.R.id.message)
        titleTextView?.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
        messageTextView?.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
        dialog.window?.setBackgroundDrawableResource(R.color.dialog_background)
    }
    private fun setDialogButtonColors(dialog: AlertDialog) { // Use AppCompat
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(ContextCompat.getColor(requireContext(), R.color.button_positive))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(ContextCompat.getColor(requireContext(), R.color.button_negative))
    }
    // --- Playback Controls (Unchanged, use local list) ---
    private fun playPlaylist() {
        if (playlistSongsList.isNotEmpty()) {
            musicService?.startPlayback(ArrayList(playlistSongsList), 0)
            (requireActivity() as MainActivity).navigateToNowPlaying()
        }
    }
    private fun shufflePlaylist() {
        if (playlistSongsList.isNotEmpty()) {
            val shuffledSongs = playlistSongsList.shuffled()
            musicService?.startPlayback(ArrayList(shuffledSongs), 0)
            musicService?.toggleShuffle()
            (requireActivity() as MainActivity).navigateToNowPlaying()
        }
    }

    // NEW: Handles delete request
    private fun requestDeleteSong(song: Song) {
        // Option 1: Request system deletion (Recommended for Android 10+)
        requestSystemDelete(song)

        // Option 2: Remove only from this playlist (Simpler, doesn't delete file)
        // removeFromPlaylist(song)
    }

    // NEW: Method to request deletion using MediaStore API
    private fun requestSystemDelete(song: Song) {
        try {
            val intentSender = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                MediaStore.createDeleteRequest(requireContext().contentResolver, listOf(song.uri)).intentSender
            } else null

            if (intentSender != null) {
                val request = IntentSenderRequest.Builder(intentSender).build()
                deleteResultLauncher.launch(request)
            } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                try {
                    // Attempt delete, catch RecoverableSecurityException for Q
                    requireContext().contentResolver.delete(song.uri, null, null)
                    // If successful without exception (unlikely for non-owned files)
                    Toast.makeText(requireContext(), "Song deleted successfully (Q)", Toast.LENGTH_SHORT).show()
                    removeSongFromPlaylistLocally(song) // Also remove from playlist UI/data
                    refreshData()
                } catch (e: SecurityException) {
                    if (e is RecoverableSecurityException) {
                        val request = IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build()
                        deleteResultLauncher.launch(request) // This will prompt the user
                    } else {
                        Log.e("PlaylistSongsFragment", "Non-recoverable SecurityException on Q", e)
                        Toast.makeText(requireContext(), "Permission denied to delete.", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                // Fallback for pre-Q: Direct deletion attempt (likely to fail for non-owned files)
                val deletedRows = requireContext().contentResolver.delete(song.uri, null, null)
                if (deletedRows > 0) {
                    Toast.makeText(requireContext(), "Song deleted successfully (pre-Q)", Toast.LENGTH_SHORT).show()
                    removeSongFromPlaylistLocally(song) // Also remove from playlist UI/data
                    refreshData()
                } else {
                    Log.w("PlaylistSongsFragment", "Direct delete failed (pre-Q) for ${song.uri}")
                    Toast.makeText(requireContext(), "Could not delete song (pre-Q).", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e("PlaylistSongsFragment", "Error requesting delete for ${song.uri}", e)
            Toast.makeText(requireContext(), "Error requesting deletion.", Toast.LENGTH_SHORT).show()
        }
    }

    // NEW: Helper to remove song from the current playlist's data and UI
    private fun removeSongFromPlaylistLocally(song: Song) {
        currentPlaylist?.let { playlist ->
            val songRemoved = playlist.songIds.remove(song.id)
            if (songRemoved) {
                // Update the list used by the adapter
                playlistSongsList.removeAll { it.id == song.id }
                // Save the updated playlist to preferences
                val allPlaylists = PreferenceManager.getPlaylists(requireContext()).toMutableList()
                val index = allPlaylists.indexOfFirst { it.id == playlist.id }
                if (index != -1) {
                    allPlaylists[index] = playlist
                    PreferenceManager.savePlaylists(requireContext(), allPlaylists)
                }
                // No need to call refreshData() here as the launcher callback will do it
            }
        }
    }


    // UPDATED: Use playlistSongsList
    private fun openNowPlaying(position: Int) {
        if (position < 0 || position >= playlistSongsList.size) return
        val songToPlay = playlistSongsList[position]
        PreferenceManager.addRecentSong(requireContext(), songToPlay.id)
        musicService?.startPlayback(ArrayList(playlistSongsList), position) // Use local list
        (requireActivity() as MainActivity).navigateToNowPlaying()
    }

    private fun showToast(message: String) { // Unchanged
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    fun refreshData() { // UPDATED: Use loadCurrentPlaylist
        loadCurrentPlaylist()
    }
}