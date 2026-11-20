package com.dewijones92.totum.longones

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.PoliteGate
import com.dewijones92.totum.common.Vitals
import com.dewijones92.totum.domain.LongOnesPick
import com.dewijones92.totum.domain.LongOnesRules
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.pickLongOnes
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlin.time.Duration

data class LongOnesPool(val feed: List<MediaItem>, val uploads: List<MediaItem>, val episodes: List<MediaItem>) {
    val all: List<MediaItem> get() = uploads + feed + episodes

    fun merged(): List<MediaItem> {
        val lengthOf = all.mapNotNull { item -> item.duration?.takeIf { it.isPositive() }?.let { item.id to it } }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.first() }
        return all.distinctBy { it.id }.map { item ->
            if (item.duration == null) lengthOf[item.id]?.let { item.copy(duration = it) } ?: item else item
        }
    }
}

data class LongOnesState(
    val items: List<MediaItem>? = null,
    val lengthsWaiting: Int = 0,
    val waitingForWifi: Boolean = false,
    val builtAtMs: Long? = null,
)

sealed interface LongOnesRun {
    data object AlreadyRunning : LongOnesRun

    data class Done(val chosen: Int, val lookedUp: Int, val elapsedMs: Long) : LongOnesRun
}

class LongOnesSources(
    val pool: suspend () -> LongOnesPool,
    val playStates: suspend () -> Map<MediaItemId, PlayState>,
    val withoutNotForMe: (List<MediaItem>) -> List<MediaItem>,
    val liveNow: (MediaItem) -> Boolean,
    val freeLength: suspend (MediaItem) -> Duration?,
)

data class LongOnesLimits(
    val rules: LongOnesRules = LongOnesRules(),
    val lookupBudget: Int = LOOKUP_BUDGET,
    val inFlight: Int = IN_FLIGHT,
)

