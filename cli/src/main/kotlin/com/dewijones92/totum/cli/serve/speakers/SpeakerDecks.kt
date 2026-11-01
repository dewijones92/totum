package com.dewijones92.totum.cli.serve.speakers

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.speakers.SpeakerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal class SpeakerDecks(
    private val dir: File,
    private val scope: CoroutineScope,
    private val sounds: SpeakerSounds,
    private val shell: Shell = SystemShell(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val discovery = BluezSpeakers(shell)
    private val decks = ConcurrentHashMap<String, SpeakerDeck>()
    private val lock = Mutex()
    private var known: List<FoundSpeaker> = emptyList()
    private var lookedAt = 0L

    suspend fun all(): List<SpeakerState> = found(fresh = false).map { deckFor(it).state() }

    suspend fun deck(id: String): SpeakerDeck? {
        val found = found(fresh = false).firstOrNull { it.speaker.id == id }
            ?: found(fresh = true).firstOrNull { it.speaker.id == id }
        if (found == null) Diag.warn("speakers", "no paired speaker $id")
        return found?.let(::deckFor)
    }

    private suspend fun found(fresh: Boolean): List<FoundSpeaker> = lock.withLock {
        if (fresh || clock() - lookedAt > LOOK_EVERY_MS) {
            known = discovery.find()
            lookedAt = clock()
        }
        known
    }

    private fun deckFor(found: FoundSpeaker): SpeakerDeck = decks.getOrPut(found.speaker.id) {
        val player = MpvPlayer(found.speaker.id, dir)
        SpeakerDeck(
            found.speaker,
            player,
            BluezLink(found.mac, shell),
            sounds,
            File(dir, "speaker-${found.speaker.id}.json"),
            lookAhead = { next ->
                scope.launch {
                    Diag.log("speakers", "${found.speaker.id}: looking up ${next.item.id.value} ahead")
                    sounds.urlFor(next)
                }
            },
        ).also { deck -> player.onEnded = { failed -> scope.launch { deck.ended(failed) } } }
    }

    private companion object {
        const val LOOK_EVERY_MS = 60_000L
    }
}
