package com.dewijones92.totum.pins

import android.content.Context
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.reminders.kit.JsonPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PinSlot(val index: Int, val pin: Pin)

interface PinStore {
    val pins: StateFlow<List<Pin>>

    fun pin(pin: Pin)

    fun unpin(key: String): PinSlot?

    fun restore(slot: PinSlot)

    fun find(key: String): Pin? = canonicalPinKey(key).let { wanted -> pins.value.firstOrNull { it.key == wanted } }

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

    override fun unpin(key: String): PinSlot? {
        val wanted = canonicalPinKey(key)
        val index = state.value.indexOfFirst { it.key == wanted }
        if (index < 0) {
            Diag.log("pin", "unpin $key: it was not pinned, so nothing changed")
            return null
        }
        val gone = state.value[index]
        state.update { current -> current.filterNot { it.key == wanted } }
        Diag.log("pin", "unpinned $key from slot ${index + 1}; ${state.value.size} pinned")
        saved(state.value)
        return PinSlot(index, gone)
    }

    override fun restore(slot: PinSlot) {
        state.update { current ->
            current.filterNot { it.key == slot.pin.key }.let { rest ->
                rest.take(slot.index) + slot.pin + rest.drop(slot.index)
            }
        }
        Diag.log("pin", "restored ${slot.pin.key} to slot ${slot.index + 1}; ${state.value.size} pinned")
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
