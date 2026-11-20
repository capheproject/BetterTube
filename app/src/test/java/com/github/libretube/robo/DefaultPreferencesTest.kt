package com.github.libretube.robo

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.github.libretube.helpers.DefaultPreferences
import com.github.libretube.helpers.PreferenceHelper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// ROBO-ONLY
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class)
class DefaultPreferencesTest {
    @Test
    fun appliesBundledSettingsWithoutOverwriting() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        prefs.edit().clear().putString("default_res", "1080p").commit() // user already changed this

        DefaultPreferences.applyIfNeeded(ctx)
        PreferenceHelper.initialize(ctx)
        PreferenceHelper.migrate()

        println("entries=${prefs.all.size}")
        listOf("default_res", "default_res_mobile", "theme_toggle", "accent_color", "dearrow",
            "sponsor_category", "home_tab_content", "playlist_sort_order", "sponsor_color", "PREFERENCE_VERSION")
            .forEach { println("  $it = ${prefs.all[it]} (${prefs.all[it]?.javaClass?.simpleName})") }

        assertEquals("1080p", prefs.getString("default_res", null)) // not overwritten
        assertEquals("480p", prefs.getString("default_res_mobile", null))
        assertEquals(true, prefs.getBoolean("dearrow", false))
        assertEquals(setOf("bookmarks", "playlists", "watching"), prefs.getStringSet("home_tab_content", null))
        assertEquals(-16722944, prefs.getInt("sponsor_color", 0))
        assertEquals(1, PreferenceHelper.getInt("playlist_sort_order", 0))

        // second call must not change anything
        prefs.edit().putString("default_res_mobile", "360p").commit()
        DefaultPreferences.applyIfNeeded(ctx)
        assertEquals("360p", prefs.getString("default_res_mobile", null))
    }
}
