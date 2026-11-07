package com.dewijones92.totum.data.notforme

import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NotForMeTest {

    @get:Rule val folder = TemporaryFolder()

    private var now = 1_000L

    private val store = InMemoryNotForMeStore()

    private fun notForMe(with: NotForMeStore = store) = NotForMe(with) { now }

    private fun item(id: String, title: String = "Title $id", author: String? = "Maker") =
        MediaItem(MediaItemId(id), SourceId("ch"), title, publishedAt = null, duration = null, author = author)

    @Test
    fun `a suppressed item is not for me, by the id it was suppressed with`() {
        val list = notForMe()

        list.suppress(item("abcdefghijk"), "menu")

        assertTrue(list.contains(MediaItemId("abcdefghijk")))
        assertFalse(list.contains(MediaItemId("zzzzzzzzzzz")))
    }

    @Test
    fun `the watch address and the bare id of one video are the same item`() {
        val list = notForMe()

        list.suppress(item("https://www.youtube.com/watch?v=abcdefghijk"), "menu")

        assertTrue(list.contains(MediaItemId("abcdefghijk")))
        assertTrue(list.contains(MediaItemId("https://www.youtube.com/watch?v=abcdefghijk")))
        assertEquals("abcdefghijk", list.items.value.single().id)
    }

    @Test
    fun `a watch address that does not end in an id is kept as it is`() {
        val list = notForMe()

        list.suppress(item("https://www.youtube.com/watch?v=short"), "menu")

        assertEquals("https://www.youtube.com/watch?v=short", list.items.value.single().id)
        assertFalse(list.contains(MediaItemId("short")))
    }

    @Test
    fun `suppressing twice keeps one entry and does not save again`() {
        var saves = 0
        val counting = object : NotForMeStore {
            override fun load() = emptyList<SuppressedItem>()
            override fun save(items: List<SuppressedItem>) {
                saves++
            }
        }
        val list = notForMe(counting)

        list.suppress(item("a"), "menu")
        list.suppress(item("a", title = "Renamed"), "menu")

        assertEquals(1, list.items.value.size)
        assertEquals("Title a", list.items.value.single().title)
        assertEquals(1, saves)
    }

    @Test
    fun `the newest is first and each remembers its title, maker and time`() {
        val list = notForMe()
        list.suppress(item("a", author = "One"), "menu")
        now = 2_000L
        list.suppress(item("b", author = null), "menu")

        assertEquals(listOf("b", "a"), list.items.value.map { it.id })
        assertEquals(SuppressedItem("b", "Title b", null, 2_000L), list.items.value[0])
        assertEquals(SuppressedItem("a", "Title a", "One", 1_000L), list.items.value[1])
    }

    @Test
    fun `allowing an item takes it off the list and a missing one changes nothing`() {
        var saves = 0
        val counting = object : NotForMeStore {
            override fun load() = emptyList<SuppressedItem>()
            override fun save(items: List<SuppressedItem>) {
                saves++
            }
        }
        val list = notForMe(counting)
        list.suppress(item("a"), "menu")
        list.suppress(item("b"), "menu")

        list.allow("zzz", "test")
        assertEquals(2, list.items.value.size)
        assertEquals(2, saves)

        list.allow("a", "test")
        assertEquals(listOf("b"), list.items.value.map { it.id })
        assertFalse(list.contains(MediaItemId("a")))
        assertEquals(3, saves)
    }

    @Test
    fun `a label is matched by its letters and digits whatever the case, punctuation or ampersand`() {
        val list = notForMe()
        list.suppress(item("a", title = "Tom & Jerry: The Movie (2021)"), "ask")

        assertTrue(list.mentions("tom and jerry the movie 2021"))
        assertTrue(list.mentions("TOM AND JERRY - THE MOVIE (2021)"))
        assertFalse(list.mentions("Tom and Jerry"))
        assertFalse(list.mentions(""))
        assertFalse(list.mentions("   !?  "))
    }

    @Test
    fun `an empty title is never matched by an empty label`() {
        val list = notForMe()
        list.suppress(item("a", title = ""), "menu")

        assertFalse(list.mentions(""))
    }

    @Test
    fun `leaving out keeps the order of what remains and returns the same list when nothing is listed`() {
        val list = notForMe()
        val three = listOf(item("a"), item("b"), item("c"))

        assertTrue(list.without(three, "empty") === three)

        list.suppress(item("b"), "menu")

        assertEquals(listOf("a", "c"), list.without(three, "test").map { it.id.value })
        assertEquals(listOf("a", "b", "c"), notForMe(InMemoryNotForMeStore()).without(three, "test").map { it.id.value })
    }

    @Test
    fun `leaving out works on anything that has an id`() {
        val list = notForMe()
        list.suppress(item("b"), "menu")

        val kept = list.without(listOf("a" to 1, "b" to 2), "pairs") { MediaItemId(it.first) }

        assertEquals(listOf("a" to 1), kept)
    }

    @Test
    fun `a backup merge adds only what is missing, counts it and saves it`() {
        val list = notForMe()
        list.suppress(item("a"), "menu")
        val saved = listOf(
            SuppressedItem("a", "Title a", null, 5L),
            SuppressedItem("b", "Title b", null, 6L),
            SuppressedItem("c", "Title c", null, 7L),
        )

        val added = list.mergeMissing(saved)

        assertEquals(2, added)
        assertEquals(setOf("a", "b", "c"), list.items.value.map { it.id }.toSet())
        assertEquals(1_000L, list.items.value.first { it.id == "a" }.atMs)
        assertEquals(3, store.load().size)
        assertEquals(0, list.mergeMissing(saved))
    }

    @Test
    fun `what was suppressed is still there for a new list over the same store`() {
        notForMe().suppress(item("a"), "menu")

        val reopened = notForMe()

        assertTrue(reopened.contains(MediaItemId("a")))
        assertEquals(listOf("Title a"), reopened.titles())
    }

    @Test
    fun `the list encodes and decodes to itself and decoding ignores keys it does not know`() {
        val list = notForMe()
        list.suppress(item("a", author = "One"), "menu")
        list.suppress(item("b", author = null), "menu")

        assertEquals(list.items.value, NotForMe.decode(list.encode()))
        assertEquals(
            listOf(SuppressedItem("x", "X", null, 9L)),
            NotForMe.decode("""[{"id":"x","title":"X","atMs":9,"extra":true}]"""),
        )
    }

    @Test
    fun `the file store round trips and an unreadable file starts afresh instead of failing`() {
        val file = folder.newFile("notforme.json")
        val fileStore = FileNotForMeStore(file)
        fileStore.save(listOf(SuppressedItem("a", "Title a", "One", 4L)))

        assertEquals(listOf(SuppressedItem("a", "Title a", "One", 4L)), FileNotForMeStore(file).load())

        file.writeText("{ this is not json")
        assertEquals(emptyList<SuppressedItem>(), FileNotForMeStore(file).load())
    }

    @Test
    fun `a file that is not there yet is an empty list`() {
        assertEquals(emptyList<SuppressedItem>(), FileNotForMeStore(folder.root.resolve("nothing.json")).load())
    }
}
