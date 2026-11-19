package com.github.libretube.helpers

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.github.libretube.R
import com.github.libretube.api.JsonHelper
import com.github.libretube.obj.PreferenceItem

/**
 * BetterTube ships with a set of default settings (res/raw/default_preferences.json, in the
 * same format as a backup's preferences), so a fresh install doesn't need to be configured.
 *
 * Applied once, before preference migrations run. Settings that already have a value on the
 * device are never overwritten.
 */
object DefaultPreferences {
    private const val APPLIED_KEY = "bettertube_default_preferences_applied"

    fun applyIfNeeded(context: Context) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        if (preferences.getBoolean(APPLIED_KEY, false)) return

        val items = runCatching {
            context.resources.openRawResource(R.raw.default_preferences).bufferedReader().use {
                JsonHelper.json.decodeFromString<List<PreferenceItem>>(it.readText())
            }
        }.getOrElse { emptyList() }

        preferences.edit(commit = true) {
            items.forEach { item ->
                val key = item.key ?: return@forEach
                if (!preferences.contains(key)) BackupHelper.putPreference(this, key, item.value)
            }
            putBoolean(APPLIED_KEY, true)
        }
    }
}
