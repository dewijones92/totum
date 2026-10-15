package com.dewijones92.totum.polite

import android.content.Context
import androidx.core.content.edit
import com.dewijones92.totum.innertube.player.VisitorIdStore

class PrefsVisitorIdStore(context: Context) : VisitorIdStore {
    private val prefs = context.getSharedPreferences("totum_visitor", Context.MODE_PRIVATE)

    override fun load(): String? = prefs.getString(KEY, null)

    override fun save(id: String) {
        prefs.edit { putString(KEY, id) }
    }

    private companion object {
        const val KEY = "visitor_id"
    }
}
