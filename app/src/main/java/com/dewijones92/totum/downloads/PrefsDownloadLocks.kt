package com.dewijones92.totum.downloads

import android.content.Context
import androidx.core.content.edit
import com.dewijones92.totum.data.download.DownloadLocks
import com.dewijones92.totum.domain.MediaItemId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class PrefsDownloadLocks(context: Context) : DownloadLocks {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(
        prefs.getStringSet(KEY, emptySet()).orEmpty().map(::MediaItemId).toSet(),
    )
    override val locked: StateFlow<Set<MediaItemId>> = state.asStateFlow()

    override fun set(id: MediaItemId, locked: Boolean) {
        state.update { if (locked) it + id else it - id }
        prefs.edit { putStringSet(KEY, state.value.mapTo(mutableSetOf()) { it.value }) }
    }

    private companion object {
        const val PREFS = "totum_download_locks"
        const val KEY = "locked"
    }
}
