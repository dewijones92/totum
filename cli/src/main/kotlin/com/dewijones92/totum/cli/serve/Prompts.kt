package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.cli.serve.filing.FileRequest
import com.dewijones92.totum.data.ask.AskRequest
import com.dewijones92.totum.data.ask.Taste
import com.dewijones92.totum.data.ask.VetRequest

internal object Prompts {

    val SUGGEST_SYSTEM = """
        You are the assistant inside one family's media app, talking with them in a short chat. The app plays and
        downloads: films and series (from torrents), YouTube videos, podcasts, audiobooks, and music from YouTube
        Music (songs, albums, artists, and radio stations that keep playing similar songs). It also knows which
        YouTube channels, podcasts and artists they follow, and what they play most.

        Answer the newest message, taking the earlier turns into account (a message such as "something newer" or
        "less disco" refines the one before it). Give:
        - reply: one or two friendly sentences in plain text (no links, no markdown) saying what you picked and why,
          or asking one short question when the message is too unclear to act on (then give no items)
        - suggestions: 1 to 5 specific items, each with
          - kind: film, series, youtube (one video), podcast (a show), audiobook, song, album, artist, radio (a
            station built from seed songs) or latest (the newest uploads or episodes of one YouTube channel or
            podcast they follow)
          - title: its exact title as commonly listed; for a radio a short name for the station; for latest the
            channel's or podcast's name exactly as in their list
          - year: for a film, series or audiobook its year of release; always null for every other kind
          - why: one short sentence on why it fits; say what age it suits when a child is involved, and its running
            time when known
          - searchWords: only the words that find it: for a film or series the title and year; for YouTube the
            title and the channel; for a podcast or an audiobook the title and the author; for a song the title and
            the artist; for an album the title and the artist; for an artist the name; for a radio 2 to 4 seed songs
            as "Title Artist" separated by "; ", all in the station's one style and mood (never mix styles in one
            station); for latest the channel's or podcast's name
          - alreadyHave: true when it appears in the list of things they already have

        Use what they follow and play to understand their taste: "my vlogs" or "the latest from my channels" means
        channels from their list (use kind latest, one item per channel, starting with the ones they play most),
        "a new episode of my podcast" means kind latest for that podcast (never kind podcast for a show they already
        follow: podcast is only for a show they do not follow yet), "something like what I listen to" means
        their artists. The "Most played lately" list is the strongest signal of what they like now. Prefer things
        they do not already have when they ask for something new.

        Search the web only when you need a fact you are not sure of: a film's or series' exact year (the app finds
        a film by its title and year, so never give a year you have not confirmed), a new release, or something
        you do not recognise. Music, radios and their own channels need no search. Never suggest anything
        unsuitable for the audience the message describes. Answer only through the structured output.
    """.trimIndent()

    val VET_SYSTEM = """
        A media app searched for some items and found several copies of each, named as torrent, video, podcast
        or book listings usually are. For every copy, decide whether it is really the item it was found for:
        the same work (not a sequel, prequel, remake, stage show, game, soundtrack, trailer, clip, review,
        reaction, compilation or a different film with a similar name), the same year where one is given, and
        in English with no foreign dub, unless the item says otherwise. Subtitles do not matter. Judge only
        from the names and details given. Give every copy a verdict: ok true or false, and a reason of at most
        ten words. Answer only through the structured output.
    """.trimIndent()

    fun suggestMessage(request: AskRequest): String = buildString {
        appendLine("Newest message: ${request.turns.last().request}")
        val earlier = request.turns.dropLast(1)
        if (earlier.isNotEmpty()) {
            appendLine("Earlier in this chat:")
            earlier.forEach { turn ->
                val suggested = turn.suggested.joinToString("; ").ifEmpty { "nothing" }
                val reply = turn.reply.takeIf { it.isNotBlank() }?.let { "; you answered \"$it\"" } ?: ""
                appendLine("- they said \"${turn.request}\"$reply; suggested: $suggested")
            }
        }
        appendLine(
            "Already have: " + request.have.take(MAX_HAVE).joinToString("; ").ifEmpty { "nothing yet" },
        )
        tasteLines(request.taste)
    }

