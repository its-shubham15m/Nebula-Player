package com.shubhamgupta.nebula_player.fragments

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.chip.Chip
import com.shubhamgupta.nebula_player.MainActivity
import com.shubhamgupta.nebula_player.R

class AboutFragment : Fragment() {

    private lateinit var btnBack: ImageButton

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_about, container, false)
        initializeViews(view)
        return view
    }

    override fun onResume() {
        super.onResume()
        // Lock drawer when fragment is visible
        (requireActivity() as? MainActivity)?.setDrawerLocked(true)
    }

    override fun onPause() {
        super.onPause()
        // Unlock drawer when leaving fragment
        (requireActivity() as? MainActivity)?.setDrawerLocked(false)
    }

    @SuppressLint("SetTextI18n")
    private fun initializeViews(view: View) {
        btnBack = view.findViewById(R.id.btn_back)

        btnBack.setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }

        val appVersion = (activity as? MainActivity)?.getAppVersionName() ?: "v1.0"

        // Update Header
        view.findViewById<Chip>(R.id.chip_version).text = "Beta $appVersion"

        // Update Developer Info
        view.findViewById<TextView>(R.id.tv_developer_name).text = "Shubham Gupta"
        view.findViewById<TextView>(R.id.tv_developer_email).text = "shubhamgupta15m@gmail.com"
    }
}