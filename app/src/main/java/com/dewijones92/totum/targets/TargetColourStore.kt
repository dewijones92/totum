package com.dewijones92.totum.targets

import android.content.Context
import androidx.core.content.edit
import com.dewijones92.totum.common.Diag
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

interface TargetColourStore {
    fun load(): Map<String, Long>
    fun save(colours: Map<String, Long>)
}

class InMemoryTargetColours(private var held: Map<String, Long> = emptyMap()) : TargetColourStore {
    override fun load(): Map<String, Long> = held

    override fun save(colours: Map<String, Long>) {
        held = colours
    }
}

class PrefsTargetColours(context: Context) : TargetColourStore {
    private val prefs = context.getSharedPreferences("totum_target_colours", Context.MODE_PRIVATE)
    private val serializer = MapSerializer(String.serializer(), Long.serializer())

    override fun load(): Map<String, Long> = runCatching {
        prefs.getString(KEY, null)?.let { Json.decodeFromString(serializer, it) }.orEmpty()
    }.onFailure { Diag.warn("targets", "saved colours unreadable, assigning afresh", it) }.getOrDefault(emptyMap())

    override fun save(colours: Map<String, Long>) {
        prefs.edit { putString(KEY, Json.encodeToString(serializer, colours)) }
    }

    private companion object {
        const val KEY = "colours"
    }
}
