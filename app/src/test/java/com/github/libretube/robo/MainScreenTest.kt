package com.github.libretube.robo

import androidx.navigation.fragment.NavHostFragment
import com.github.libretube.R
import com.github.libretube.ui.activities.MainActivity
import com.github.libretube.ui.fragments.TabsFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** The main screen starts with the dock's tabs in the swipeable pager, on the start tab. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LibApp::class, qualifiers = "w411dp-h891dp-port")
class MainScreenTest {
    @Test fun startsOnTheTabPager() {
        // past the first-run welcome screen
        com.github.libretube.helpers.PreferenceHelper.putBoolean(
            com.github.libretube.constants.PreferenceKeys.WELCOME_ACTIVITY_FINISHED, true
        )
        // a (simulated) internet connection, so the no-internet screen isn't shown
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        org.robolectric.Shadows.shadowOf(caps).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        org.robolectric.Shadows.shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork, caps)

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper()
        val navHost = activity.supportFragmentManager.findFragmentById(R.id.fragment) as NavHostFragment
        assertEquals(R.id.tabsFragment, navHost.navController.currentDestination?.id)
        val tabs = navHost.childFragmentManager.fragments.firstOrNull { it is TabsFragment } as? TabsFragment
        assertNotNull(tabs)
        println("TABS current=${tabs!!.currentTabId?.let { activity.resources.getResourceEntryName(it) }} dock=${activity.dockTabIds().map { activity.resources.getResourceEntryName(it) }}")
        assertEquals(activity.startTabId(), tabs.currentTabId)

        // selecting another dock tab moves the pager
        val other = activity.dockTabIds().first { it != tabs.currentTabId }
        activity.showScreen(other)
        ShadowLooper.idleMainLooper()
        assertEquals(other, tabs.currentTabId)
    }
}
