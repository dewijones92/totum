package com.dewijones92.totum.recorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.edit
import com.dewijones92.totum.common.Diag

internal class SystemTaps(private val context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun permitted(): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun show(): Boolean {
        if (!permitted()) {
            Diag.log(TAG, "dewidebug recorder system taps not available: WRITE_SECURE_SETTINGS was never granted")
            return false
        }
        val resolver = context.contentResolver
        val before = Settings.System.getInt(resolver, SHOW_TOUCHES, 0)
        if (!prefs.contains(BEFORE)) prefs.edit { putInt(BEFORE, before) }
        val set = runCatching { Settings.System.putInt(resolver, SHOW_TOUCHES, 1) }
            .onFailure { Diag.warn(TAG, "dewidebug recorder could not turn the system's Show taps on", it) }
            .getOrDefault(false)
        Diag.log(TAG, "dewidebug recorder system Show taps on=$set (it was $before)")
        return set
    }

    fun leftOn(): Boolean = prefs.contains(BEFORE)

    fun restoreIfLeftOn(source: String) {
        if (!leftOn()) return
        Diag.log(
            TAG,
            "dewidebug recorder ($source): Show taps was left switched on by a recording that never finished " +
                "(it was ${prefs.getInt(BEFORE, 0)} before); putting it back",
        )
        restore()
    }

    fun restore() {
        if (!prefs.contains(BEFORE)) return
        val before = prefs.getInt(BEFORE, 0)
        val restored = runCatching { Settings.System.putInt(context.contentResolver, SHOW_TOUCHES, before) }
            .onFailure { Diag.warn(TAG, "dewidebug recorder could not put the system's Show taps back", it) }
            .getOrDefault(false)
        if (restored) prefs.edit { remove(BEFORE) }
        Diag.log(TAG, "dewidebug recorder system Show taps put back to $before restored=$restored")
    }

    private companion object {
        const val TAG = "recorder"
        const val FILE = "recorder-taps"
        const val BEFORE = "before"
        const val SHOW_TOUCHES = "show_touches"
    }
}
