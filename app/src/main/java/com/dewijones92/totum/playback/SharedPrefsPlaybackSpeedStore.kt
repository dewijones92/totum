package com.dewijones92.totum.playback

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [PlaybackSpeedStore] backed by [android.content.SharedPreferences] — one value for everything.
 *
 * The per-source entries the old version wrote are simply left behind rather than migrated. They
 * are a speed each, on a key that is now never read; the alternative is guessing which of them
 * should become the single global value, and guessing would change the speed on its own, which is
 * the whole thing being fixed.
 */
public class SharedPrefsPlaybackSpeedStore(context: Context) : PlaybackSpeedStore {

    private val prefs = context.getSharedPreferences("totum_playback_speed", Context.MODE_PRIVATE)

    override suspend fun speed(): Float = withContext(Dispatchers.IO) {
        prefs.getFloat(KEY_SPEED, DEFAULT_PLAYBACK_SPEED)
    }

    override suspend fun save(speed: Float): Unit = withContext(Dispatchers.IO) {
        prefs.edit { putFloat(KEY_SPEED, speed) }
    }

    override suspend fun skipSilence(): Boolean = withContext(Dispatchers.IO) {
        prefs.getBoolean(KEY_SKIP_SILENCE, false)
    }

    override suspend fun saveSkipSilence(on: Boolean): Unit = withContext(Dispatchers.IO) {
        prefs.edit { putBoolean(KEY_SKIP_SILENCE, on) }
    }

    private companion object {
        /** Deliberately not a SourceId, so it cannot collide with the old per-source entries. */
        const val KEY_SPEED = "speed"
        const val KEY_SKIP_SILENCE = "skipSilence"
    }
}
