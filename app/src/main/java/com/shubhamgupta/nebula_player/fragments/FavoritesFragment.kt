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
import androidx.appcompat.app.AlertDialog
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

class FavoritesFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var btnBack: ImageButton
    private var musicService: MusicService? = null
    private val favoriteSongs = mutableListOf<Song>()
    private lateinit var adapter: SongAdapter

    // Delete request launcher
    private lateinit var deleteResultLauncher: ActivityResultLauncher<IntentSenderRequest>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deleteResultLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Toast.makeText(requireContext(), "Song deleted successfully", Toast.LENGTH_SHORT).show()
                refreshData()
            } else {
                Toast.makeText(requireContext(), "Song could not be deleted", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_favorites, container, false)
        initializeViews(view)
        musicService = (requireActivity() as MainActivity).getMusicService()
        loadFavorites()
        return view
    }

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
        loadFavorites()
    }

    override fun onPause() {
        super.onPause()
        (requireActivity() as MainActivity).setDrawerLocked(false)
    }

    private fun initializeViews(view: View) {
        recyclerView = view.findViewById(R.id.recycler_view_fav)
        tvEmpty = view.findViewById(R.id.tv_empty_fav)
        btnBack = view.findViewById(R.id.btn_back)
        val shuffleCard = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.shuffle_all_card)

        btnBack.setOnClickListener {
            // Check if we are inside HomePageFragment and pop its stack
            val parent = parentFragment
            if (parent is HomePageFragment) {
                parent.childFragmentManager.popBackStack()
            } else {
                requireActivity().supportFragmentManager.popBackStack()
            }
        }
        shuffleCard.setOnClickListener {
            shuffleFavorites()
        }
        view.findViewById<ImageButton>(R.id.btn_sort).setOnClickListener {
            showSortDialog()
        }
        view.findViewById<ImageButton>(R.id.btn_select).setOnClickListener {
            showToast("Select mode - Coming soon")
        }
    }

    private fun loadFavorites() {
        val favoriteIds = PreferenceManager.getFavorites(requireContext())
        val allSongs = SongRepository.getAllSongs(requireContext())
        favoriteSongs.clear()
        favoriteSongs.addAll(allSongs.filter { it.id in favoriteIds }.sortedByDescending { it.dateAdded })

        if (favoriteSongs.isEmpty()) {
            recyclerView.visibility = View.GONE
            tvEmpty.visibility = View.VISIBLE
            view?.findViewById<com.google.android.material.card.MaterialCardView>(R.id.shuffle_all_card)?.visibility = View.GONE
        } else {
            recyclerView.visibility = View.VISIBLE
            tvEmpty.visibility = View.GONE
            view?.findViewById<com.google.android.material.card.MaterialCardView>(R.id.shuffle_all_card)?.visibility = View.VISIBLE
        }
        setupRecyclerView()
    }

    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(context)
        adapter = SongAdapter(
            context = requireContext(),
            songs = favoriteSongs,
            onItemClick = { position -> playSong(position) },
            onDataChanged = { refreshData() },
            onDeleteRequest = { song -> requestDeleteSong(song) }
        )
        recyclerView.adapter = adapter
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
            Log.e("FavoritesFragment", "Error requesting delete for ${song.uri}", e)
            Toast.makeText(requireContext(), "Error requesting deletion.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun playSong(position: Int) {
        if (position < 0 || position >= favoriteSongs.size) return
        val songToPlay = favoriteSongs[position]
        PreferenceManager.addRecentSong(requireContext(), songToPlay.id)
        musicService?.startPlayback(ArrayList(favoriteSongs), position)
        (requireActivity() as MainActivity).navigateToNowPlaying()
    }

    private fun shuffleFavorites() {
        if (favoriteSongs.isNotEmpty()) {
            val shuffledSongs = favoriteSongs.shuffled()
            musicService?.startPlayback(ArrayList(shuffledSongs), 0)
            musicService?.toggleShuffle()
            (requireActivity() as MainActivity).navigateToNowPlaying()
            showToast("Shuffling ${favoriteSongs.size} favorite songs")
        } else {
            showToast("No favorite songs to shuffle")
        }
    }

    private fun showSortDialog() {
        val items = arrayOf("Name (A-Z)", "Name (Z-A)", "Date Added (Newest)", "Date Added (Oldest)")
        AlertDialog.Builder(requireContext())
            .setTitle("Sort by")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> sortFavoritesByName(true)
                    1 -> sortFavoritesByName(false)
                    2 -> sortFavoritesByDate(true)
                    3 -> sortFavoritesByDate(false)
                }
            }
            .show()
    }

    private fun sortFavoritesByName(ascending: Boolean) {
        favoriteSongs.sortBy { it.title.lowercase() }
        if (!ascending) favoriteSongs.reverse()
        adapter.notifyDataSetChanged()
        showToast("Sorted by name ${if (ascending) "A-Z" else "Z-A"}")
    }

    private fun sortFavoritesByDate(descending: Boolean) {
        favoriteSongs.sortBy { it.dateAdded }
        if (descending) favoriteSongs.reverse()
        adapter.notifyDataSetChanged()
        showToast("Sorted by date ${if (descending) "newest first" else "oldest first"}")
    }

    private fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    fun refreshData() {
        loadFavorites()
    }
}