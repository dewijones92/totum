package com.dewijones92.totum.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dewijones92.totum.common.Vitals
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Everything [PlaybackAnalytics] measures belongs to ONE item, and a transition wipes it.
 *
 * Instrumented rather than JVM because `LoadEventInfo` needs a `DataSpec`, which needs an
 * `android.net.Uri`, which a plain unit test cannot make. That is the same reason the cause-chain
 * walk in `looksExpired()` has no unit test — noted in `docs/features/streaming-reliability.md`.
 *
 * Report 0.1.383 is what these pin. Thirteen transitions in ninety-six seconds produced
 * `18 load(s) in flight, oldest 84804ms` — six of them the same `startedAt` values across four
 * different videos — and `loaded to: track--1=3657572ms` against a **24-minute** video. A load
 * issued for a source the player has since released never completes, cancels or errors, so its
 * entry stayed for ever; and `loadedTo` only moves forward, so once anything reached 61 minutes
 * the figure was stuck at the session maximum.
 *
 * The second one matters most: it is the number that separates *starved* from *stuck*, and it was
 * answering "plenty buffered" no matter what.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class AnalyticsResetPerItemTest {

    private val analytics = PlaybackAnalytics()

    @Before fun clear() = Vitals.clear()

    @After fun tidy() = Vitals.clear()

    @Test
    fun aLoadLeftInFlightDoesNotFollowTheNextItem() {
        analytics.onLoadStarted(eventTime(), load(taskId = 1), mediaLoadData())

        assertEquals("1", Vitals.snapshot()["playback.loadsOutstanding"])

        analytics.onMediaItemTransition(eventTime(), null, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

        assertEquals("0", Vitals.snapshot()["playback.loadsOutstanding"])
        assertEquals("none", Vitals.snapshot()["playback.loadsInFlight"])
    }

    /** The one that made "is it starved or stuck?" unanswerable. */
    @Test
    fun howFarTheLastItemBufferedIsNotClaimedForTheNextOne() {
        analytics.onLoadStarted(eventTime(), load(taskId = 1), mediaLoadData(endMs = SIXTY_ONE_MINUTES))
        analytics.onLoadCompleted(eventTime(), load(taskId = 1), mediaLoadData(endMs = SIXTY_ONE_MINUTES))

        assertEquals("audio=${SIXTY_ONE_MINUTES}ms", Vitals.snapshot()["playback.loadedTo"])

        analytics.onMediaItemTransition(eventTime(), null, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

        assertEquals("nothing yet", Vitals.snapshot()["playback.loadedTo"])
    }

    /**
     * And a shorter item afterwards can report its own figure. Without the reset it never could:
     * `loadedTo` only ever moves forward, so a 24-minute video behind a 61-minute one was
     * permanently invisible.
     */
    @Test
    fun aShorterItemAfterALongerOneReportsItsOwnBuffer() {
        analytics.onLoadStarted(eventTime(), load(taskId = 1), mediaLoadData(endMs = SIXTY_ONE_MINUTES))
        analytics.onLoadCompleted(eventTime(), load(taskId = 1), mediaLoadData(endMs = SIXTY_ONE_MINUTES))
        analytics.onMediaItemTransition(eventTime(), null, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

        analytics.onLoadStarted(eventTime(), load(taskId = 2), mediaLoadData(endMs = TEN_SECONDS))
        analytics.onLoadCompleted(eventTime(), load(taskId = 2), mediaLoadData(endMs = TEN_SECONDS))

        assertEquals("audio=${TEN_SECONDS}ms", Vitals.snapshot()["playback.loadedTo"])
    }

    /** A failed load is a finished load, and the published count has to say so. */
    @Test
    fun aFailedLoadIsNoLongerCountedAsInFlight() {
        analytics.onLoadStarted(eventTime(), load(taskId = 1), mediaLoadData())
        analytics.onLoadError(
            eventTime(),
            load(taskId = 1),
            mediaLoadData(),
            java.io.IOException("Response code: 403"),
            false,
        )

        assertEquals("0", Vitals.snapshot()["playback.loadsOutstanding"])
    }

    // Positional: EventTime takes no named arguments from Kotlin, and nothing under test reads
    // any of it beyond the realtime clock the in-flight ages are derived from.
    private fun eventTime() = AnalyticsListener.EventTime(
        0,
        Timeline.EMPTY,
        0,
        null,
        0,
        Timeline.EMPTY,
        0,
        null,
        0,
        0,
    )

    private fun load(taskId: Long) = LoadEventInfo(
        taskId,
        DataSpec(Uri.parse("https://example.test/chunk")),
        Uri.parse("https://example.test/chunk"),
        emptyMap(),
        0,
        1,
        1_000,
    )

    private fun mediaLoadData(endMs: Long = C.TIME_UNSET) = MediaLoadData(
        C.DATA_TYPE_MEDIA,
        C.TRACK_TYPE_AUDIO,
        null,
        C.SELECTION_REASON_UNKNOWN,
        null,
        0,
        endMs,
    )

    private companion object {
        /** The figure the real report was stuck at, against a 24-minute video. */
        const val SIXTY_ONE_MINUTES = 3_657_572L
        const val TEN_SECONDS = 10_000L
    }
}
