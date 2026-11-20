package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.CastScreen
import com.dewijones92.totum.domain.VideoCodecFamily

public interface RefusalStore {
    public fun load(): Map<String, Set<VideoCodecFamily>>

    public fun save(refusals: Map<String, Set<VideoCodecFamily>>)
}

public class CodecRefusals(private val store: RefusalStore? = null) {

    private val confirmed = store?.load().orEmpty().mapValues { it.value.toMutableSet() }.toMutableMap()
    private val tentative = mutableMapOf<String, MutableSet<VideoCodecFamily>>()

    init {
        if (confirmed.isNotEmpty()) Diag.log("cast", "dewidebug codec refusals remembered: ${describe(confirmed)}")
    }

    @Synchronized
    public fun refusedFor(model: String): Set<VideoCodecFamily> =
        confirmed[key(model)].orEmpty() + tentative[key(model)].orEmpty()

    @Synchronized
    public fun appliedTo(screen: CastScreen): CastScreen = screen.without(refusedFor(screen.model))

    @Synchronized
    public fun leavesSomethingAfter(screen: CastScreen, family: VideoCodecFamily): Boolean =
        family !in screen.without(refusedFor(screen.model) + family).codecs

    @Synchronized
    public fun tried(model: String, family: VideoCodecFamily) {
        tentative.getOrPut(key(model)) { mutableSetOf() } += family
        Diag.log(
            "cast",
            "dewidebug $model: ${family.name} is set aside for now; it is remembered only if the next codec plays",
        )
    }

    @Synchronized
    public fun worked(model: String, family: VideoCodecFamily) {
        if (tentative[key(model)]?.remove(family) != true) return
        confirmed.getOrPut(key(model)) { mutableSetOf() } += family
        store?.save(confirmed.mapValues { it.value.toSet() })
        Diag.log(
            "cast",
            "dewidebug $model: another codec played after ${family.name} would not start, " +
                "so ${family.name} is remembered as refused by this model",
        )
    }

    @Synchronized
    public fun forgetTentative() {
        tentative.clear()
    }

    private fun key(model: String): String = model.trim().lowercase()

    private fun describe(map: Map<String, Set<VideoCodecFamily>>): String =
        map.entries.joinToString { (model, families) -> "$model=${families.joinToString("+") { it.name }}" }
}
