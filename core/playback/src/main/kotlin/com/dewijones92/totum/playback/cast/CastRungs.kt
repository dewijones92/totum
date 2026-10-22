package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.CastScreen

private const val MAX_PROBES = 6

internal class CastRungs(
    private val mediaId: String,
    private val onSent: (Int) -> Unit,
    private val onFetching: (Int) -> Unit,
) {

    @Volatile var rungs: List<Pair<CastRung, StreamLayout>> = emptyList()
        private set

    @Volatile private var fetching = -1

    fun choose(
        current: String,
        offered: List<CastRung>,
        screen: CastScreen?,
        probe: (CastRung) -> StreamLayout = { rung -> StreamLayouts.probe { span -> Upstream.bytes(rung.url, span) } },
    ): Boolean {
        val started = System.currentTimeMillis()
        val candidates = offered.distinctBy { it.url }.filter { screen?.canDecode(it.codec, it.height) ?: true }
            .sortedByDescending { it.height }.take(MAX_PROBES)
        val probed = candidates.mapNotNull { rung ->
            runCatching {
                probe(
                    rung
                ).let { layout -> rung.copy(codec = layout.codecs, height = layout.height ?: rung.height) to layout }
            }
                .onFailure { Diag.warn("cast", "$mediaId: could not read ${rung.label} for the TV; left out", it) }
                .getOrNull()
        }
        val chosen = CastLadder.choose(current, probed.map { it.first }, screen)
        val picked = chosen.mapNotNull { rung -> probed.firstOrNull { it.first.url == rung.url } }
        val mime = picked.firstOrNull()?.second?.mimeType
        rungs = picked.filter { it.second.mimeType == mime }.takeIf { it.size >= 2 }.orEmpty()
        val between = rungs.joinToString { it.first.label }.ifEmpty { "nothing (fewer than 2 readable)" }
        val flowing = offered.firstOrNull { it.url == current }?.label ?: "not in the ladder"
        Diag.log(
            "cast",
            "$mediaId: the TV may switch between $between [phone's stream $flowing, offered ${offered.size}, " +
                "probed ${candidates.size} in ${System.currentTimeMillis() - started}ms]"
        )
        rungs.maxOfOrNull { it.second.height ?: 0 }?.let(onSent)
        return rungs.isNotEmpty()
    }

    fun describe(): String? = rungs.takeIf { it.isNotEmpty() }?.joinToString(" | ") { it.second.describe() }

    fun urlOf(index: Int): String? = rungs.getOrNull(index)?.first?.url

    fun fetched(index: Int) {
        if (index == fetching) return
        val before = rungs.getOrNull(fetching)?.first
        val now = rungs.getOrNull(index)?.first ?: return
        fetching = index
        Diag.log("cast", "$mediaId: the TV is fetching ${now.label}${before?.let { " (was ${it.label})" } ?: ""}")
        onFetching(now.height)
    }
}