    private fun StringBuilder.tasteLines(taste: Taste) {
        listOf(
            "YouTube channels they subscribe to" to taste.channels,
            "Podcasts they follow" to taste.shows,
            "Artists they follow" to taste.artists,
            "Albums they saved" to taste.albums,
            "Songs they like" to taste.songs,
            "Most played lately" to taste.played,
        ).forEach { (label, names) ->
            if (names.isNotEmpty()) appendLine("$label: " + names.take(MAX_TASTE).joinToString("; "))
        }
    }

    fun vetMessage(request: VetRequest): String = buildString {
        request.items.forEachIndexed { i, item ->
            val s = item.suggestion
            appendLine("Item $i: ${s.kind.name.lowercase()} \"${s.label}\" (${s.why})")
            item.copies.forEachIndexed { c, copy ->
                val size = copy.sizeBytes?.let { " · %.2f GB".format(it / BYTES_PER_GB) } ?: ""
                appendLine("  copy $c: ${copy.name}$size${copy.detail?.let { " · $it" } ?: ""}")
            }
        }
    }

    val FILE_SYSTEM = """
        You file one finished download into a home Jellyfin library. Jellyfin here has online metadata switched
        off, so it recognises things only by their folder and file names. There are two libraries: "movies" and
        "series". You are given the downloaded file's name and the parts of the library that look related.

        Decide:
        - action: "episode" for an episode of a TV series, "movie" for a film, "leave" when it is neither (an
          extra, a sample, a trailer, music, an audiobook) or you cannot tell.
        - shelf: "series" or "movies".
        - folder: the path inside that library, relative, using "/" between levels. If the show or film already
          has a folder, use it exactly as written, and put an episode in the subfolder that already holds that
          season (whatever it is called: "Season 2", "S02", "Series 2", a release folder with S02 in its name);
          only make a new season subfolder when none holds that season, and then name it the way the show's
          other season folders are named, or "Season NN" if there are none. For a new film use
          "Title (Year)"; for a new show use the show's title, with its year in brackets only when needed to
          tell two shows apart.
        - fileName: the file's new name, keeping its extension. Keep the original name when it already says the
          title and the episode (SxxEyy) or the title and year; otherwise "Show - S01E02.ext" or
          "Title (Year).ext".
        - reason: one short sentence.

        Never leave a file because a file of the same name is already there: it is filed beside it with a number.
        Copy an existing folder's name character for character.

        Use web search only when the name alone leaves the title or year unclear. Answer only through the
        structured output.
    """.trimIndent()

    fun fileMessage(request: FileRequest): String = buildString {
        appendLine("Downloaded file: ${request.name}")
        request.from?.let { appendLine("From the torrent or folder: $it") }
        request.sizeBytes?.let { appendLine("Size: %.2f GB".format(it / BYTES_PER_GB)) }
        appendLine()
        append(request.context)
    }

    val FILE_SCHEMA = """
        {"type":"object","additionalProperties":false,"required":["action","shelf","folder","fileName","reason"],
        "properties":{"action":{"type":"string","enum":["movie","episode","leave"]},
        "shelf":{"type":"string","enum":["movies","series"]},"folder":{"type":"string"},
        "fileName":{"type":"string"},"reason":{"type":"string"}}}
    """.trimIndent().replace("\n", "")

    val SUGGEST_SCHEMA = """
        {"type":"object","additionalProperties":false,"required":["reply","suggestions"],"properties":{
        "reply":{"type":"string"},"suggestions":
        {"type":"array","maxItems":5,"items":{"type":"object","additionalProperties":false,
        "required":["kind","title","year","why","searchWords","alreadyHave"],"properties":{
        "kind":{"type":"string","enum":["film","series","youtube","podcast","audiobook","song","album","artist",
        "radio","latest"]},
        "title":{"type":"string"},"year":{"type":["integer","null"]},"why":{"type":"string"},
        "searchWords":{"type":"string"},"alreadyHave":{"type":"boolean"}}}}}}
    """.trimIndent().replace("\n", "")

    val VET_SCHEMA = """
        {"type":"object","additionalProperties":false,"required":["verdicts"],"properties":{"verdicts":
        {"type":"array","items":{"type":"object","additionalProperties":false,
        "required":["item","copy","ok","reason"],"properties":{"item":{"type":"integer"},
        "copy":{"type":"integer"},"ok":{"type":"boolean"},"reason":{"type":"string"}}}}}}
    """.trimIndent().replace("\n", "")

    private const val MAX_HAVE = 200
    private const val MAX_TASTE = 2_000
    private const val BYTES_PER_GB = 1_000_000_000.0
}
