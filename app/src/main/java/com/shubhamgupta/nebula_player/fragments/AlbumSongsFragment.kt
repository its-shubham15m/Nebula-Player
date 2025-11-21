package com.shubhamgupta.nebula_player.fragments

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.shubhamgupta.nebula_player.MainActivity // Import MainActivity
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.adapters.SongAdapter
import com.shubhamgupta.nebula_player.models.Album
import com.shubhamgupta.nebula_player.models.Song // Import Song
import com.shubhamgupta.nebula_player.utils.PreferenceManager
import com.shubhamgupta.nebula_player.utils.SongUtils

class AlbumSongsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var albumArt: ImageView
    private lateinit var albumNameView: TextView
    private lateinit var albumArtistView: TextView
    private lateinit var songCountView: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var tvEmpty: TextView
    private var currentAlbum: Album? = null
    private var albumSongsList = mutableListOf<Song>() // Use MutableList

    // Delete request launcher
    private lateinit var deleteResultLauncher: ActivityResultLauncher<IntentSenderRequest>


    private val playbackReceiver = object : BroadcastReceiver() { // Unchanged
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "SONG_CHANGED", "PLAYBACK_STATE_CHANGED" -> {
                    currentAlbum?.let { setupAlbumSongs(it) }
                }
            }
        }
    }

    companion object { // Unchanged
        private const val ARG_ALBUM = "album"
        fun newInstance(album: Album): AlbumSongsFragment {
            val fragment = AlbumSongsFragment()
            val args = Bundle()
            args.putParcelable(ARG_ALBUM, album)
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
        val view = inflater.inflate(R.layout.fragment_album_songs, container, false)
        initializeViews(view)
        return view
    }

    override fun onResume() { // Unchanged (except receiver flags)
        super.onResume()
        (requireActivity() as MainActivity).setDrawerLocked(true)
        val filter = IntentFilter().apply {
            addAction("SONG_CHANGED")
            addAction("PLAYBACK_STATE_CHANGED")
        }
        val receiverFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_NOT_EXPORTED else 0
        requireActivity().registerReceiver(playbackReceiver, filter, receiverFlags)
        currentAlbum?.let { setupAlbumSongs(it) }
    }

    override fun onPause() { // Unchanged
        super.onPause()
        (requireActivity() as MainActivity).setDrawerLocked(false)
        try {
            requireActivity().unregisterReceiver(playbackReceiver)
        } catch (e: Exception) { /* Ignore */ }
    }

    private fun initializeViews(view: View) { // Unchanged
        recyclerView = view.findViewById(R.id.recycler_view_album_songs)
        albumArt = view.findViewById(R.id.album_art)
        albumNameView = view.findViewById(R.id.album_name)
        albumArtistView = view.findViewById(R.id.album_artist)
        songCountView = view.findViewById(R.id.album_song_count)
        btnBack = view.findViewById(R.id.btn_back)
        tvEmpty = view.findViewById(R.id.tv_empty_album_songs)
        recyclerView.layoutManager = LinearLayoutManager(context)

        currentAlbum = arguments?.getParcelable(ARG_ALBUM)
        currentAlbum?.let { setupAlbumSongs(it) }

        btnBack.setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }
        val shuffleCard = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.shuffle_all_card)
        shuffleCard.setOnClickListener {
            shuffleAlbumSongs()
        }
    }

    // UPDATED: Use MutableList and correct adapter call
    private fun setupAlbumSongs(album: Album) {
        albumNameView.text = album.name
        albumArtistView.text = album.artist
        songCountView.text = "${album.songCount} songs"
        val albumArtUri = SongUtils.getAlbumArtUri(album.albumId)
        Glide.with(this).load(albumArtUri).placeholder(R.drawable.default_album_art).error(R.drawable.default_album_art).into(albumArt)

        // Update the local mutable list
        albumSongsList.clear()
        albumSongsList.addAll(album.songs)

        if (albumSongsList.isEmpty()) {
            recyclerView.visibility = View.GONE
            tvEmpty.visibility = View.VISIBLE
            view?.findViewById<com.google.android.material.card.MaterialCardView>(R.id.shuffle_all_card)?.visibility = View.GONE
        } else {
            recyclerView.visibility = View.VISIBLE
            tvEmpty.visibility = View.GONE
            view?.findViewById<com.google.android.material.card.MaterialCardView>(R.id.shuffle_all_card)?.visibility = View.VISIBLE

            val adapter = SongAdapter(
                context = requireContext(),
                songs = albumSongsList, // Pass the mutable list
                onItemClick = { pos -> openNowPlaying(pos) }, // Simplified call
                onDataChanged = { refreshData() },
                onDeleteRequest = { song -> requestDeleteSong(song) }
            )
            recyclerView.adapter = adapter
        }
    }

    // UPDATED: Use albumSongsList
    private fun shuffleAlbumSongs() {
        if (albumSongsList.isNotEmpty()) {
            val shuffledSongs = albumSongsList.shuffled()
            val service = (requireActivity() as MainActivity).getMusicService()
            service?.startPlayback(ArrayList(shuffledSongs), 0)
            service?.toggleShuffle()
            (requireActivity() as MainActivity).navigateToNowPlaying()
            showToast("Shuffling ${albumSongsList.size} songs from ${currentAlbum?.name ?: "Album"}")
        } else {
            showToast("No songs to shuffle")
        }
    }

    // NEW: Handles delete request
    private fun requestDeleteSong(song: Song) {
        try {
            val intentSender = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                MediaStore.createDeleteRequest(requireContext().contentResolver, listOf(song.uri)).intentSender
            } else null

            if (intentSender != null) {
                val request = IntentSenderRequest.Builder(intentSender).build()
                deleteResultLauncher.launch(request)
            } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                try {
                    requireContext().contentResolver.delete(song.uri, null, null)
                    Toast.makeText(requireContext(), "Song deleted successfully", Toast.LENGTH_SHORT).show()
                    refreshData()
                } catch (e: SecurityException) {
                    if (e is RecoverableSecurityException) {
                        val request = IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build()
                        deleteResultLauncher.launch(request)
                    } else throw e
                }
            } else {
                val deletedRows = requireContext().contentResolver.delete(song.uri, null, null)
                if (deletedRows > 0) {
                    Toast.makeText(requireContext(), "Song deleted successfully", Toast.LENGTH_SHORT).show()
                    refreshData()
                } else {
                    Toast.makeText(requireContext(), "Could not delete song (pre-Q).", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e("AlbumSongsFragment", "Error requesting delete for ${song.uri}", e)
            Toast.makeText(requireContext(), "Error requesting deletion.", Toast.LENGTH_SHORT).show()
        }
    }


    // UPDATED: Use albumSongsList
    private fun openNowPlaying(position: Int) {
        if (position < 0 || position >= albumSongsList.size) return
        val songToPlay = albumSongsList[position]
        PreferenceManager.addRecentSong(requireContext(), songToPlay.id)
        val service = (requireActivity() as MainActivity).getMusicService()
        service?.startPlayback(ArrayList(albumSongsList), position) // Use the local list
        (requireActivity() as MainActivity).navigateToNowPlaying()
    }

    private fun showToast(message: String) { // Unchanged
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_SHORT).show()
    }

    fun refreshData() { // Unchanged
        // Reload the album data if needed, then setup
        currentAlbum?.let {
            // Assuming album object might have stale song list, refetch or update it here if necessary
            // For now, just re-setup with existing data
            setupAlbumSongs(it)
        }
    }
}