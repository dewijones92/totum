package com.dewijones92.totum.cli.serve.targets

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.targets.TargetState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal class TargetDecks(
    private val dir: File,
    private val scope: CoroutineScope,
    private val sounds: TargetSounds,
    private val sources: List<TargetSource>,
    private val reporter: ProgressReporter = ProgressReporter.NONE,
    private val ownerId: () -> String? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val decks = ConcurrentHashMap<String, TargetDeck>()
    private val lock = Mutex()
    private var known: List<FoundTarget> = emptyList()
    private var lookedAt = 0L

    init {
        scope.launch {
            while (true) {
                delay(REPORT_EVERY_MS)
                decks.values.forEach {
                    runCatching { it.tick() }.onFailure { e ->
                        Diag.warn(
                            "targets",
                            "tick failed",
                            e
                        )
                    }
                }
            }
        }
    }

    suspend fun all(): List<TargetState> = found(fresh = false).map { deckFor(it).state() }

    suspend fun deck(id: String): TargetDeck? {
        val found = found(fresh = false).firstOrNull { it.target.id == id }
        if (found == null) {
            Diag.warn(
                "targets",
                "no target $id among ${known.joinToString { it.target.id }}; not scanning"
            )
        }
        return found?.let(::deckFor)
    }

    private suspend fun found(fresh: Boolean): List<FoundTarget> = lock.withLock {
        if (fresh || clock() - lookedAt > LOOK_EVERY_MS) {
            known = sources.flatMap { source ->
                runCatching { source.find(fresh) }
                    .onFailure { Diag.warn("targets", "a target source failed", it) }
                    .getOrDefault(emptyList())
            }
            lookedAt = clock()
        }
        known
    }

    private fun deckFor(found: FoundTarget): TargetDeck = decks.getOrPut(found.target.id) {
        val kit = found.kit()
        TargetDeck(
            found.target,
            kit.player,
            kit.link,
            sounds,
            File(dir, "target-${found.target.id}.json"),
            lookAhead = { next ->
                scope.launch {
                    Diag.log("targets", "${found.target.id}: looking up ${next.item.id.value} ahead")
                    sounds.urlFor(next, found.target.kind.hasPicture)
                }
            },
            reporter = reporter,
            ownerId = ownerId,
        ).also { deck -> kit.player.onEnded = { failed -> scope.launch { deck.ended(failed) } } }
    }

    private companion object {
        const val LOOK_EVERY_MS = 60_000L
        const val REPORT_EVERY_MS = 30_000L
    }
}
