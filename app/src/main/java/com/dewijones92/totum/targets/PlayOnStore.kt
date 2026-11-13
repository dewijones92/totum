package com.dewijones92.totum.targets

import android.content.Context
import androidx.core.content.edit

interface PlayOnStore {
    fun load(): String?
    fun save(lastUsed: String?)
}

class InMemoryPlayOn(private var held: String? = null) : PlayOnStore {
    override fun load(): String? = held

    override fun save(lastUsed: String?) {
        held = lastUsed
    }
}

class PrefsPlayOn(context: Context) : PlayOnStore {
    private val prefs = context.getSharedPreferences("totum_play_on", Context.MODE_PRIVATE)

    override fun load(): String? = prefs.getString(KEY, null)

    override fun save(lastUsed: String?) {
        prefs.edit { if (lastUsed == null) remove(KEY) else putString(KEY, lastUsed) }
    }

    private companion object {
        const val KEY = "last_used"
    }
}
