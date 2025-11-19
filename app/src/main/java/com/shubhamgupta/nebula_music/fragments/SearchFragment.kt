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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.shubhamgupta.nebula_music.R
import com.shubhamgupta.nebula_music.adapters.SearchHistoryAdapter
import com.shubhamgupta.nebula_music.utils.SearchHistoryManager
import java.util.Locale

class SearchFragment : Fragment() {

    private lateinit var searchBar: EditText
    private lateinit var btnBack: ImageButton
    private lateinit var btnVoiceSearch: ImageButton
    private lateinit var historyRecyclerView: RecyclerView
    private lateinit var resultsContainer: View
    private lateinit var historyAdapter: SearchHistoryAdapter

    private val handler = Handler(Looper.getMainLooper())

    // Receiver to sync search bar if search is initiated from Home Page mic
    private val searchUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "SEARCH_QUERY_CHANGED") {
                val query = intent.getStringExtra("query") ?: return
                if (searchBar.text.toString() != query) {
                    searchBar.setText(query)
                    searchBar.setSelection(query.length)
                    // The TextWatcher will handle showing the results container
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
        setupSearchResultsFragment()
        setupSearchLogic()

        // Focus search bar and show keyboard automatically
        searchBar.requestFocus()
        handler.postDelayed({
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(searchBar, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
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
        btnBack = view.findViewById(R.id.btn_back)
        btnVoiceSearch = view.findViewById(R.id.btn_voice_search)
        historyRecyclerView = view.findViewById(R.id.history_recycler_view)
        resultsContainer = view.findViewById(R.id.search_results_container)

        btnBack.setOnClickListener {
            hideKeyboard()
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        btnVoiceSearch.setOnClickListener {
            startVoiceRecognition()
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

    private fun updateHistoryVisibility(itemCount: Int) {
        // Only show history if search bar is empty and we have items
        if (itemCount > 0 && searchBar.text.isNullOrEmpty()) {
            historyRecyclerView.visibility = View.VISIBLE
            resultsContainer.visibility = View.GONE
        } else if (searchBar.text.isNullOrEmpty()) {
            historyRecyclerView.visibility = View.GONE
            resultsContainer.visibility = View.GONE
        }
    }

    private fun setupSearchResultsFragment() {
        // Use SongsFragment for results, but keeping it hidden initially
        childFragmentManager.commit {
            replace(R.id.search_results_container, SongsFragment(), "SEARCH_RESULTS")
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
                    // Show History, Hide Results
                    val historyCount = SearchHistoryManager.getHistory(requireContext()).size
                    if (historyCount > 0) historyRecyclerView.visibility = View.VISIBLE
                    resultsContainer.visibility = View.GONE
                } else {
                    // Hide History, Show Results
                    historyRecyclerView.visibility = View.GONE
                    resultsContainer.visibility = View.VISIBLE
                }
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

    @SuppressLint("UnsafeImplicitIntentLaunch")
    private fun performSearch(query: String) {
        Log.d("SearchFragment", "Broadcasting search query: $query")
        val intent = Intent("SEARCH_QUERY_CHANGED").apply {
            putExtra("query", query)
        }
        requireActivity().sendBroadcast(intent)
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchBar.windowToken, 0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        hideKeyboard()
        performSearch("")
    }
}