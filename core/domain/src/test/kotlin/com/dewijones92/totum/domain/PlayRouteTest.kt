package com.dewijones92.totum.domain

import com.dewijones92.totum.common.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one routing decision, for both pillars, in every combination that can occur.
 *
 * The case that matters most is the one that shipped broken: a **video** with a downloaded copy,
 * offline. The queue's video branch never asked the download store, so a Novara episode whose
 * audio had been fetched was refused on a plane with the file on the disk (Dewi, 2026-08-06).
 * Everything here is a table, because the bug was not a hard rule wrongly written — it was a
 * rule that existed for one pillar and not the other, and only a table makes that visible.
 */
class PlayRouteTest {

    private val watchUrl = HttpUrl.of("https://www.youtube.com/watch?v=aaaaaaaaaaa")
    private val enclosure = HttpUrl.of("https://example.test/episode.mp3")
    private val remuxedAudio = HttpUrl.of("https://home.test/ts/stream/audio.m3u8")

    private fun item(mediaUrl: HttpUrl? = null) = MediaItem(
        id = MediaItemId("aaaaaaaaaaa"),
        sourceId = SourceId("src"),
        title = "a thing",
        publishedAt = null,
        duration = null,
        mediaUrl = mediaUrl,
    )

    private fun video() = PlayableItem(item(watchUrl), PlayHandle.Video(watchUrl))
    private fun podcast() = PlayableItem(item(enclosure), PlayHandle.Podcast())
    private fun torrent() = PlayableItem(item(enclosure), PlayHandle.Podcast(audioUrl = remuxedAudio))

    private val audioCopy = LocalCopy("/data/aaaaaaaaaaa.m4a", audioOnly = true)
    private val fullCopy = LocalCopy("/data/aaaaaaaaaaa.mkv", audioOnly = false)

    // ---- The reported bug, at every level it can be asserted ----

    @Test
    fun `offline, a video with a downloaded audio copy plays that copy`() {
        val route = video().routeNow(audioCopy, offline = true, audioPreferred = false)

        assertEquals(PlayRoute.AudioFile(video().playedFromDisk(audioCopy), audioCopy.path), route)
    }

    @Test
    fun `offline, a video downloaded in full plays as video from disk`() {
        val route = video().routeNow(fullCopy, offline = true, audioPreferred = false)

        assertEquals(PlayRoute.VideoFile(video().playedFromDisk(fullCopy), fullCopy.path), route)
    }

    /** The handle is a snapshot from when it was queued; the copy arrives later. */
    @Test
    fun `a video handle is not what decides whether a copy exists`() {
        val queuedBeforeDownload = video()

        val route = queuedBeforeDownload.routeNow(audioCopy, offline = true, audioPreferred = true)

        assertEquals(PlayRoute.AudioFile::class.java, route.javaClass)
    }

    @Test
    fun `offline, a video with no copy is refused for the right reason`() {
        val route = video().routeNow(onDisk = null, offline = true, audioPreferred = false)

        assertEquals(PlayRoute.Refused(Refusal.NotOnThisDevice), route)
    }

    // ---- Watching must not silently lose the picture ----

    /** Dewi's rule, 2026-08-06: an audio-only copy does not stand in while you are watching. */
    @Test
    fun `online and watching, an audio-only copy is kept for later and the video streams`() {
        val route = video().routeNow(audioCopy, offline = false, audioPreferred = false)

        assertEquals(PlayRoute.VideoStream(video(), watchUrl), route)
    }

    @Test
    fun `online and listening, an audio-only copy is used instead of streaming`() {
        val route = video().routeNow(audioCopy, offline = false, audioPreferred = true)

        assertEquals(PlayRoute.AudioFile(video().playedFromDisk(audioCopy), audioCopy.path), route)
    }

    /** A full copy has the picture in it, so there is never a reason to stream instead. */
    @Test
    fun `online and watching, a full copy still beats streaming`() {
        val route = video().routeNow(fullCopy, offline = false, audioPreferred = false)

        assertEquals(PlayRoute.VideoFile(video().playedFromDisk(fullCopy), fullCopy.path), route)
    }

    // ---- Podcasts: unchanged behaviour, now from the same decision ----

    @Test
    fun `a podcast plays its downloaded file whether or not there is a network`() {
        val copy = LocalCopy("/data/episode.mp3")

        assertEquals(
            PlayRoute.AudioFile(podcast().playedFromDisk(copy), copy.path),
            podcast().routeNow(copy, offline = true, audioPreferred = false),
        )
        assertEquals(
            PlayRoute.AudioFile(podcast().playedFromDisk(copy), copy.path),
            podcast().routeNow(copy, offline = false, audioPreferred = false),
        )
    }

    @Test
    fun `online, a podcast with no copy streams its enclosure`() {
        val route = podcast().routeNow(onDisk = null, offline = false, audioPreferred = false)

        assertEquals(PlayRoute.AudioStream(podcast(), viaAudioOnlyUrl = false), route)
    }

    @Test
    fun `offline, a podcast with no copy is refused`() {
        val route = podcast().routeNow(onDisk = null, offline = true, audioPreferred = false)

        assertEquals(PlayRoute.Refused(Refusal.NotOnThisDevice), route)
    }

