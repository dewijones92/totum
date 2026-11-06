package com.dewijones92.totum.workapps

import android.content.Context
import androidx.core.content.edit

class SnoozedKeys(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private var keys: Set<String> = prefs.getStringSet(KEYS, emptySet()).orEmpty().toSet()

    @Synchronized
    fun add(key: String) = save(keys + key)

    @Synchronized
    fun contains(key: String): Boolean = key in keys

    @Synchronized
    fun keepOnly(live: Set<String>) {
        if (keys.any { it !in live }) save(keys intersect live)
    }

    @Synchronized
    fun remove(handedBack: Set<String>) = save(keys - handedBack)

    private fun save(next: Set<String>) {
        keys = next
        prefs.edit { putStringSet(KEYS, next) }
    }

    private companion object {
        const val FILE = "work_apps_snoozed"
        const val KEYS = "keys"
    }
}
