package com.shubhamgupta.nebula_player.fragments

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

class BrowseFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Simple placeholder view for Browse functionality
        val textView = TextView(context).apply {
            text = "File Browser\n(Coming Soon)"
            textSize = 20f
            gravity = Gravity.CENTER
        }
        return textView
    }
}