class LongOnes(
    private val sources: LongOnesSources,
    private val lengths: VideoLengths,
    private val unmetered: () -> Boolean,
    private val limits: LongOnesLimits = LongOnesLimits(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val running = Mutex()
    private val _state = MutableStateFlow(LongOnesState())

    val state: StateFlow<LongOnesState> = _state.asStateFlow()

    suspend fun refresh(why: String, lookUp: Boolean = true): LongOnesRun {
        if (!running.tryLock()) {
            Diag.log(TAG, "dewidebug refresh ($why): one is already running; not starting a second")
            return LongOnesRun.AlreadyRunning
        }
        try {
            return build(why, lookUp)
        } finally {
            running.unlock()
        }
    }

    private class Eligible(val items: List<MediaItem>, val played: Int, val notForMe: Int, val live: Int)

    private class Round(val eligible: List<MediaItem>, val known: HashMap<MediaItemId, Duration>) {
        var pick = LongOnesPick(emptyList(), emptyList(), 0, 0, 0)
        val tried = mutableSetOf<MediaItemId>()
        val outcomes = Outcomes()
        var wifi = false

        fun waiting(): Int = pick.unknownLength.count { it.id !in tried }
    }

    private suspend fun build(why: String, lookUp: Boolean): LongOnesRun.Done {
        val started = now()
        val gathered = sources.pool()
        val left = eligible(gathered.merged())
        lengths.keep(
            gathered.feed.filter { it.contentKind.keepsItsLength() }
                .mapNotNull { item -> item.duration?.let { item.id.value to it } }.toMap(),
        )
        val round = Round(left.items, HashMap())
        left.items.forEach { item -> lengthOf(item)?.let { round.known[item.id] = it } }
        repick(round)
        publish(round)
        while (lookUp && round.outcomes.asked < limits.lookupBudget && lookUpBatch(why, round)) {
            publish(round)
        }
        publish(round)
        val elapsed = now() - started
        log(why, Facts(gathered, left, round, elapsed))
        return LongOnesRun.Done(round.pick.chosen.size, round.outcomes.asked, elapsed)
    }

    private fun repick(round: Round) {
        round.pick = pickLongOnes(round.eligible, { round.known[it.id] }, limits.rules)
    }

    private suspend fun lookUpBatch(why: String, round: Round): Boolean {
        val room = minOf(limits.inFlight, limits.lookupBudget - round.outcomes.asked)
        val batch = round.pick.unknownLength.filter { it.id !in round.tried }.take(room)
        if (batch.isNotEmpty() && !unmetered()) {
            round.wifi = true
            Diag.log(TAG, "dewidebug refresh ($why): ${round.waiting()} length(s) wait for Wi-Fi")
            return false
        }
        if (batch.isEmpty()) return false
        val answers = withContext(PoliteGate.background()) {
            coroutineScope { batch.map { item -> async { item to lengths.lookUp(item) } }.awaitAll() }
        }
        answers.forEach { (item, looked) ->
            round.tried += item.id
            round.outcomes.note(looked)
            if (looked is VideoLengths.Looked.Known) round.known[item.id] = looked.length
        }
        repick(round)
        return true
    }

    private suspend fun eligible(all: List<MediaItem>): Eligible {
        val played = sources.playStates()
        val unplayed = all.filter { it.id !in played }
        val kept = sources.withoutNotForMe(unplayed)
        val unlive = kept.filterNot(sources.liveNow)
        return Eligible(unlive, all.size - unplayed.size, unplayed.size - kept.size, kept.size - unlive.size)
    }

    private suspend fun lengthOf(item: MediaItem): Duration? =
        item.duration?.takeIf { it.isPositive() } ?: lengths.cached(item.id.value) ?: sources.freeLength(item)

    private fun publish(round: Round) {
        _state.value = LongOnesState(round.pick.chosen, round.waiting(), round.wifi, now())
    }

    private class Outcomes {
        var known = 0
        var live = 0
        var none = 0
        var unknown = 0
        var asked = 0

        fun note(looked: VideoLengths.Looked) {
            when (looked) {
                is VideoLengths.Looked.Known -> if (looked.asked) known++
                is VideoLengths.Looked.NotNow -> if (looked.asked) {
                    when (looked.reason) {
                        VideoLengths.Reason.LIVE_OR_UPCOMING -> live++
                        VideoLengths.Reason.NO_LENGTH -> none++
                        VideoLengths.Reason.UNKNOWN -> unknown++
                    }
                }
            }
            val wasAsked = when (looked) {
                is VideoLengths.Looked.Known -> looked.asked
                is VideoLengths.Looked.NotNow -> looked.asked
            }
            if (wasAsked) asked++
        }
    }

    private class Facts(val gathered: LongOnesPool, val eligible: Eligible, val round: Round, val elapsedMs: Long)

    private fun log(why: String, facts: Facts) {
        val gathered = facts.gathered
        val eligible = facts.eligible
        val round = facts.round
        val outcomes = round.outcomes
        val pick = round.pick
        val line = "refresh ($why): pool=${gathered.all.size} (uploads=${gathered.uploads.size} " +
            "feed=${gathered.feed.size} episodes=${gathered.episodes.size}) eligible=${eligible.items.size} " +
            "(left out: played=${eligible.played} notForMe=${eligible.notForMe} liveNow=${eligible.live}) " +
            "lengthsKnown=${round.known.size} askedYouTube=${outcomes.asked} (known=${outcomes.known} " +
            "liveOrUpcoming=${outcomes.live} none=${outcomes.none} unknown=${outcomes.unknown}) " +
            "chosen=${pick.chosen.size}/${limits.rules.limit} tooShort=${pick.tooShort} " +
            "heldBackByTheCap=${pick.heldBack} lengthsStillUnknown=${round.waiting()} " +
            "waitingForWifi=${round.wifi} in ${facts.elapsedMs}ms"
        Diag.log(TAG, "dewidebug $line")
        Vitals.set("longones.lastRefresh", "${java.time.Instant.ofEpochMilli(now())} $line")
        Vitals.set("longones.chosen", pick.chosen.joinToString(" | ") { "${it.id.value}@${it.duration}" })
    }

    private fun MediaContentKind.keepsItsLength(): Boolean =
        this == MediaContentKind.STANDARD || this == MediaContentKind.WAS_LIVE
}

private const val TAG = "longones"
private const val LOOKUP_BUDGET = 40
private const val IN_FLIGHT = 4
