package com.github.libretube.helpers

import com.github.libretube.constants.PreferenceKeys

/**
 * Remembers whether the user last switched to audio or to video, so new videos open in the
 * same mode (e.g. to keep saving data while travelling). Can be turned off in the settings.
 */
object PlayerModeMemory {
    private val isEnabled
        get() = PreferenceHelper.getBoolean(PreferenceKeys.REMEMBER_PLAYER_MODE, true)

    /** Whether new videos should open in the audio player. */
    val prefersAudio: Boolean
        get() = isEnabled && PreferenceHelper.getBoolean(PreferenceKeys.LAST_PLAYER_MODE_AUDIO, false)

    /** Called when the user switches between the audio and the video player. */
    fun remember(audio: Boolean) {
        PreferenceHelper.putBoolean(PreferenceKeys.LAST_PLAYER_MODE_AUDIO, audio)
    }
}
