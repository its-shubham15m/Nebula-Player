package com.shubhamgupta.nebula_player.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.shubhamgupta.nebula_player.R

class BrowseFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the new dashboard-style layout
        return inflater.inflate(R.layout.fragment_browse_page, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // In a real implementation, you would initialize RecyclerView adapters here
        // to populate the "Recently Played" and "Videos" sections dynamically.
    }
}