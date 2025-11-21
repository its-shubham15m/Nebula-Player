package com.shubhamgupta.nebula_player.fragments

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.fragment.app.Fragment
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
        (requireActivity() as com.shubhamgupta.nebula_player.MainActivity).setDrawerLocked(true)
    }

    override fun onPause() {
        super.onPause()
        // Unlock drawer when leaving fragment
        (requireActivity() as com.shubhamgupta.nebula_player.MainActivity).setDrawerLocked(false)
    }

    @SuppressLint("SetTextI18n")
    private fun initializeViews(view: View) {
        btnBack = view.findViewById(R.id.btn_back)

        btnBack.setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }

        val appVersion = (activity as? MainActivity)?.getAppVersionName() ?: "v?"
        view.findViewById<TextView>(R.id.tv_developer_name).text = "Shubham Gupta"
        view.findViewById<TextView>(R.id.tv_developer_email).text = "shubhamgupta15m@gmail.com"
        view.findViewById<TextView>(R.id.tv_app_version).text = "Nebula Music $appVersion"
        view.findViewById<TextView>(R.id.tv_app_description).text = "A beautiful music player with modern UI and smooth experience."
    }
}