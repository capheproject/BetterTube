package com.github.libretube.ui.fragments

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.github.libretube.R
import com.github.libretube.ui.activities.MainActivity

/**
 * The dock's top-level tabs side by side in a horizontal pager: swiping moves the neighbouring
 * tab in under the finger and snaps, and every tab stays loaded, so switching is instant.
 * Deeper screens (channel, playlist, search, ...) still open on top through navigation.
 */
class TabsFragment : Fragment(R.layout.fragment_tabs) {
    private var pager: ViewPager2? = null
    private val tabs: List<Int> get() = (activity as? MainActivity)?.dockTabIds().orEmpty()
    private var pageTabs: List<Int> = emptyList()

    /** The tab currently shown (a dock item id). */
    val currentTabId: Int?
        get() = pageTabs.getOrNull(pager?.currentItem ?: -1)

    /** The fragment of the tab currently shown. */
    val currentTabFragment: Fragment?
        get() = pager?.let { childFragmentManager.findFragmentByTag("f${it.currentItem}") }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pageTabs = tabs
        val pager = view.findViewById<ViewPager2>(R.id.tabs_pager)
        this.pager = pager
        pager.adapter = TabsAdapter(this, pageTabs)
        // all tabs stay loaded: the neighbour is ready when you swipe, switching back is instant
        pager.offscreenPageLimit = (pageTabs.size - 1).coerceAtLeast(1)

        // rows that scroll sideways inside a tab (playlist rows, chips, the Downloads tabs)
        // keep their own swipes; everywhere else, a sideways swipe moves between tabs
        childFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
                v.post { keepSidewaysScrollersOwnSwipes(v) }
            }
        }, true)

        val initial = savedInstanceState?.getInt(KEY_PAGE)
            ?: pageTabs.indexOf((activity as? MainActivity)?.startTabId()).takeIf { it >= 0 }
            ?: 0
        pager.setCurrentItem(initial, false)

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                pageTabs.getOrNull(position)?.let { (activity as? MainActivity)?.onTabShown(it) }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pager?.let { outState.putInt(KEY_PAGE, it.currentItem) }
    }

    override fun onDestroyView() {
        pager = null
        super.onDestroyView()
    }

    /** Shows the tab with the given dock item id; returns false if it isn't one of the tabs. */
    fun selectTab(tabId: Int, smooth: Boolean = true): Boolean {
        val index = pageTabs.indexOf(tabId)
        if (index < 0) return false
        val pager = pager ?: return false
        if (pager.currentItem != index) pager.setCurrentItem(index, smooth)
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun keepSidewaysScrollersOwnSwipes(root: View) {
        fun visit(view: View) {
            when (view) {
                is RecyclerView -> if (view.layoutManager?.canScrollHorizontally() == true &&
                    view.id != R.id.tabs_pager &&
                    // a nested pager with swiping turned off (Downloads' sub-tabs) leaves swipes to the tabs
                    (view.parent as? ViewPager2)?.isUserInputEnabled != false
                ) {
                    view.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
                        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                                rv.parent?.requestDisallowInterceptTouchEvent(true)
                            }
                            return false
                        }
                    })
                }
                is HorizontalScrollView -> view.setOnTouchListener { v, e ->
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
                    false
                }
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(root)
    }

    private class TabsAdapter(fragment: Fragment, private val tabIds: List<Int>) : FragmentStateAdapter(fragment) {
        override fun getItemCount() = tabIds.size
        override fun createFragment(position: Int): Fragment = when (tabIds[position]) {
            R.id.homeFragment -> HomeFragment()
            R.id.trendsFragment -> TrendsFragment()
            R.id.subscriptionsFragment -> SubscriptionsFragment()
            R.id.libraryFragment -> LibraryFragment()
            R.id.downloadsFragment -> DownloadsFragment()
            else -> HomeFragment()
        }
    }

    companion object {
        private const val KEY_PAGE = "tabs_page"
    }
}
