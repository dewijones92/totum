package com.dewijones92.totum.data.phone

import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.PhoneMediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStorePlanTest {

    @Test
    fun `a plain video query has no selection and is newest first, a hundred at a time`() {
        val plan = PhoneMediaQuery(PhoneMediaKind.VIDEO).toPlan()

        assertNull(plan.selection)
        assertTrue(plan.args.isEmpty())
        assertEquals("datetaken DESC, date_added DESC, _id DESC", plan.orderBy)
        assertEquals(100, plan.limit)
        assertEquals(0, plan.offset)
    }

    @Test
    fun `audio leaves out ringtones, notifications and alarms`() {
        val plan = PhoneMediaQuery(PhoneMediaKind.AUDIO).toPlan()

        assertEquals("is_ringtone = 0 AND is_notification = 0 AND is_alarm = 0", plan.selection)
        assertEquals("date_added DESC, _id DESC", plan.orderBy)
    }

    @Test
    fun `a folder is an exact match on its name`() {
        val plan = PhoneMediaQuery(PhoneMediaKind.PHOTO, folder = "Camera").toPlan()

        assertEquals("bucket_display_name = ?", plan.selection)
        assertEquals(listOf("Camera"), plan.args)
    }

    @Test
    fun `each searched word must match the title or the file name, and a song's artist or album too`() {
        val video = PhoneMediaQuery(PhoneMediaKind.VIDEO, search = "birthday party").toPlan()
        val audio = PhoneMediaQuery(PhoneMediaKind.AUDIO, search = "blue").toPlan()

        assertEquals(
            "(title LIKE ? ESCAPE '\\' OR _display_name LIKE ? ESCAPE '\\') AND " +
                "(title LIKE ? ESCAPE '\\' OR _display_name LIKE ? ESCAPE '\\')",
            video.selection,
        )
        assertEquals(listOf("%birthday%", "%birthday%", "%party%", "%party%"), video.args)
        assertTrue(audio.selection!!.contains("artist LIKE ?"))
        assertTrue(audio.selection!!.contains("album LIKE ?"))
        assertEquals(4, audio.args.size)
    }

    @Test
    fun `wildcards typed into the search are matched literally`() {
        val plan = PhoneMediaQuery(PhoneMediaKind.VIDEO, search = "50%_off").toPlan()

        assertEquals("%50\\%\\_off%", plan.args.first())
    }

    @Test
    fun `blank search adds nothing`() {
        assertNull(PhoneMediaQuery(PhoneMediaKind.VIDEO, search = "   ").toPlan().selection)
    }

    @Test
    fun `every sort has an order that ends on the id so paging never repeats or skips a row`() {
        PhoneMediaKind.entries.forEach { kind ->
            PhoneSort.entries.forEach { sort ->
                val order = PhoneMediaQuery(kind, sort = sort).toPlan().orderBy
                assertTrue("$kind $sort: $order", order.contains("_id"))
            }
        }
    }

    @Test
    fun `a page that comes back full says where the next one starts, a short one says it is the last`() {
        val query = PhoneMediaQuery(PhoneMediaKind.VIDEO, offset = 200, limit = 100)

        assertEquals(300, query.nextOffset(100))
        assertNull(query.nextOffset(37))
    }

    @Test
    fun `a row becomes an entry with its file as the path and a title without the extension`() {
        val entry = PhoneMediaRow(
            id = 42,
            data = "/storage/emulated/0/Movies/Holiday 2025.mp4",
            title = "Holiday 2025.mp4",
            displayName = "Holiday 2025.mp4",
            durationMs = 61_000,
            dateAddedSeconds = 1_700_000_000,
            bucket = "Movies",
        ).toEntry(PhoneMediaKind.VIDEO, "content://media/external/video/media/42")!!

        assertEquals("Holiday 2025", entry.title)
        assertEquals("42", entry.ref)
        assertEquals("/storage/emulated/0/Movies/Holiday 2025.mp4", entry.path)
        assertEquals("Movies", entry.folder)
    }

    @Test
    fun `a row with no file path cannot be played and is dropped`() {
        assertNull(
            PhoneMediaRow(id = 1, data = null, title = "x", displayName = "x").toEntry(PhoneMediaKind.VIDEO, "c")
        )
        assertNull(PhoneMediaRow(id = 1, data = " ", title = "x", displayName = "x").toEntry(PhoneMediaKind.VIDEO, "c"))
    }

    @Test
    fun `an unknown artist tag is no artist, and a tag title wins over the file name`() {
        val entry = PhoneMediaRow(
            id = 7,
            data = "/storage/emulated/0/Music/track01.mp3",
            title = "Blue Monday",
            displayName = "track01.mp3",
            artist = "<unknown>",
            album = "Power, Corruption & Lies",
            isMusic = true,
        ).toEntry(PhoneMediaKind.AUDIO, "content://media/external/audio/media/7")!!

        assertEquals("Blue Monday", entry.title)
        assertNull(entry.artist)
        assertEquals("Power, Corruption & Lies", entry.album)
        assertEquals(MediaContentKind.MUSIC, entry.contentKind)
    }

    @Test
    fun `an audiobook is an audiobook, spoken audio is standard and keeps skip silence and speed`() {
        val book = PhoneMediaRow(1, "/a/b.m4b", "B", "b.m4b", isAudiobook = true).toEntry(PhoneMediaKind.AUDIO, "c")!!
        val memo = PhoneMediaRow(2, "/a/r.m4a", "R", "r.m4a").toEntry(PhoneMediaKind.AUDIO, "c")!!

        assertEquals(MediaContentKind.AUDIOBOOK, book.contentKind)
        assertEquals(MediaContentKind.STANDARD, memo.contentKind)
    }

    @Test
    fun `a zero duration or zero date is no duration and no date`() {
        val entry = PhoneMediaRow(
            id = 3,
            data = "/p.jpg",
            title = "p",
            displayName = "p.jpg",
            durationMs = 0,
            dateTakenMs = 0,
            width = 0,
        ).toEntry(PhoneMediaKind.PHOTO, "c")!!

        assertNull(entry.durationMs)
        assertNull(entry.takenAtMs)
        assertNull(entry.width)
    }

    @Test
    fun `the projection of each kind asks for the id and the file path`() {
        PhoneMediaKind.entries.forEach {
            val columns = MediaStoreColumns.projection(it)
            assertTrue(MediaStoreColumns.ID in columns)
            assertTrue(MediaStoreColumns.DATA in columns)
        }
    }
}
