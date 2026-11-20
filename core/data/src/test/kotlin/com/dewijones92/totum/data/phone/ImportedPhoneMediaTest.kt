package com.dewijones92.totum.data.phone

import com.dewijones92.totum.domain.PhoneMediaIds
import com.dewijones92.totum.domain.PhoneMediaKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ImportedPhoneMediaTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun library(): ImportedPhoneMedia = ImportedPhoneMedia(File(folder.root, "google-photos"))

    private fun ImportedPhoneMedia.import(
        googleId: String,
        kind: PhoneMediaKind,
        name: String,
        createdAtMs: Long,
    ): ImportedMedia {
        val file = File(directory(), name).apply { writeText(name) }
        return ImportedMedia(
            key = ImportedKeys.keyOf(googleId),
            kind = kind.key,
            title = name.substringBeforeLast('.'),
            fileName = file.name,
            mimeType = "image/jpeg",
            createdAtMs = createdAtMs,
            importedAtMs = 5_000,
            sizeBytes = file.length(),
        ).also(::add)
    }

    @Test
    fun `a Google id always gets the same short key, and different ids get different keys`() {
        val key = ImportedKeys.keyOf("AF1QipN-abc")

        assertEquals(key, ImportedKeys.keyOf("AF1QipN-abc"))
        assertNotEquals(key, ImportedKeys.keyOf("AF1QipN-abd"))
        assertTrue(key.startsWith(PhoneMediaIds.GOOGLE_REF_PREFIX))
        assertEquals(PhoneMediaIds.GOOGLE_REF_PREFIX.length + 16, key.length)
    }

    @Test
    fun `an imported file is listed with its path in the key and is found again`() = runTest {
        val library = library()
        val item = library.import("one", PhoneMediaKind.PHOTO, "beach.jpg", 1_000)

        val page = library.page(PhoneMediaQuery(PhoneMediaKind.PHOTO))

        val entry = page.entries.single()
        assertEquals("beach", entry.title)
        assertEquals(item.key, entry.ref)
        assertEquals(File(library.directory(), "beach.jpg").absolutePath, PhoneMediaIds.pathOf(entry.key))
        assertTrue(entry.isGoogle)
        assertEquals(PhoneMediaKind.PHOTO, entry.kind)
        assertNotNull(library.fileOf(item.key))
    }

    @Test
    fun `photos and videos are listed apart, newest first`() = runTest {
        val library = library()
        library.import("a", PhoneMediaKind.PHOTO, "old.jpg", 1_000)
        library.import("b", PhoneMediaKind.PHOTO, "new.jpg", 9_000)
        library.import("c", PhoneMediaKind.VIDEO, "clip.mp4", 5_000)

        val photos = library.page(PhoneMediaQuery(PhoneMediaKind.PHOTO)).entries.map { it.title }
        val videos = library.page(PhoneMediaQuery(PhoneMediaKind.VIDEO)).entries.map { it.title }

        assertEquals(listOf("new", "old"), photos)
        assertEquals(listOf("clip"), videos)
    }

    @Test
    fun `the library survives being opened again`() = runTest {
        val first = library()
        first.import("a", PhoneMediaKind.PHOTO, "kept.jpg", 1_000)

        val second = library()

        assertEquals(listOf("kept"), second.page(PhoneMediaQuery(PhoneMediaKind.PHOTO)).entries.map { it.title })
        assertEquals(1, second.count())
    }

    @Test
    fun `an item whose file has gone is not listed and has no file`() = runTest {
        val library = library()
        val item = library.import("a", PhoneMediaKind.PHOTO, "gone.jpg", 1_000)
        File(library.directory(), "gone.jpg").delete()

        assertTrue(library.page(PhoneMediaQuery(PhoneMediaKind.PHOTO)).entries.isEmpty())
        assertNull(library.fileOf(item.key))
        assertFalse(library.has(item.key))
    }

    @Test
    fun `adding the same Google item again replaces it rather than listing it twice`() = runTest {
        val library = library()
        library.import("a", PhoneMediaKind.PHOTO, "first.jpg", 1_000)

        library.import("a", PhoneMediaKind.PHOTO, "second.jpg", 2_000)

        assertEquals(listOf("second"), library.page(PhoneMediaQuery(PhoneMediaKind.PHOTO)).entries.map { it.title })
    }

    @Test
    fun `removing an item deletes its file and forgets it`() = runTest {
        val library = library()
        val item = library.import("a", PhoneMediaKind.PHOTO, "bye.jpg", 1_000)

        assertTrue(library.remove(item.key))

        assertFalse(File(library.directory(), "bye.jpg").exists())
        assertEquals(0, library.count())
        assertFalse(library.remove(item.key))
    }

    @Test
    fun `the library has one folder, named for where it came from`() = runTest {
        val library = library()
        library.import("a", PhoneMediaKind.PHOTO, "x.jpg", 1_000)

        assertEquals(listOf(PhoneFolder("Google Photos", 1)), library.folders(PhoneMediaKind.PHOTO))
        assertTrue(library.folders(PhoneMediaKind.VIDEO).isEmpty())
    }

    @Test
    fun `a damaged index starts the library empty instead of crashing`() = runTest {
        val dir = File(folder.root, "google-photos").apply { mkdirs() }
        File(dir, "index.json").writeText("{ this is not json")

        val library = ImportedPhoneMedia(dir)

        assertEquals(0, library.count())
    }

    @Test
    fun `search, sort and paging work over the imports as over the phone`() = runTest {
        val library = library()
        (1..5).forEach { library.import("id$it", PhoneMediaKind.PHOTO, "holiday $it.jpg", it * 1_000L) }
        library.import("other", PhoneMediaKind.PHOTO, "receipt.jpg", 10_000)

        val found = library.page(PhoneMediaQuery(PhoneMediaKind.PHOTO, search = "holiday", limit = 2))
        val rest = library.page(PhoneMediaQuery(PhoneMediaKind.PHOTO, search = "holiday", limit = 2, offset = 2))

        assertEquals(listOf("holiday 5", "holiday 4"), found.entries.map { it.title })
        assertEquals(2, found.next)
        assertEquals(listOf("holiday 3", "holiday 2"), rest.entries.map { it.title })
    }
}
