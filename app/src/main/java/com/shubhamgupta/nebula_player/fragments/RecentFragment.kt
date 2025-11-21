package com.shubhamgupta.nebula_player.fragments

import android.app.Activity
import android.app.RecoverableSecurityException
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
import androidx.appcompat.app.AlertDialog // Use AppCompat AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shubhamgupta.nebula_player.MainActivity
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.adapters.SongAdapter
import com.shubhamgupta.nebula_player.models.Song
import com.shubhamgupta.nebula_player.repository.SongRepository
import com.shubhamgupta.nebula_player.service.MusicService
import com.shubhamgupta.nebula_player.utils.PreferenceManager

class RecentFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var btnBack: ImageButton
    private var musicService: MusicService? = null
    private val recentSongs = mutableListOf<Song>()
    private lateinit var adapter: SongAdapter // Keep adapter reference

    // Delete request launcher
    private lateinit var deleteResultLauncher: ActivityResultLauncher<IntentSenderRequest>

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
        val view = inflater.inflate(R.layout.fragment_recent, container, false)
        initializeViews(view)
        loadRecentSongs() // Load initial data
        return view
    }

    override fun onResume() { // Unchanged
        super.onResume()
        (requireActivity() as MainActivity).setDrawerLocked(true)
        loadRecentSongs() // Refresh on resume
    }

    override fun onPause() { // Unchanged
        super.onPause()
        (requireActivity() as MainActivity).setDrawerLocked(false)
    }

    private fun initializeViews(view: View) { // Unchanged
        recyclerView = view.findViewById(R.id.recycler_view_recent)
        tvEmpty = view.findViewById(R.id.tv_empty_recent)
        btnBack = view.findViewById(R.id.btn_back)
        val shuffleCard = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.shuffle_all_card)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        btnBack.setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }
        shuffleCard.setOnClickListener {
            shuffleRecentSongs()
        }
        view.findViewById<ImageButton>(R.id.btn_sort).setOnClickListener {
            showSortDialog()
        }
        view.findViewById<ImageButton>(R.id.btn_select).setOnClickListener {
            showToast("Select mode - Coming soon")
        }
        try {
            musicService = (requireActivity() as MainActivity).getMusicService()
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun loadRecentSongs() { // Unchanged
        val recentSongIds = PreferenceManager.getRecentSongs(requireContext())
        val allSongs = SongRepository.getAllSongs(requireContext()) // Consider optimization if slow
        recentSongs.clear()
        // Maintain the order from recentSongIds
        recentSongs.addAll(recentSongIds.mapNotNull { id -> allSongs.find { it.id == id } })

        if (recentSongs.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            view?.findViewById<com.google.android.material.card.MaterialCardView>(R.id.shuffle_all_card)?.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            view?.findViewById<com.google.android.material.card.MaterialCardView>(R.id.shuffle_all_card)?.visibility = View.VISIBLE
        }
        setupRecyclerView() // Call setup after loading
    }

    // UPDATED: Correct adapter call
    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter = SongAdapter( // Assign to class member
            context = requireContext(),
            songs = recentSongs,
            onItemClick = { position -> playSong(position) },
            onDataChanged = { refreshData() }, // Use refreshData
            onDeleteRequest = { song -> requestDeleteSong(song) }
        )
        recyclerView.adapter = adapter
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
            Log.e("RecentFragment", "Error requesting delete for ${song.uri}", e)
            Toast.makeText(requireContext(), "Error requesting deletion.", Toast.LENGTH_SHORT).show()
        }
    }


    private fun playSong(position: Int) { // Unchanged
        if (position < 0 || position >= recentSongs.size) return
        val songToPlay = recentSongs[position]
        PreferenceManager.addRecentSong(requireContext(), songToPlay.id) // Move to top
        musicService?.startPlayback(ArrayList(recentSongs), position)
        (requireActivity() as MainActivity).navigateToNowPlaying()
    }

    private fun shuffleRecentSongs() { // Unchanged
        if (recentSongs.isNotEmpty()) {
            val shuffledSongs = recentSongs.shuffled()
            musicService?.startPlayback(ArrayList(shuffledSongs), 0)
            musicService?.toggleShuffle()
            (requireActivity() as MainActivity).navigateToNowPlaying()
            showToast("Shuffling ${recentSongs.size} recent songs")
        } else {
            showToast("No recent songs to shuffle")
        }
    }

    private fun showSortDialog() { // Unchanged (Use AppCompat AlertDialog)
        val items = arrayOf("Most Recent", "Least Recent", "Name (A-Z)", "Name (Z-A)")
        AlertDialog.Builder(requireContext()) // Use AppCompat
            .setTitle("Sort by")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> sortRecentByDate(true)
                    1 -> sortRecentByDate(false)
                    2 -> sortRecentByName(true)
                    3 -> sortRecentByName(false)
                }
            }
            .show()
    }

    private fun sortRecentByDate(mostRecentFirst: Boolean) { // Unchanged
        val recentSongIds = PreferenceManager.getRecentSongs(requireContext())
        // Sorting logic based on original index in recentSongIds
        recentSongs.sortBy { song -> recentSongIds.indexOf(song.id) }
        if (!mostRecentFirst) {
            recentSongs.reverse() // Reverse for least recent first
        }
        adapter.notifyDataSetChanged() // Use class member adapter
        showToast("Sorted by ${if (mostRecentFirst) "most recent" else "least recent"}")
    }


    private fun sortRecentByName(ascending: Boolean) { // Unchanged
        recentSongs.sortBy { it.title.lowercase() } // Sort by lowercase
        if (!ascending) recentSongs.reverse()
        adapter.notifyDataSetChanged() // Use class member adapter
        showToast("Sorted by name ${if (ascending) "A-Z" else "Z-A"}")
    }

    private fun showToast(message: String) { // Unchanged
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    fun refreshData() { // Unchanged
        loadRecentSongs()
    }
}