package com.dewijones92.totum.video

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.targets.HandedResolve
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem

class ResolveHandOver(
    private val lookups: LookupStore,
    private val now: () -> Long = System::currentTimeMillis,
) {

    fun offer(item: PlayableItem): HandedResolve? {
        val watchUrl = (item.handle as? PlayHandle.Video)?.watchUrl ?: return null
        val stored = lookups.load(watchUrl)
        if (stored == null) {
            Diag.log("handover", "dewidebug ${item.item.id.value}: no resolve is stored on this phone to hand over")
            return null
        }
        val age = now() - stored.savedAtMs
        if (age > MAX_AGE_MS) {
            Diag.log("handover", "dewidebug ${item.item.id.value}: the stored resolve is ${age / MS_PER_MIN}m old, not handed over")
            return null
        }
        Diag.log(
            "handover",
            "dewidebug ${item.item.id.value}: handing over the resolve saved ${age / MS_PER_MIN}m ago " +
                "(${stored.metadata.formats.size} format(s))",
        )
        return HandedResolve(watchUrl.value, FileLookupStore.encode(stored))
    }

    suspend fun accept(handed: List<HandedResolve>, probe: suspend (HttpUrl) -> Int?): Int {
        var kept = 0
        for (one in handed) if (acceptOne(one, probe)) kept++
        return kept
    }

    private suspend fun acceptOne(handed: HandedResolve, probe: suspend (HttpUrl) -> Int?): Boolean {
        val watchUrl = HttpUrl.parse(handed.watchUrl)
        val lookup = FileLookupStore.decode(handed.lookup)
        if (watchUrl == null || lookup == null) {
            Diag.warn("handover", "dewidebug a handed resolve for ${handed.watchUrl.takeLast(ID_CHARS)} was unreadable; resolving it here")
            return false
        }
        val id = watchUrl.value.takeLast(ID_CHARS)
        val own = lookups.load(watchUrl)
        if (own != null && own.savedAtMs >= lookup.savedAtMs) {
            Diag.log("handover", "dewidebug $id: this Pi already holds a resolve as new as the one handed over; keeping its own")
            return false
        }
        val sample = lookup.metadata.formats.firstNotNullOfOrNull { format -> format.url?.let(HttpUrl::parse) }
        if (sample == null) {
            Diag.log("handover", "dewidebug $id: the handed resolve carries no stream address; resolving it here")
            return false
        }
        val status = probe(sample)
        if (status == null || status !in OK_RANGE) {
            Diag.log(
                "handover",
                "dewidebug $id: a handed stream address answered ${status ?: "nothing"} here " +
                    "(the phone may not share this Pi's address); resolving it here instead",
            )
            return false
        }
        lookups.save(watchUrl, lookup)
        Diag.log("handover", "dewidebug $id: the phone's resolve is kept (its stream answered $status here), so the Pi need not resolve it")
        return true
    }

    private companion object {
        const val MAX_AGE_MS = 4 * 60 * 60 * 1000L
        const val MS_PER_MIN = 60_000L
        const val ID_CHARS = 11
        val OK_RANGE = 200..299
    }
}
