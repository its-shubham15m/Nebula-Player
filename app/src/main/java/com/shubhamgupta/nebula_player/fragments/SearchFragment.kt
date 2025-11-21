package com.shubhamgupta.nebula_player.fragments

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
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.bumptech.glide.Glide
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.shubhamgupta.nebula_player.MainActivity
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.adapters.SearchHistoryAdapter
import com.shubhamgupta.nebula_player.models.Song
import com.shubhamgupta.nebula_player.repository.SongRepository
import com.shubhamgupta.nebula_player.utils.SearchHistoryManager
import com.shubhamgupta.nebula_player.utils.SongUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class SearchFragment : Fragment() {

    private lateinit var searchBar: EditText
    private lateinit var btnVoiceSearch: ImageButton
    private lateinit var historyRecyclerView: RecyclerView
    private lateinit var resultsContainer: View
    private lateinit var idleContainer: NestedScrollView
    private lateinit var suggestionsRecyclerView: RecyclerView
    private lateinit var btnRefreshSuggestions: ImageButton
    private lateinit var historyAdapter: SearchHistoryAdapter
    private lateinit var searchScopeGroup: ChipGroup

    private val handler = Handler(Looper.getMainLooper())
    private var currentScope = "songs" // 'songs' or 'videos'

    // Receiver to sync search bar if search is initiated from Home Page mic
    private val searchUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "SEARCH_QUERY_CHANGED") {
                val query = intent.getStringExtra("query") ?: return
                val scope = intent.getStringExtra("scope")

                if (scope != null && scope != currentScope) {
                    // Update scope if provided
                    currentScope = scope
                    if (currentScope == "videos") {
                        searchScopeGroup.check(R.id.chip_scope_videos)
                    } else {
                        searchScopeGroup.check(R.id.chip_scope_songs)
                    }
                }

                if (searchBar.text.toString() != query) {
                    searchBar.setText(query)
                    searchBar.setSelection(query.length)
                }
            }
        }
    }

    private val voiceRecognitionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val results = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            results?.get(0)?.let { spokenText ->
                searchBar.setText(spokenText)
                searchBar.setSelection(spokenText.length)
                performSearch(spokenText)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_search, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeViews(view)
        setupHistoryRecycler()
        setupSuggestionsRecycler()
        setupSearchResultsFragment() // Loads default songs fragment
        setupSearchLogic()
        setupScopeChips(view)

        // Load initial random suggestions
        loadSuggestions()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("SEARCH_QUERY_CHANGED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireActivity().registerReceiver(searchUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            requireActivity().registerReceiver(searchUpdateReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            requireActivity().unregisterReceiver(searchUpdateReceiver)
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun initializeViews(view: View) {
        searchBar = view.findViewById(R.id.search_bar)
        btnVoiceSearch = view.findViewById(R.id.btn_voice_search)
        historyRecyclerView = view.findViewById(R.id.history_recycler_view)
        suggestionsRecyclerView = view.findViewById(R.id.suggestions_recycler_view)
        btnRefreshSuggestions = view.findViewById(R.id.btn_refresh_suggestions)
        resultsContainer = view.findViewById(R.id.search_results_container)
        idleContainer = view.findViewById(R.id.search_idle_container)
        searchScopeGroup = view.findViewById(R.id.search_scope_group)

        btnVoiceSearch.setOnClickListener {
            startVoiceRecognition()
        }

        btnRefreshSuggestions.setOnClickListener {
            loadSuggestions()
        }
    }

    private fun setupScopeChips(view: View) {
        val chipSongs = view.findViewById<Chip>(R.id.chip_scope_songs)
        val chipVideos = view.findViewById<Chip>(R.id.chip_scope_videos)

        chipSongs.isChecked = true

        searchScopeGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.chip_scope_songs -> {
                    currentScope = "songs"
                    setupSearchResultsFragment()
                    if (searchBar.text.isNotEmpty()) performSearch(searchBar.text.toString())
                }
                R.id.chip_scope_videos -> {
                    currentScope = "videos"
                    setupSearchResultsFragment()
                    if (searchBar.text.isNotEmpty()) performSearch(searchBar.text.toString())
                }
            }
        }
    }

    private fun setupHistoryRecycler() {
        val historyList = SearchHistoryManager.getHistory(requireContext()).toMutableList()

        historyAdapter = SearchHistoryAdapter(
            historyList,
            onItemClick = { query ->
                searchBar.setText(query)
                searchBar.setSelection(query.length)
                performSearch(query)
                hideKeyboard()
            },
            onDeleteClick = { query ->
                SearchHistoryManager.removeHistory(requireContext(), query)
                updateHistoryVisibility(historyAdapter.itemCount - 1)
            }
        )

        val layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.HORIZONTAL)
        historyRecyclerView.layoutManager = layoutManager
        historyRecyclerView.adapter = historyAdapter

        updateHistoryVisibility(historyList.size)
    }

    private fun setupSuggestionsRecycler() {
        suggestionsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        suggestionsRecyclerView.isNestedScrollingEnabled = false
    }

    private fun loadSuggestions() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val allSongs = SongRepository.getAllSongs(requireContext())
                val randomSongs = allSongs.asSequence().shuffled().take(5).toList()

                withContext(Dispatchers.Main) {
                    suggestionsRecyclerView.adapter = SuggestionsAdapter(randomSongs) { song ->
                        val mainActivity = requireActivity() as? MainActivity
                        mainActivity?.getMusicService()?.startPlayback(arrayListOf(song), 0)
                        mainActivity?.showNowPlayingPage()
                    }
                }
            } catch (e: Exception) {
                Log.e("SearchFragment", "Error loading suggestions", e)
            }
        }
    }

    private fun updateHistoryVisibility(itemCount: Int) {
        // Only show history if search bar is empty and we have items
        if (itemCount > 0 && searchBar.text.isNullOrEmpty()) {
            historyRecyclerView.visibility = View.VISIBLE
        } else {
            historyRecyclerView.visibility = View.GONE
        }
        updateContainerVisibility()
    }

    private fun updateContainerVisibility() {
        if (searchBar.text.isNullOrEmpty()) {
            // Idle State: Show ScrollView (History + Suggestions), Hide Results
            idleContainer.visibility = View.VISIBLE
            resultsContainer.visibility = View.GONE
        } else {
            // Search State: Hide Idle ScrollView, Show Results
            idleContainer.visibility = View.GONE
            resultsContainer.visibility = View.VISIBLE
        }
    }

    private fun setupSearchResultsFragment() {
        // Switch between SongsFragment and VideosFragment based on selection
        val fragment = if (currentScope == "videos") VideosFragment() else SongsFragment()

        childFragmentManager.commit {
            replace(R.id.search_results_container, fragment, "SEARCH_RESULTS")
        }
    }

    private fun setupSearchLogic() {
        searchBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString()
                performSearch(query)

                if (query.isEmpty()) {
                    val historyCount = SearchHistoryManager.getHistory(requireContext()).size
                    if (historyCount > 0) historyRecyclerView.visibility = View.VISIBLE
                } else {
                    historyRecyclerView.visibility = View.GONE
                }
                updateContainerVisibility()
            }
        })

        searchBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = searchBar.text.toString()
                if (query.isNotBlank()) {
                    SearchHistoryManager.saveHistory(requireContext(), query)
                    historyAdapter.updateData(SearchHistoryManager.getHistory(requireContext()))
                }
                hideKeyboard()
                return@setOnEditorActionListener true
            }
            false
        }
    }

    // Called from HomePageFragment when double-tapping the Search tab
    fun focusSearchInput() {
        if (this::searchBar.isInitialized) {
            searchBar.requestFocus()
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(searchBar, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to search song or video")
        }
        try {
            voiceRecognitionLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Voice recognition not supported", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("UnsafeImplicitIntentLaunch")
    private fun performSearch(query: String) {
        Log.d("SearchFragment", "Broadcasting search query: $query for scope: $currentScope")
        val intent = Intent("SEARCH_QUERY_CHANGED").apply {
            putExtra("query", query)
            putExtra("scope", currentScope) // Pass scope so receiver knows what to filter
        }
        requireActivity().sendBroadcast(intent)
    }

    private fun hideKeyboard() {
        if (this::searchBar.isInitialized) {
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(searchBar.windowToken, 0)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        hideKeyboard()
        performSearch("")
    }

    // Internal Adapter for Suggestions using item_song.xml IDs
    private inner class SuggestionsAdapter(
        private val songs: List<Song>,
        private val onItemClick: (Song) -> Unit
    ) : RecyclerView.Adapter<SuggestionsAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            // Updated IDs based on item_song.xml
            val title: TextView = view.findViewById(R.id.item_title)
            val artist: TextView = view.findViewById(R.id.item_artist)
            val albumArt: ImageView = view.findViewById(R.id.item_album_art)
            val options: ImageView = view.findViewById(R.id.item_options)

            init {
                view.setOnClickListener { onItemClick(songs[bindingAdapterPosition]) }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            // Using item_song.xml which is standard for the app
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val song = songs[position]
            holder.title.text = song.title
            holder.artist.text = song.artist

            // Hide options menu for suggestions as it's a quick pick list
            holder.options.visibility = View.GONE

            Glide.with(holder.itemView.context)
                .load(SongUtils.getAlbumArtUri(song.albumId))
                .placeholder(R.drawable.default_album_art)
                .into(holder.albumArt)
        }

        override fun getItemCount(): Int = songs.size
    }
}