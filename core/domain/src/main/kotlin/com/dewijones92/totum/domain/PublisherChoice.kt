package com.dewijones92.totum.domain

/**
 * Whether an item has a publisher to show beside the show's name — the ONE place that decides.
 *
 * A sealed decision rather than a nullable string, because the two ways of having no publisher are
 * different facts and a report has to tell them apart: a feed that named none, and a feed that named
 * the show again. Returning null for both is what made the first version of the diagnostics line
 * state the opposite of the truth in the commonest case — it had to *reconstruct* the reason from
 * the channel-level author afterwards, and so reported "the feed named no publisher" about a feed
 * that named one on every single episode.
 *
 * It exists as one function because the rule had grown three spellings within a day (the podcast
 * mapping, the facts seam, and that log line), two of which trimmed and one of which did not.
 */
public sealed interface PublisherChoice {

    /** A real second name: the network behind the show. */
    public data class Named(public val name: String) : PublisherChoice

    /**
     * The feed named the show again, which is what most feeds put in `itunes:author`. Carried
     * rather than discarded so a log can say the name that was dropped and why.
     */
    public data class RepeatsShow(public val name: String) : PublisherChoice

    /** The feed named nobody. */
    public data object NotGiven : PublisherChoice

    /** What to store and show — null in both of the "no second name" cases. */
    public val nameOrNull: String? get() = (this as? Named)?.name
}

/**
 * The publisher for an item whose show is called [show], preferring [episodeAuthor] over
 * [feedAuthor].
 *
 * The episode's own `itunes:author` wins because it is the more specific claim; the channel's is the
 * fallback, and it is the one most feeds actually set. Compared case-insensitively and with both
 * sides trimmed, since feeds are inconsistent about capitals and whitespace.
 */
public fun publisherFor(episodeAuthor: String?, feedAuthor: String?, show: String?): PublisherChoice {
    val given = (episodeAuthor ?: feedAuthor)?.trim()?.takeIf { it.isNotEmpty() }
        ?: return PublisherChoice.NotGiven
    return if (given.equals(show?.trim().orEmpty(), ignoreCase = true)) {
        PublisherChoice.RepeatsShow(given)
    } else {
        PublisherChoice.Named(given)
    }
}
