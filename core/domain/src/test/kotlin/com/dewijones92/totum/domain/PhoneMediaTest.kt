package com.dewijones92.totum.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PhoneMediaTest {

    private val path = "/storage/emulated/0/Movies/holiday 2025.mp4"

    private fun entry(
        kind: PhoneMediaKind,
        ref: String = "42",
        contentKind: MediaContentKind = MediaContentKind.STANDARD,
    ) = PhoneMediaEntry(
        kind = kind,
        key = PhoneMediaIds.keyOf(ref, path),
        title = "Holiday",
        path = path,
        uri = "content://media/external/video/media/$ref",
        durationMs = 61_000,
        addedAtSeconds = 1_700_000_000,
        artist = "Someone",
        folder = "Movies",
        contentKind = contentKind,
    )

    @Test
    fun `a key carries the media store id and the path and both read back`() {
        val key = PhoneMediaIds.keyOf("42", path)

        assertEquals("42", PhoneMediaIds.refOf(key))
        assertEquals(path, PhoneMediaIds.pathOf(key))
    }

    @Test
    fun `a path with unicode and slashes survives the round trip and is safe in an address`() {
        val odd = "/storage/emulated/0/DCIM/Café ☕/IMG 2025?#%.jpg"
        val key = PhoneMediaIds.keyOf("g-ab12", odd)

        assertEquals(odd, PhoneMediaIds.pathOf(key))
        assertTrue(key.none { it == '/' || it == '?' || it == '#' || it == ' ' })
        assertNotNull(PhoneMediaIds.thumbnail(PhoneMediaKind.PHOTO, key))
    }

    @Test
    fun `an id names its kind and key and reads back`() {
        val key = PhoneMediaIds.keyOf("42", path)
        val id = PhoneMediaIds.id(PhoneMediaKind.VIDEO, key)

        assertTrue(id.value.startsWith("phone:video:42."))
        assertEquals(PhoneMediaKind.VIDEO to key, PhoneMediaIds.parse(id.value))
        assertTrue(id.isOnThisPhone)
    }

    @Test
    fun `an id from anywhere else is not on this phone`() {
        listOf("jellyfin:abc", "torrent:xyz", "phone:", "phone:video:", "phone:movie:1", "https://x/y").forEach {
            assertNull(it, PhoneMediaIds.parse(it))
            assertFalse(it, MediaItemId(it).isOnThisPhone)
        }
    }

    @Test
    fun `a thumbnail address reads back to the file it stands for`() {
        val key = PhoneMediaIds.keyOf("g-abc", path)
        val url = PhoneMediaIds.thumbnail(PhoneMediaKind.PHOTO, key)

        assertEquals(PhoneMediaKind.PHOTO to key, PhoneMediaIds.parseThumbnail(url.value))
        assertNull(PhoneMediaIds.parseThumbnail("https://example.com/photo/1"))
    }

    @Test
    fun `a video on the phone plays from its file and has a picture`() {
        val playable = checkNotNull(entry(PhoneMediaKind.VIDEO).toPlayableOrNull())

        assertEquals(PlayHandle.LocalVideo(path), playable.handle)
        assertEquals(MediaKind.VIDEO, playable.pillar)
        assertEquals(Picture.YES, playable.item.picture)
        assertEquals(Instant.ofEpochSecond(1_700_000_000), playable.item.publishedAt)
        assertEquals("Movies", playable.item.author)
    }

    @Test
    fun `a song on the phone is a file with no picture, by its artist`() {
        val playable = checkNotNull(entry(PhoneMediaKind.AUDIO, "7").toPlayableOrNull())

        assertEquals(PlayHandle.Podcast(localPath = path), playable.handle)
        assertEquals(Picture.NO, playable.item.picture)
        assertEquals("Someone", playable.item.author)
    }

    @Test
    fun `a photo is never playable`() {
        val photo = entry(PhoneMediaKind.PHOTO)

        assertNull(photo.toPlayableOrNull())
        assertNull(
            MediaItem(photo.id, PhoneMediaIds.SOURCE, "p", publishedAt = null, duration = null).toPlayableOrNull()
        )
    }

    @Test
    fun `an item from a menu, with only its id, is still playable from its file`() {
        val listed = checkNotNull(entry(PhoneMediaKind.VIDEO).toPlayableOrNull()).item

        val again = listed.toPlayableOrNull()

        assertEquals(PlayHandle.LocalVideo(path), again?.handle)
        assertEquals(listed, again?.item)
    }

    @Test
    fun `a taken date beats the added date`() {
        val taken = entry(PhoneMediaKind.VIDEO).copy(takenAtMs = 1_600_000_000_000)

        assertEquals(Instant.ofEpochMilli(1_600_000_000_000), taken.takenOrAdded)
    }

    @Test
    fun `a Google Photos import is told by its ref`() {
        assertTrue(entry(PhoneMediaKind.PHOTO, "g-0011").isGoogle)
        assertFalse(entry(PhoneMediaKind.PHOTO, "42").isGoogle)
    }

    @Test
    fun `a file on the phone has nothing to follow`() {
        val item = checkNotNull(entry(PhoneMediaKind.VIDEO).toPlayableOrNull()).item

        assertNotNull(FollowedSources.nothingToFollow(item))
    }

    @Test
    fun `a video on the phone routes as a video file with no network, a song as an audio file`() {
        val video = checkNotNull(entry(PhoneMediaKind.VIDEO).toPlayableOrNull())
        val song = checkNotNull(entry(PhoneMediaKind.AUDIO, "7").toPlayableOrNull())

        val offline = video.routeNow(onDisk = null, offline = true, audioPreferred = false)
        val songRoute = song.routeNow(onDisk = null, offline = true, audioPreferred = false)

        assertTrue(offline is PlayRoute.VideoFile)
        assertEquals(path, (offline as PlayRoute.VideoFile).path)
        assertTrue(songRoute is PlayRoute.AudioFile)
    }

    @Test
    fun `listening to a video on the phone plays its sound from the file`() {
        val video = checkNotNull(entry(PhoneMediaKind.VIDEO).toPlayableOrNull())

        val route = video.routeNow(onDisk = null, offline = false, audioPreferred = true)

        assertTrue(route is PlayRoute.AudioFile)
        assertTrue((route as PlayRoute.AudioFile).audioOnly)
    }
}
