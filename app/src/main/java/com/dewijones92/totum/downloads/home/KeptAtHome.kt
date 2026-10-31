package com.dewijones92.totum.downloads.home

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

interface KeptAtHome {
    val ids: StateFlow<Set<String>>
    fun keep(id: String)
    fun release(id: String)
}

open class InMemoryKeptAtHome : KeptAtHome {
    protected val held = MutableStateFlow<Set<String>>(emptySet())
    override val ids: StateFlow<Set<String>> = held.asStateFlow()
    override fun keep(id: String) = held.update { it + id }
    override fun release(id: String) = held.update { it - id }
}

class PrefsKeptAtHome(context: Context) : InMemoryKeptAtHome() {
    private val prefs = context.getSharedPreferences("totum_kept_at_home", Context.MODE_PRIVATE)

    init {
        held.value = prefs.getStringSet(KEY, emptySet()).orEmpty().toSet()
    }

    override fun keep(id: String) {
        super.keep(id)
        prefs.edit { putStringSet(KEY, held.value) }
    }

    override fun release(id: String) {
        super.release(id)
        prefs.edit { putStringSet(KEY, held.value) }
    }

    private companion object {
        const val KEY = "ids"
    }
}
