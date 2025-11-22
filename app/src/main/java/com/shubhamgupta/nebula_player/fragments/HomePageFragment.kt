package com.shubhamgupta.nebula_player.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.shubhamgupta.nebula_player.MainActivity
import com.shubhamgupta.nebula_player.R

class HomePageFragment : Fragment() {

    private lateinit var bottomNavigationView: BottomNavigationView

    // Track the currently active fragment to hide it when switching
    private var activeFragment: Fragment? = null

    // Tags for child fragments
    private val TAG_AUDIO = "audio_page"
    private val TAG_VIDEO = "video_page"
    private val TAG_SEARCH = "search_page"
    private val TAG_BROWSE = "browse_page"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home_page, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bottomNavigationView = view.findViewById(R.id.bottom_navigation)

        bottomNavigationView.doOnLayout {
            updateMiniPlayerPosition()
        }

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (imeVisible) {
                bottomNavigationView.visibility = View.GONE
                (requireActivity() as? MainActivity)?.setMiniPlayerBottomMargin(0)
            } else {
                bottomNavigationView.visibility = View.VISIBLE
                bottomNavigationView.post { updateMiniPlayerPosition() }
            }
            insets
        }

        bottomNavigationView.setOnItemSelectedListener { item ->
            handleTabSelection(item.itemId)
            true
        }

        bottomNavigationView.setOnItemReselectedListener { item ->
            if (item.itemId == R.id.nav_search) {
                val fragment = childFragmentManager.findFragmentByTag(TAG_SEARCH) as? SearchFragment
                fragment?.focusSearchInput()
            }
        }

        val currentNavFragment = childFragmentManager.primaryNavigationFragment
        if (currentNavFragment != null) {
            activeFragment = currentNavFragment
            val targetTabId = when (currentNavFragment.tag) {
                TAG_VIDEO -> R.id.nav_video
                TAG_SEARCH -> R.id.nav_search
                TAG_BROWSE -> R.id.nav_browse
                TAG_AUDIO -> R.id.nav_audio
                else -> bottomNavigationView.selectedItemId
            }

            if (bottomNavigationView.selectedItemId != targetTabId) {
                bottomNavigationView.selectedItemId = targetTabId
            }
        } else {
            if (savedInstanceState == null) {
                handleTabSelection(R.id.nav_audio)
            }
        }
    }

    private fun handleTabSelection(itemId: Int) {
        if (childFragmentManager.backStackEntryCount > 0) {
            childFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }

        when (itemId) {
            R.id.nav_audio -> {
                switchFragment(TAG_AUDIO) { MusicPageFragment.newInstance() }
                setMiniPlayerVisible(true)
                (requireActivity() as? MainActivity)?.setDrawerLocked(false)
            }
            R.id.nav_video -> {
                switchFragment(TAG_VIDEO) { VideosFragment() }
                setMiniPlayerVisible(false)
                // RESTRICT SIDEBAR: Locked for Videos as requested
                (requireActivity() as? MainActivity)?.setDrawerLocked(true)
            }
            R.id.nav_search -> {
                switchFragment(TAG_SEARCH) { SearchFragment() }
                setMiniPlayerVisible(true)
                (requireActivity() as? MainActivity)?.setDrawerLocked(true)
            }
            R.id.nav_browse -> {
                switchFragment(TAG_BROWSE) { BrowseFragment() }
                setMiniPlayerVisible(true)
                (requireActivity() as? MainActivity)?.setDrawerLocked(true)
            }
        }
    }

    // Navigation Methods
    fun navigateToFavorites() {
        childFragmentManager.commit {
            setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right)
            replace(R.id.home_content_container, FavoritesFragment(), "favorites")
            addToBackStack("favorites")
        }
        updateMiniPlayerPosition()
    }

    fun navigateToPlaylists() {
        childFragmentManager.commit {
            setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right)
            replace(R.id.home_content_container, PlaylistsFragment(), "playlists")
            addToBackStack("playlists")
        }
        updateMiniPlayerPosition()
    }

    fun navigateToRecents() {
        childFragmentManager.commit {
            setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right)
            replace(R.id.home_content_container, RecentFragment(), "recents")
            addToBackStack("recents")
        }
        updateMiniPlayerPosition()
    }

    private fun switchFragment(tag: String, createFragment: () -> Fragment) {
        val transaction = childFragmentManager.beginTransaction()
        activeFragment?.let { transaction.hide(it) }

        var targetFragment = childFragmentManager.findFragmentByTag(tag)
        if (targetFragment == null) {
            targetFragment = createFragment()
            transaction.add(R.id.home_content_container, targetFragment, tag)
        } else {
            transaction.show(targetFragment)
        }

        transaction.setPrimaryNavigationFragment(targetFragment)
        transaction.setReorderingAllowed(true)
        transaction.commit()

        activeFragment = targetFragment
    }

    fun updateMiniPlayerPosition() {
        if (bottomNavigationView.visibility == View.VISIBLE) {
            val height = bottomNavigationView.height
            val offsetPx = (4 * resources.displayMetrics.density).toInt()
            val margin = if (height > 0) height + offsetPx else offsetPx
            (requireActivity() as? MainActivity)?.setMiniPlayerBottomMargin(margin)
        } else {
            (requireActivity() as? MainActivity)?.setMiniPlayerBottomMargin(0)
        }
    }

    // UPDATED: Handle Back Press to support Folder Navigation
    fun handleBackPress(): Boolean {
        // 1. Check back stack (Favorites, Playlists, etc.)
        if (childFragmentManager.backStackEntryCount > 0) {
            childFragmentManager.popBackStack()
            childFragmentManager.addOnBackStackChangedListener(object : androidx.fragment.app.FragmentManager.OnBackStackChangedListener {
                override fun onBackStackChanged() {
                    if (childFragmentManager.backStackEntryCount == 0) {
                        handleTabSelection(bottomNavigationView.selectedItemId)
                    }
                    childFragmentManager.removeOnBackStackChangedListener(this)
                }
            })
            return true
        }

        // 2. Check if VideosFragment needs to handle back (Folder navigation)
        val videoFragment = childFragmentManager.findFragmentByTag(TAG_VIDEO) as? VideosFragment
        if (videoFragment != null && videoFragment.isVisible && videoFragment.handleBackPress()) {
            return true
        }

        return false
    }

    override fun onResume() {
        super.onResume()
        bottomNavigationView.post { updateMiniPlayerPosition() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        (requireActivity() as? MainActivity)?.setMiniPlayerBottomMargin(0)
    }

    private fun setMiniPlayerVisible(visible: Boolean) {
        (requireActivity() as? MainActivity)?.setMiniPlayerVisibility(visible)
    }

    fun refreshData() {
        val activeFragment = childFragmentManager.findFragmentById(R.id.home_content_container)
        if (activeFragment is MusicPageFragment) activeFragment.refreshData()
        else if (activeFragment is VideosFragment) activeFragment.refreshData()
        else if (activeFragment is FavoritesFragment) activeFragment.refreshData()
        else if (activeFragment is PlaylistsFragment) activeFragment.refreshData()
        else if (activeFragment is RecentFragment) activeFragment.refreshData()
    }

    fun switchToTab(tabId: Int) {
        bottomNavigationView.selectedItemId = tabId
    }

    companion object {
        fun newInstance(): HomePageFragment = HomePageFragment()
    }
}