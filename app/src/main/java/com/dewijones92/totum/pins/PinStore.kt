package com.dewijones92.totum.pins

import android.content.Context
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.reminders.kit.JsonPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

interface PinStore {
    val pins: StateFlow<List<Pin>>

    fun pin(pin: Pin)

    fun unpin(key: String)

    fun find(key: String): Pin? = pins.value.firstOrNull { it.key == key }

    fun replaceAll(pins: List<Pin>)
}

open class InMemoryPinStore(initial: List<Pin> = emptyList()) : PinStore {
    private val state = MutableStateFlow(initial)
    override val pins: StateFlow<List<Pin>> = state.asStateFlow()

    override fun pin(pin: Pin) {
        state.update { current -> current.filterNot { it.key == pin.key } + pin }
        Diag.log("pin", "pinned ${pin.key} \"${pin.title}\"; ${state.value.size} pinned")
        saved(state.value)
    }

    override fun unpin(key: String) {
        state.update { current -> current.filterNot { it.key == key } }
        Diag.log("pin", "unpinned $key; ${state.value.size} pinned")
        saved(state.value)
    }

    override fun replaceAll(pins: List<Pin>) {
        state.value = pins.distinctBy { it.key }
        Diag.log("pin", "pins replaced: ${state.value.size}")
        saved(state.value)
    }

    protected open fun saved(pins: List<Pin>) = Unit
}

class SharedPrefsPinStore(context: Context) : InMemoryPinStore(load(context)) {
    private val prefs = JsonPrefs(context, PREFS, "pin")

    override fun saved(pins: List<Pin>) = prefs.write(KEY, PinCodec.encode(pins))

    private companion object {
        const val PREFS = "totum_pins"
        const val KEY = "pins"

        fun load(context: Context): List<Pin> =
            JsonPrefs(context, PREFS, "pin").read(KEY, emptyList(), PinCodec::decode)
                .also { Diag.log("pin", "loaded ${it.size} pin(s)") }
    }
}
