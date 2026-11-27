package com.dewijones92.totum.data.discover

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.innertube.music.MusicArtist
import com.dewijones92.totum.innertube.music.MusicResult
import com.dewijones92.totum.innertube.music.MusicSong
import kotlin.random.Random

public data class DiscoverPick(val song: MusicSong, val artist: DiscoverArtist, val hop: Int)

public data class DiscoverStep(
    val picks: List<DiscoverPick>,
    val pagesAsked: Int,
    val dry: Boolean,
    val failure: String? = null,
    val reason: String? = null,
)

@Suppress("TooManyFunctions")
public class DiscoverWalk(
    public val seed: DiscoverArtist,
    private val random: Random = Random.Default,
    private val memory: Int = SESSION_MEMORY,
    offeredSongs: Set<String> = emptySet(),
    playedArtists: List<DiscoverArtist> = emptyList(),
    private val label: String = seed.name,
) {
    private data class Candidate(val artist: DiscoverArtist, val hop: Int, val via: String)

    private val frontier = LinkedHashMap<String, Candidate>()
    private val similarOf = HashMap<String, List<DiscoverArtist>>()
    private val spent = HashMap<String, String>()
    private val picked = ArrayDeque<DiscoverArtist>()
    private val offered = HashSet(offeredSongs)
    private var seeded = false
    private var productiveSteps = 0
    private var recentres = 0
    private var recentredThisStep = false
    private var failureThisStep: String? = null
    private var askedThisStep = 0
    private var passedThroughThisStep = 0

    public val steps: Int get() = productiveSteps

    public val seedSimilarCount: Int get() = similarOf[seed.id]?.size ?: 0

    public val last: DiscoverArtist? get() = picked.lastOrNull()

    init {
        playedArtists.forEach { picked.addLast(it) }
    }

    public suspend fun step(
        pages: Int,
        page: suspend (String) -> MusicResult<MusicArtist>,
        taste: DiscoverTaste,
        songWanted: (MusicSong) -> Boolean = { true },
        onPick: (DiscoverPick) -> Unit = {},
    ): DiscoverStep {
        askedThisStep = 0
        passedThroughThisStep = 0
        failureThisStep = null
        if (!seeded) {
            askedThisStep++
            if (!readSeed(page)) {
                return DiscoverStep(emptyList(), askedThisStep, dry = false, failure = failureThisStep)
            }
        }
        val inputs = StepInputs(pages, page, taste, songWanted, onPick)
        val startLimit = if (productiveSteps == 0) FIRST_HOP_LIMIT else MAX_HOP
        val gathered = gather(inputs, startLimit)
        val picks = gathered.picks
        if (picks.isNotEmpty()) productiveSteps++
        val failure = failureThisStep.takeIf { picks.isEmpty() }
        val dry = picks.isEmpty() && gathered.ranOut && failure == null
        val result = DiscoverStep(picks, askedThisStep, dry, failure, if (dry) dryReason(taste) else null)
        logStep(result, pages, gathered.limit)
        return result
    }

    private class StepInputs(
        val pages: Int,
        val page: suspend (String) -> MusicResult<MusicArtist>,
        val taste: DiscoverTaste,
        val songWanted: (MusicSong) -> Boolean,
        val onPick: (DiscoverPick) -> Unit,
    )

    private class Gathered(val picks: List<DiscoverPick>, val ranOut: Boolean, val limit: Int)

    private suspend fun gather(inputs: StepInputs, startLimit: Int): Gathered {
        val picks = mutableListOf<DiscoverPick>()
        var limit = startLimit
        var fetched = 0
        var ranOut = false
        recentredThisStep = false
        while (fetched < inputs.pages && !ranOut) {
            val choice = chooseOrRecentre(limit, inputs.taste, inputs.page)
            if (passedThroughThisStep > 0) limit = maxOf(limit, MAX_HOP)
            if (choice == null) {
                ranOut = true
            } else {
                fetched++
                askedThisStep++
                visit(choice, inputs.page, inputs.songWanted)?.let { pick ->
                    picks += pick
                    inputs.onPick(pick)
                }
            }
        }
        return Gathered(picks, ranOut, limit)
    }

    private fun logStep(step: DiscoverStep, pages: Int, limit: Int) {
        Diag.log(
            TAG,
            "\"$label\" step: ${step.picks.size} song(s) from ${step.pagesAsked} page(s) asked (at most $pages, " +
                "plus the seed's the first time, plus $passedThroughThisStep heard artist(s) read only to get " +
                "through), hop limit $limit, $productiveSteps productive step(s), ${frontier.size} artist(s) " +
                "known, dry=${step.dry}" + (step.failure?.let { ", failure=$it" } ?: "") +
                (step.reason?.let { ", reason=$it" } ?: ""),
        )
    }

    private fun dryReason(taste: DiscoverTaste): String {
        val near = similarOf[seed.id].orEmpty()
        if (near.isEmpty()) return "no similar artists for ${seed.name}"
        val heard = near.count { heardWhy(it, taste) != null }
        return if (heard == near.size) {
            "all ${near.size} near artists were heard recently, and none of them lists anyone new"
        } else {
            "nothing new left near ${seed.name} that is not already heard or used"
        }
    }

    private suspend fun chooseOrRecentre(
        limit: Int,
        taste: DiscoverTaste,
        page: suspend (String) -> MusicResult<MusicArtist>,
    ): Candidate? {
        choose(limit, taste)?.let { return it }
        if (passThrough(limit, taste, page) > 0) choose(maxOf(limit, MAX_HOP), taste)?.let { return it }
        if (recentredThisStep) return null
        recentredThisStep = true
        return if (recentre()) choose(limit, taste) else null
    }

    private suspend fun passThrough(
        limit: Int,
        taste: DiscoverTaste,
        page: suspend (String) -> MusicResult<MusicArtist>,
    ): Int {
        val room = PASS_THROUGH_PAGES - passedThroughThisStep
        if (room <= 0) return 0
        val heard = frontier.values.filter {
            it.hop <= limit && it.hop < MAX_HOP && !similarOf.containsKey(it.artist.id) &&
                !spent.containsKey(it.artist.id) && heardWhy(it.artist, taste) != null
        }.take(room)
        var read = 0
        heard.forEach { candidate ->
            val artist = candidate.artist
            askedThisStep++
            when (val answer = page(artist.id)) {
                is MusicResult.Failure -> {
                    spent[artist.id] = "its page failed (${answer.detail})"
                    Diag.warn(
                        TAG,
                        "\"$label\": heard artist ${artist.name} (${artist.id}) could not be read: ${answer.detail}",
                    )
                }
                is MusicResult.Success -> {
                    read++
                    passedThroughThisStep++
                    spent[artist.id] = "heard recently; read only to reach further"
                    absorb(artist, candidate.hop, answer.value.similar.map { DiscoverArtist(it.browseId, it.name) })
                    Diag.log(
                        TAG,
                        "\"$label\": ${artist.name} (${artist.id}) was heard (${heardWhy(artist, taste)}); read only " +
                            "to pass through, ${answer.value.similar.size} similar artist(s) now in reach, " +
                            "no song offered",
                    )
                }
            }
        }
        if (heard.isEmpty()) {
            Diag.log(TAG, "\"$label\": nothing in range and no heard artist left to pass through")
        }
        return read
    }

    private suspend fun readSeed(page: suspend (String) -> MusicResult<MusicArtist>): Boolean {
        return when (val result = page(seed.id)) {
            is MusicResult.Failure -> {
                failureThisStep = result.detail
                Diag.warn(TAG, "\"$label\": the seed's page ${seed.id} failed (${result.detail}); will try again")
                false
            }
            is MusicResult.Success -> {
                seeded = true
                absorb(seed, hop = 0, similar = result.value.similar.map { DiscoverArtist(it.browseId, it.name) })
                Diag.log(
                    TAG,
                    "\"$label\": seed ${seed.name} (${seed.id}) lists ${similarOf[seed.id].orEmpty().size} similar " +
                        "artist(s): ${similarOf[seed.id].orEmpty().take(LISTED).joinToString { it.name }}",
                )
                true
            }
        }
    }

    private suspend fun visit(
        choice: Candidate,
        page: suspend (String) -> MusicResult<MusicArtist>,
        songWanted: (MusicSong) -> Boolean,
    ): DiscoverPick? {
        val artist = choice.artist
        val result = when (val answer = page(artist.id)) {
            is MusicResult.Failure -> {
                failureThisStep = answer.detail
                spent[artist.id] = "its page failed (${answer.detail})"
                Diag.warn(
                    TAG,
                    "\"$label\": ${artist.name} (${artist.id}) page failed: ${answer.detail}; skipped for good"
                )
                return null
            }
            is MusicResult.Success -> answer.value
        }
        absorb(artist, choice.hop, result.similar.map { DiscoverArtist(it.browseId, it.name) })
        val usable = result.topSongs.filter { it.videoId !in offered && songWanted(it) }.take(TOP_SONGS)
        val song = usable.randomOrNull(random)
        if (song == null) {
            spent[artist.id] = "no top song left to offer"
            Diag.log(
                TAG,
                "\"$label\": ${artist.name} (${artist.id}) has ${result.topSongs.size} top song(s) but none to offer " +
                    "(already offered or not for me); skipped for good",
            )
            return null
        }
        offered += song.videoId
        picked.addLast(artist)
        while (picked.size > maxOf(memory, 1)) picked.removeFirst()
        Diag.log(
            TAG,
            "\"$label\": picked ${artist.name} (${artist.id}) hop ${choice.hop} via ${choice.via}, " +
                "song \"${song.title}\" [${song.videoId}] from ${usable.size} of its top ${result.topSongs.size}",
        )
        return DiscoverPick(song, artist, choice.hop)
    }

    private fun absorb(artist: DiscoverArtist, hop: Int, similar: List<DiscoverArtist>) {
        similarOf[artist.id] = similar
        val nextHop = hop + 1
        if (nextHop > MAX_HOP) return
        similar.forEach { next ->
            if (next.id == seed.id) return@forEach
            val known = frontier[next.id]
            if (known == null || known.hop > nextHop) frontier[next.id] = Candidate(next, nextHop, artist.name)
        }
    }

    private fun recentre(): Boolean {
        val centre = picked.lastOrNull() ?: return false
        val similar = similarOf[centre.id].orEmpty()
        var added = 0
        similar.forEach { next ->
            if (next.id == seed.id) return@forEach
            val known = frontier[next.id]
            if (known == null || known.hop > 1) {
                frontier[next.id] = Candidate(next, 1, centre.name)
                added++
            }
        }
        recentres++
        Diag.log(
            TAG,
            "\"$label\": nothing left in range, so re-centring on ${centre.name} " +
                "(re-centre #$recentres, ${similar.size} similar, $added brought back in range)",
        )
        return added > 0
    }

    private fun choose(limit: Int, taste: DiscoverTaste): Candidate? {
        val skipped = LinkedHashMap<String, String>()
        val eligible = frontier.values.filter { candidate ->
            if (candidate.hop > limit) return@filter false
            val why = skipReason(candidate.artist, taste)
            if (why != null) skipped[candidate.artist.name] = why
            why == null
        }
        logSkipped(skipped)
        if (eligible.isEmpty()) return null
        val unfollowed = eligible.filter { taste.followedWhy(it.artist) == null }
        val pool = unfollowed.ifEmpty {
            Diag.log(TAG, "\"$label\": only artists you follow are in range, so using one of ${eligible.size}")
            eligible
        }
        val byHop = pool.groupBy { it.hop }
        val hop = if (limit >= MAX_HOP && byHop.containsKey(1) && byHop.containsKey(MAX_HOP)) {
            if (random.nextBoolean()) 1 else MAX_HOP
        } else {
            byHop.keys.min()
        }
        return byHop.getValue(hop).random(random)
    }

    private fun logSkipped(skipped: Map<String, String>) {
        val offered = skipped.values.count { it == ALREADY_OFFERED }
        val named = skipped.filterValues { it != ALREADY_OFFERED }
        if (named.isEmpty() && offered == 0) return
        val shown = named.entries.take(LISTED).joinToString { "${it.key} (${it.value})" }
        val more = (named.size - LISTED).takeIf { it > 0 }?.let { ", and $it more" }.orEmpty()
        val earlier = if (offered > 0) "; $offered more already offered in this session" else ""
        Diag.log(TAG, "\"$label\": skipped ${shown.ifEmpty { "none by name" }}$more$earlier")
    }

    private fun skipReason(artist: DiscoverArtist, taste: DiscoverTaste): String? =
        spent[artist.id] ?: heardWhy(artist, taste)

    private fun heardWhy(artist: DiscoverArtist, taste: DiscoverTaste): String? = when {
        isLast(artist) -> "same artist as the last pick"
        recentlyOffered(artist) -> ALREADY_OFFERED
        else -> taste.recentWhy(artist)
    }

    private fun isLast(artist: DiscoverArtist): Boolean = picked.lastOrNull()?.sameAs(artist) == true

    private fun DiscoverArtist.sameAs(other: DiscoverArtist): Boolean =
        id == other.id || name.artistNameKey() == other.name.artistNameKey()

    private fun recentlyOffered(artist: DiscoverArtist): Boolean =
        memory > 0 && picked.any { it.sameAs(artist) }

    public companion object {
        public const val PAGES_PER_TOP_UP: Int = 2
        public const val TOP_SONGS: Int = 5
        public const val MAX_HOP: Int = 2
        public const val SESSION_MEMORY: Int = 40
        public const val PASS_THROUGH_PAGES: Int = 3
        private const val FIRST_HOP_LIMIT = 1
        private const val LISTED = 8
        private const val ALREADY_OFFERED = "already offered in this session"
        private const val TAG = "discover"
    }
}
