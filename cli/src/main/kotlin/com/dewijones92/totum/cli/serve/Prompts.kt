package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.data.ask.AskRequest
import com.dewijones92.totum.data.ask.VetRequest

internal object Prompts {

    val SUGGEST_SYSTEM = """
        You help one family choose something to download into their media app. It can download five kinds of
        thing: a film or a series (from torrents), a YouTube video, a podcast, or an audiobook.

        Always search the web before answering, and confirm every item's exact title and year there: never give
        a year you have not seen in a search result, because the app finds a film by its title and year and a
        wrong year finds nothing. Then suggest 3 to 5 specific items that best fit the newest request, taking any
        earlier turns of the conversation into account (a request such
        as "something newer" refines the one before it). For each item give:
        - kind: film, series, youtube, podcast or audiobook
        - title: its exact title as it is commonly listed
        - year: its year of release when it has one, otherwise null
        - why: one short sentence on why it fits; say what age it suits when a child is involved, and its
          running time when known
        - searchWords: only the words that find it: for a film or series the title and year and nothing else
          (torrent names carry little more); for YouTube the title and the channel; for a podcast or an
          audiobook the title and the author
        - alreadyHave: true when it appears in the list of things the family already has

        Prefer items they do not already have. Never suggest anything unsuitable for the audience the request
        describes. Answer only through the structured output.
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
        appendLine("Newest request: ${request.turns.last().request}")
        val earlier = request.turns.dropLast(1)
        if (earlier.isNotEmpty()) {
            appendLine("Earlier in this conversation:")
            earlier.forEach { turn ->
                val suggested = turn.suggested.joinToString("; ").ifEmpty { "nothing" }
                appendLine("- asked \"${turn.request}\"; suggested: $suggested")
            }
        }
        appendLine(
            "Already have: " + request.have.take(MAX_HAVE).joinToString("; ").ifEmpty { "nothing yet" },
        )
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

    val SUGGEST_SCHEMA = """
        {"type":"object","additionalProperties":false,"required":["suggestions"],"properties":{"suggestions":
        {"type":"array","maxItems":5,"items":{"type":"object","additionalProperties":false,
        "required":["kind","title","year","why","searchWords","alreadyHave"],"properties":{
        "kind":{"type":"string","enum":["film","series","youtube","podcast","audiobook"]},
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
    private const val BYTES_PER_GB = 1_000_000_000.0
}
