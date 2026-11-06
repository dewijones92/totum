package com.dewijones92.totum.update

import android.content.Context
import androidx.core.content.edit
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.update.AppVersion
import com.dewijones92.totum.data.update.ReleaseLookup
import com.dewijones92.totum.data.update.ReleaseSource
import com.dewijones92.totum.data.update.WhatsNewVerdict
import com.dewijones92.totum.data.update.whatsNewLines
import com.dewijones92.totum.data.update.whatsNewVerdict
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class WhatsNewCard(val version: String, val lines: List<String>)

interface LastSeenVersion {
    var value: String?
}

class PrefsLastSeenVersion(context: Context) : LastSeenVersion {
    private val prefs = context.getSharedPreferences("whats_new", Context.MODE_PRIVATE)

    override var value: String?
        get() = prefs.getString(KEY, null)
        set(version) = prefs.edit { putString(KEY, version) }

    private companion object {
        const val KEY = "last_seen_version"
    }
}

class WhatsNew(
    private val installedName: String,
    private val source: ReleaseSource,
    private val seen: LastSeenVersion,
    private val freshInstall: () -> Boolean,
) {
    private val mutableCard = MutableStateFlow<WhatsNewCard?>(null)
    val card: StateFlow<WhatsNewCard?> = mutableCard.asStateFlow()

    @Volatile private var lastOutcome = "not run yet"

    suspend fun load() {
        val installed = AppVersion.parse(installedName)
        val lastSeen = seen.value
        val fresh = freshInstall()
        val verdict = whatsNewVerdict(installed, lastSeen, fresh)
        Diag.log(TAG, "load: installed=$installedName lastSeen=${lastSeen ?: "none"} freshInstall=$fresh -> $verdict")
        lastOutcome = when (verdict) {
            WhatsNewVerdict.Nothing -> "nothing to show"
            WhatsNewVerdict.RecordOnly -> record(installed, "a fresh install, so no card")
            WhatsNewVerdict.Show -> show(checkNotNull(installed))
        }
        Diag.log(TAG, "load: $lastOutcome")
    }

    fun dismiss() {
        val card = mutableCard.value ?: return
        Diag.log(TAG, "card for ${card.version} dismissed")
        seen.value = card.version
        mutableCard.value = null
    }

    fun diagnostics(): Map<String, String> = mapOf(
        "whatsNew.lastSeen" to (seen.value ?: "none"),
        "whatsNew.outcome" to lastOutcome,
    )

    private fun record(installed: AppVersion?, why: String): String {
        seen.value = installed?.toString()
        return "recorded ${seen.value} as seen: $why"
    }

    private suspend fun show(installed: AppVersion): String {
        val release = when (val lookup = source.byTag("v$installed")) {
            is ReleaseLookup.Found -> lookup.release
            is ReleaseLookup.Unavailable ->
                return "notes for v$installed not fetched (${lookup.detail}); will try next launch"
        }
        val lines = whatsNewLines(release.notes)
        if (lines.isEmpty()) return record(installed, "release v$installed carries no \"What's new\" list")
        mutableCard.value = WhatsNewCard(installed.toString(), lines)
        return "showing the card for v$installed (${lines.size} changes)"
    }

    private companion object {
        const val TAG = "whatsnew"
    }
}
