package com.github.libretube.robo

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.github.libretube.helpers.ThemeHelper
import com.github.libretube.ui.adapters.IconsSheetAdapter
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every app icon option points to a launcher alias that exists (it broke in release builds). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LibApp::class)
class AppIconTest {
    @Test fun everyIconOptionExists() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val missing = IconsSheetAdapter.availableIcons.filter { icon ->
            val component = ComponentName(context.packageName, ThemeHelper.iconAliasClass(icon.activityAlias))
            runCatching { context.packageManager.getActivityInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS) }.isFailure
        }.map { it.activityAlias }
        println("ICON aliases checked=${IconsSheetAdapter.availableIcons.size} missing=$missing")
        assertTrue("missing launcher aliases: $missing", missing.isEmpty())
    }
}
