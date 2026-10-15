package com.dewijones92.totum.playback

import android.content.Context
import androidx.core.content.edit

class ExploreResumeStore(context: Context) {
    private val prefs = context.getSharedPreferences("totum_explore", Context.MODE_PRIVATE)

    fun read(): String? = prefs.getString(KEY, null)

    fun write(itemId: String?) = prefs.edit { if (itemId == null) remove(KEY) else putString(KEY, itemId) }

    private companion object {
        const val KEY = "resume_after"
    }
}
