package com.dewijones92.totum.pins

import android.content.Context
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.reminders.kit.JsonPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

open class RecentActions(initial: List<Pin> = emptyList(), private val keep: Int = KEEP) {
    private val state = MutableStateFlow(initial.take(keep))
    val recent: StateFlow<List<Pin>> = state.asStateFlow()

    fun record(pin: Pin, from: String) {
        val before = state.value.indexOfFirst { it.key == pin.key }
        state.update { current -> (listOf(pin) + current.filterNot { it.key == pin.key }).take(keep) }
        Diag.log(
            TAG,
            "recorded ${pin.key} \"${pin.title}\" from $from " +
                "(${if (before < 0) "new" else "was #${before + 1}"}); ${state.value.size} recent",
        )
        saved(state.value)
    }

    fun find(key: String): Pin? = canonicalPinKey(key).let { wanted -> state.value.firstOrNull { it.key == wanted } }

    protected open fun saved(recent: List<Pin>) = Unit

    companion object {
        const val KEEP = 8
        const val TAG = "recent"
    }
}

class SharedPrefsRecentActions(context: Context) : RecentActions(load(context)) {
    private val prefs = JsonPrefs(context, PREFS, TAG)

    override fun saved(recent: List<Pin>) = prefs.write(KEY, PinCodec.encode(recent))

    private companion object {
        const val PREFS = "totum_recent_actions"
        const val KEY = "recent"

        fun load(context: Context): List<Pin> =
            JsonPrefs(context, PREFS, TAG).read(KEY, emptyList(), PinCodec::decode)
                .also { Diag.log(TAG, "loaded ${it.size} recent action(s)") }
    }
}