    /** No file and no URL is a different answer from "no network", and more useful. */
    @Test
    fun `an item with nothing to play says so rather than blaming the network`() {
        val nothing = PlayableItem(item(mediaUrl = null), PlayHandle.Podcast())

        assertEquals(
            PlayRoute.Refused(Refusal.NothingToPlay),
            nothing.routeNow(onDisk = null, offline = true, audioPreferred = false),
        )
    }

    // ---- Listen mode over the wire: the torrent's remuxed audio ----

    @Test
    fun `listening, a torrent takes the audio-only URL rather than the whole file`() {
        val route = torrent().routeNow(onDisk = null, offline = false, audioPreferred = true)

        assertEquals(
            PlayRoute.AudioStream(
                PlayableItem(item(remuxedAudio), PlayHandle.Podcast(audioUrl = remuxedAudio)),
                viaAudioOnlyUrl = true,
            ),
            route,
        )
    }

    @Test
    fun `watching, a torrent streams the file with its picture`() {
        val route = torrent().routeNow(onDisk = null, offline = false, audioPreferred = false)

        assertEquals(PlayRoute.AudioStream(torrent(), viaAudioOnlyUrl = false), route)
    }

    /** A file on the device beats even the cheap audio-only stream. */
    @Test
    fun `listening, a downloaded copy still wins over the audio-only URL`() {
        val copy = LocalCopy("/data/episode.mp3")

        val route = torrent().routeNow(copy, offline = false, audioPreferred = true)

        assertEquals(PlayRoute.AudioFile(torrent().playedFromDisk(copy), copy.path), route)
    }

    // ---- Handles that already point at a file ----

    @Test
    fun `a local-video handle plays from its own path with no store lookup`() {
        val local = PlayableItem(item(), PlayHandle.LocalVideo("/data/film.mkv"))

        val route = local.routeNow(onDisk = null, offline = true, audioPreferred = false)

        assertEquals(PlayRoute.VideoFile(local, "/data/film.mkv"), route)
    }

    @Test
    fun `a handle carrying a path wins over what the store says`() {
        val local = PlayableItem(item(enclosure), PlayHandle.Podcast(localPath = "/data/from-handle.mp3"))

        val route = local.routeNow(LocalCopy("/data/from-store.mp3"), offline = false, audioPreferred = false)

        assertEquals("/data/from-handle.mp3", (route as PlayRoute.AudioFile).path)
    }

    // ---- The swap the Library and the queue must agree on ----

    @Test
    fun `a downloaded record and the queue make the same handle out of one file`() {
        val record = DownloadedMedia(video(), audioCopy.path, audioOnly = true)

        assertEquals(video().playedFromDisk(audioCopy), record.offline)
    }
    // ---- When the stream itself will not play (added 2026-08-14, report 0.1.383) ----

    /**
     * THE case. An audio-only copy does not stand in while you are *watching*, because a working
     * stream is the better answer — but once the stream has failed every retry the comparison is
     * no longer "audio or video", it is "audio or nothing".
     *
     * Report 0.1.383: the WarFronts video was downloaded audio-only (`copy=audio-only`, all 29
     * queue items ready), its stream 403'd from the first byte, and the app skipped past it to the
     * next video three times without ever reaching for the file.
     */
    @Test
    fun `once the stream is refused, an audio-only copy of a video does stand in`() {
        val route = video().routeNow(
            audioCopy,
            offline = false,
            audioPreferred = false,
            streamRefused = true,
        )

        assertEquals(PlayRoute.AudioFile(video().playedFromDisk(audioCopy), audioCopy.path), route)
    }

    /** And with nothing on the disk there is genuinely nothing left — which is when to move on. */
    @Test
    fun `a refused stream with no copy is refused, not retried`() {
        val route = video().routeNow(null, offline = false, audioPreferred = false, streamRefused = true)

        assertEquals(PlayRoute.Refused(Refusal.StreamWillNotPlay), route)
    }

    /**
     * The rule it must not undo: with the stream working, watching still wins. Otherwise every
     * video in a queue that auto-downloads audio would quietly lose its picture.
     */
    @Test
    fun `while the stream is fine, an audio-only copy still does not stand in`() {
        val route = video().routeNow(audioCopy, offline = false, audioPreferred = false)

        assertEquals(PlayRoute.VideoStream(video(), watchUrl), route)
    }

    /** A full copy was always preferred, and a refusal does not change that. */
    @Test
    fun `a refused stream still plays a full copy as video`() {
        val route = video().routeNow(fullCopy, offline = false, audioPreferred = false, streamRefused = true)

        assertEquals(PlayRoute.VideoFile(video().playedFromDisk(fullCopy), fullCopy.path), route)
    }

    /** Both pillars, one seam: a podcast with its enclosure downloaded behaves the same way. */
    @Test
    fun `a refused podcast stream plays its downloaded file`() {
        val route = podcast().routeNow(audioCopy, offline = false, audioPreferred = false, streamRefused = true)

        assertEquals(PlayRoute.AudioFile(podcast().playedFromDisk(audioCopy), audioCopy.path), route)
    }

    /** Nothing to play beats the stream refusal, because it is true whatever the network did. */
    @Test
    fun `an item with no stream and no copy still reports nothing to play`() {
        val nothing = PlayableItem(item(mediaUrl = null), PlayHandle.Podcast())

        val route = nothing.routeNow(null, offline = false, audioPreferred = false, streamRefused = true)

        assertEquals(PlayRoute.Refused(Refusal.NothingToPlay), route)
    }
}
