package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.CastScreen
import com.dewijones92.totum.domain.QualityLabel

private const val MAX_PROBES = 6

public class CastRungs(
    private val mediaId: String,
    private val onSent: (QualityLabel) -> Unit,
    private val onFetching: (Int) -> Unit,
) {

    @Volatile public var rungs: List<Pair<CastRung, StreamLayout>> = emptyList()
        private set

    @Volatile private var showable: List<CastRung> = emptyList()

    @Volatile private var fetching = -1

    public fun choose(
        current: String,
        offered: List<CastRung>,
        screen: CastScreen?,
        ceiling: Int? = null,
        probe: (CastRung) -> StreamLayout = { rung -> StreamLayouts.probe { span -> Upstream.bytes(rung.url, span) } },
    ): Boolean {
        val started = System.currentTimeMillis()
        showable = CastLadder.ranked(offered, screen, ceiling)
        val readable = mutableListOf<Pair<CastRung, StreamLayout>>()
        var probes = 0
        for (rung in showable.take(MAX_PROBES)) {
            val topHeight = readable.firstOrNull()?.first?.height
            if (topHeight != null && (rung.height < topHeight || rung.codec != null)) break
            probes++
            probeOne(rung, screen, probe)?.let(readable::add)
        }
        val best = readable.sortedWith(
            compareByDescending<Pair<CastRung, StreamLayout>> { it.first.height }
                .thenBy { screen?.preference(it.first.codec) ?: CastScreen.rank(it.first.codec) },
        ).firstOrNull()
        rungs = listOfNotNull(best)
        val flowing = offered.firstOrNull { it.url == current }?.label ?: "not in the offered list"
        Diag.log(
            "cast",
            "$mediaId: the TV gets ${best?.first?.label ?: "no picture from the offered ones"} only " +
                "[phone's stream $flowing, offered ${offered.size}, showable ${showable.size}, probed $probes in " +
                "${System.currentTimeMillis() - started}ms, ceiling=${ceiling?.let { "${it}p" } ?: "none"}]",
        )
        best?.first?.quality?.let(onSent)
        return best != null
    }

    private fun probeOne(
        rung: CastRung,
        screen: CastScreen?,
        probe: (CastRung) -> StreamLayout,
    ): Pair<CastRung, StreamLayout>? {
        val layout = runCatching { probe(rung) }
            .onFailure { Diag.warn("cast", "$mediaId: could not read ${rung.label} for the TV; left out", it) }
            .getOrNull() ?: return null
        val real = rung.copy(codec = layout.codecs, height = layout.height ?: rung.height)
        if (screen != null && !screen.canDecode(real.codec, real.height)) {
            Diag.log("cast", "$mediaId: ${rung.label} is really ${real.label}, which the TV cannot show; left out")
            return null
        }
        return real to layout
    }

    public fun nextLowerHeight(): Int? {
        val sent = rungs.firstOrNull()?.first?.height ?: return null
        return showable.map { it.height }.filter { it < sent }.maxOrNull()
    }

    public fun describe(): String? = rungs.takeIf { it.isNotEmpty() }?.joinToString(" | ") { it.second.describe() }

    public fun urlOf(index: Int): String? = rungs.getOrNull(index)?.first?.url

    public fun fetched(index: Int) {
        if (index == fetching) return
        val before = rungs.getOrNull(fetching)?.first
        val now = rungs.getOrNull(index)?.first ?: return
        fetching = index
        Diag.log("cast", "$mediaId: the TV is fetching ${now.label}${before?.let { " (was ${it.label})" } ?: ""}")
        onFetching(now.height)
    }
}
