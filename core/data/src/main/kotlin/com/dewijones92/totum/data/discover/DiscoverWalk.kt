package com.dewijones92.totum.data.discover

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.innertube.music.MusicArtist
import com.dewijones92.totum.innertube.music.MusicResult
import com.dewijones92.totum.innertube.music.MusicSong
import kotlin.random.Random

public data class DiscoverPick(val song: MusicSong, val artist: DiscoverArtist, val hop: Int)

public data class DiscoverStep(val picks: List<DiscoverPick>, val pagesAsked: Int, val dry: Boolean)

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

    public val steps: Int get() = productiveSteps

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
        val limit = if (productiveSteps == 0) FIRST_HOP_LIMIT else MAX_HOP
        var asked = 0
        if (!seeded) {
            asked++
            if (!readSeed(page)) return DiscoverStep(emptyList(), asked, dry = false)
        }
        val picks = mutableListOf<DiscoverPick>()
        var fetched = 0
        var ranOut = false
        recentredThisStep = false
        while (fetched < pages && !ranOut) {
            val choice = chooseOrRecentre(limit, taste)
            if (choice == null) {
                ranOut = true
            } else {
                fetched++
                asked++
                visit(choice, page, songWanted)?.let { pick ->
                    picks += pick
                    onPick(pick)
                }
            }
        }
        if (picks.isNotEmpty()) productiveSteps++
        val dry = picks.isEmpty() && ranOut
        Diag.log(
            TAG,
            "\"$label\" step: ${picks.size} song(s) from $asked page(s) asked (at most $pages, plus the seed's " +
                "the first time), hop limit $limit, $productiveSteps productive step(s), " +
                "${frontier.size} artist(s) known, dry=$dry",
        )
        return DiscoverStep(picks, asked, dry)
    }

    private fun chooseOrRecentre(limit: Int, taste: DiscoverTaste): Candidate? {
        choose(limit, taste)?.let { return it }
        if (recentredThisStep) return null
        recentredThisStep = true
        return if (recentre()) choose(limit, taste) else null
    }

    private suspend fun readSeed(page: suspend (String) -> MusicResult<MusicArtist>): Boolean {
        return when (val result = page(seed.id)) {
            is MusicResult.Failure -> {
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
        if (skipped.isNotEmpty()) {
            val shown = skipped.entries.take(LISTED).joinToString { "${it.key} (${it.value})" }
            val more = (skipped.size - LISTED).takeIf { it > 0 }?.let { ", and $it more" }.orEmpty()
            Diag.log(TAG, "\"$label\": skipped $shown$more")
        }
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

    private fun skipReason(artist: DiscoverArtist, taste: DiscoverTaste): String? = when {
        spent.containsKey(artist.id) -> spent.getValue(artist.id)
        isLast(artist) -> "same artist as the last pick"
        recentlyOffered(artist) -> "already offered in this session"
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
        private const val FIRST_HOP_LIMIT = 1
        private const val LISTED = 8
        private const val TAG = "discover"
    }
}
