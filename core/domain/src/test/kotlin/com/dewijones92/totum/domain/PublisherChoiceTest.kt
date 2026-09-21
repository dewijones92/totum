package com.dewijones92.totum.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one rule that decides whether an item has a second name to show.
 *
 * It is a sealed decision rather than a nullable string because the two ways of having no publisher
 * are different facts, and conflating them is what made the first diagnostics line report the
 * opposite of the truth. So each outcome is pinned here, including the one that carries a name it
 * has decided NOT to show.
 */
class PublisherChoiceTest {

    @Test
    fun `a different name is the publisher`() {
        assertEquals(
            PublisherChoice.Named("Goalhanger"),
            publisherFor(episodeAuthor = "Goalhanger", feedAuthor = null, show = "The Rest Is Politics"),
        )
    }

    /** The episode's own claim is the more specific one, so it wins. */
    @Test
    fun `an episode's author beats the channel's`() {
        assertEquals(
            PublisherChoice.Named("A Guest Author"),
            publisherFor(episodeAuthor = "A Guest Author", feedAuthor = "Goalhanger", show = "TRIP"),
        )
    }

    /** Most feeds set itunes:author only on the channel, so the fallback carries most of the value. */
    @Test
    fun `the channel's author is used when the episode names nobody`() {
        assertEquals(
            PublisherChoice.Named("BBC Radio 5 Live"),
            publisherFor(episodeAuthor = null, feedAuthor = "BBC Radio 5 Live", show = "Football Daily"),
        )
    }

    /**
     * What most feeds actually do — and the case the decision has to stay distinguishable in, since
     * a log that cannot tell this from "named nobody" tells you the opposite of what happened.
     */
    @Test
    fun `a name that repeats the show is a repeat, not a silence`() {
        assertEquals(
            PublisherChoice.RepeatsShow("the REST is politics"),
            publisherFor(episodeAuthor = "  the REST is politics  ", feedAuthor = null, show = "The Rest Is Politics"),
        )
    }

    @Test
    fun `a repeat has nothing to show`() {
        assertEquals(null, publisherFor("Football Daily", null, "Football Daily").nameOrNull)
    }

    @Test
    fun `no author at either level is NotGiven`() {
        assertEquals(PublisherChoice.NotGiven, publisherFor(null, null, "Football Daily"))
    }

    /** A feed that writes `<itunes:author></itunes:author>` has named nobody, not an empty person. */
    @Test
    fun `a blank author is NotGiven rather than an empty name`() {
        assertEquals(PublisherChoice.NotGiven, publisherFor("   ", "", "Football Daily"))
    }

    /** The player page has no show name above the line, so anything named there is worth showing. */
    @Test
    fun `with no show to compare against a name still shows`() {
        assertEquals(PublisherChoice.Named("Goalhanger"), publisherFor("Goalhanger", null, null))
    }

    /** Trimmed on the way out, so a stored row never carries the feed's stray whitespace. */
    @Test
    fun `the name is trimmed`() {
        assertEquals("Goalhanger", publisherFor("  Goalhanger\n", null, "TRIP").nameOrNull)
    }
}
