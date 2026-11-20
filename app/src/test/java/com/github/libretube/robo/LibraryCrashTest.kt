package com.github.libretube.robo

import android.os.Bundle
import com.github.libretube.R
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.fragments.LibraryFragment
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

class LibHost : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(android.widget.FrameLayout(this).apply { id = android.R.id.custom })
    }
}

class LibApp : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        com.github.libretube.helpers.PreferenceHelper.initialize(this)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LibApp::class, qualifiers = "w411dp-h891dp-port")
class LibraryCrashTest {
    @Test fun opens() {
        val act = Robolectric.buildActivity(LibHost::class.java).setup().get()
        act.supportFragmentManager.beginTransaction().replace(android.R.id.custom, LibraryFragment()).commitNow()
        repeat(5) { ShadowLooper.idleMainLooper(); Thread.sleep(200) }
        println("LIBRARY OK")
    }
}
