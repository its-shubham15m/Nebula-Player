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
import com.shubhamgupta.nebula_player.models.Artist
import com.shubhamgupta.nebula_player.models.Song
import com.shubhamgupta.nebula_player.utils.PreferenceManager

class ArtistSongsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var artistNameView: TextView
    private lateinit var songCountView: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var tvEmpty: TextView
    private var currentArtist: Artist? = null
    private var artistSongsList = mutableListOf<Song>()

    // Delete request launcher
    private lateinit var deleteResultLauncher: ActivityResultLauncher<IntentSenderRequest>

    private val playbackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "SONG_CHANGED", "PLAYBACK_STATE_CHANGED" -> {
                    currentArtist?.let { setupArtistSongs(it) }
                }
            }
        }
    }

    companion object {
        private const val ARG_ARTIST = "artist"
        fun newInstance(artist: Artist): ArtistSongsFragment {
            val fragment = ArtistSongsFragment()
            val args = Bundle()
            args.putParcelable(ARG_ARTIST, artist)
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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_artist_songs, container, false)
        initializeViews(view)
        return view
    }

    // UPDATED: Added to fix layout padding issue (same as FavoritesFragment)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Add padding to the bottom of RecyclerView so the last item is not hidden behind
        // the MiniPlayer and Bottom Navigation
        val bottomPadding = (140 * resources.displayMetrics.density).toInt() // Approx 140dp
        recyclerView.clipToPadding = false
        recyclerView.setPadding(0, 0, 0, bottomPadding)
    }

    override fun onResume() {
        super.onResume()
        (requireActivity() as MainActivity).setDrawerLocked(true)
        val filter = IntentFilter().apply {
            addAction("SONG_CHANGED")
            addAction("PLAYBACK_STATE_CHANGED")
        }
        val receiverFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_NOT_EXPORTED else 0
        requireActivity().registerReceiver(playbackReceiver, filter, receiverFlags)
        currentArtist?.let { setupArtistSongs(it) }
    }

    override fun onPause() {
        super.onPause()
        (requireActivity() as MainActivity).setDrawerLocked(false)
        try {
            requireActivity().unregisterReceiver(playbackReceiver)
        } catch (e: Exception) { /* Ignore */ }
    }

    private fun initializeViews(view: View) {
        recyclerView = view.findViewById(R.id.recycler_view_artist_songs)
        artistNameView = view.findViewById(R.id.artist_name)
        songCountView = view.findViewById(R.id.artist_song_count)
        btnBack = view.findViewById(R.id.btn_back)
        tvEmpty = view.findViewById(R.id.tv_empty_artist_songs)
        recyclerView.layoutManager = LinearLayoutManager(context)

        currentArtist = arguments?.getParcelable(ARG_ARTIST)
        currentArtist?.let { setupArtistSongs(it) }

        // UPDATED: Correct back navigation handling
        btnBack.setOnClickListener {
            val parent = parentFragment
            if (parent is HomePageFragment) {
                parent.childFragmentManager.popBackStack()
            } else {
                requireActivity().supportFragmentManager.popBackStack()
            }
        }

        val shuffleCard = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.shuffle_all_card)
        shuffleCard.setOnClickListener {
            shuffleArtistSongs()
        }
    }

    private fun setupArtistSongs(artist: Artist) {
        artistNameView.text = artist.name
        songCountView.text = "${artist.songCount} songs"

        // Update local list
        artistSongsList.clear()
        artistSongsList.addAll(artist.songs)

        if (artistSongsList.isEmpty()) {
            recyclerView.visibility = View.GONE
            tvEmpty.visibility = View.VISIBLE
            view?.findViewById<com.google.android.material.card.MaterialCardView>(R.id.shuffle_all_card)?.visibility = View.GONE
        } else {
            recyclerView.visibility = View.VISIBLE
            tvEmpty.visibility = View.GONE
            view?.findViewById<com.google.android.material.card.MaterialCardView>(R.id.shuffle_all_card)?.visibility = View.VISIBLE

            val adapter = SongAdapter(
                context = requireContext(),
                songs = artistSongsList,
                onItemClick = { pos -> openNowPlaying(pos) },
                onDataChanged = { refreshData() },
                onDeleteRequest = { song -> requestDeleteSong(song) }
            )
            recyclerView.adapter = adapter
        }
    }

    private fun shuffleArtistSongs() {
        if (artistSongsList.isNotEmpty()) {
            val shuffledSongs = artistSongsList.shuffled()
            val service = (requireActivity() as MainActivity).getMusicService()
            service?.startPlayback(ArrayList(shuffledSongs), 0)
            service?.toggleShuffle()
            (requireActivity() as MainActivity).navigateToNowPlaying()
            showToast("Shuffling ${artistSongsList.size} songs by ${currentArtist?.name ?: "Artist"}")
        } else {
            showToast("No songs to shuffle")
        }
    }

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
            Log.e("ArtistSongsFragment", "Error requesting delete for ${song.uri}", e)
            Toast.makeText(requireContext(), "Error requesting deletion.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openNowPlaying(position: Int) {
        if (position < 0 || position >= artistSongsList.size) return
        val songToPlay = artistSongsList[position]
        PreferenceManager.addRecentSong(requireContext(), songToPlay.id)
        val service = (requireActivity() as MainActivity).getMusicService()
        service?.startPlayback(ArrayList(artistSongsList), position)
        (requireActivity() as MainActivity).navigateToNowPlaying()
    }

    private fun showToast(message: String) {
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_SHORT).show()
    }

    fun refreshData() {
        currentArtist?.let {
            setupArtistSongs(it)
        }
    }
}