package com.dewijones92.totum.reminders.kit

import android.content.Context
import androidx.core.content.edit
import com.dewijones92.totum.common.Diag

class JsonPrefs(context: Context, name: String, private val tag: String) {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    fun <T> read(key: String, default: T, decode: (String) -> T): T {
        val text = prefs.getString(key, null) ?: return default
        return runCatching { decode(text) }
            .onFailure { Diag.warn(tag, "dewidebug stored $key unreadable, using defaults", it) }
            .getOrDefault(default)
    }

    fun write(key: String, text: String) = prefs.edit { putString(key, text) }
}
