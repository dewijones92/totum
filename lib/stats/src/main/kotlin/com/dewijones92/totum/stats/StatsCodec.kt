package com.dewijones92.totum.stats

import kotlinx.serialization.json.Json

public object StatsCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        encodeDefaults = false
    }

    public fun line(event: StatEvent): String = json.encodeToString(StatEvent.serializer(), event)

    public fun read(lines: Sequence<String>): Decoded {
        var bad = 0
        val events = lines.filter { it.isNotBlank() }.mapNotNull { line ->
            runCatching { json.decodeFromString(StatEvent.serializer(), line) }.getOrElse {
                bad++
                null
            }
        }.toList()
        return Decoded(events, bad)
    }

    public data class Decoded(val events: List<StatEvent>, val unreadable: Int)
}
