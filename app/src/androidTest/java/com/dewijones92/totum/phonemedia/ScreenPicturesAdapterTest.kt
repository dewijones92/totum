package com.dewijones92.totum.phonemedia

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.domain.PhoneMediaEntry
import com.dewijones92.totum.domain.PhoneMediaIds
import com.dewijones92.totum.domain.PhoneMediaKind
import com.dewijones92.totum.support.PhoneFixture
import com.dewijones92.totum.support.PhoneMediaFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ScreenPicturesAdapterTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val pictures = AndroidScreenPictures(context)

    @Before
    fun fixtures() {
        PhoneMediaFixtures.grantMediaPermissions()
    }

    private fun entryOf(fixture: PhoneFixture) = PhoneMediaEntry(
        kind = PhoneMediaKind.PHOTO,
        key = PhoneMediaIds.keyOf("screen-test", fixture.path),
        title = fixture.name,
        path = fixture.path,
        uri = fixture.uri.toString(),
    )

    private fun sizeOf(file: File): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return bounds.outWidth to bounds.outHeight
    }

    @Test
    fun aBigPhotoIsBroughtDownToScreenSizeAsAJpegAndKeptForTheNextTime() {
        val big = PhoneMediaFixtures.photo(context, "totum-phone-big.jpg", Color.MAGENTA, width = 3000, height = 2000)

        val prepared = pictures.forScreen(entryOf(big))

        assertNotNull(prepared)
        assertEquals("jpg", prepared!!.extension)
        assertEquals(1920 to 1280, sizeOf(prepared))
        val written = prepared.lastModified()
        val again = pictures.forScreen(entryOf(big))
        assertEquals(prepared.path, again?.path)
        assertEquals(written, again?.lastModified())
    }

    @Test
    fun aPhotoAlreadySmallIsLeftAtItsOwnSize() {
        val small = PhoneMediaFixtures.photo(context, "totum-phone-small.jpg", Color.CYAN)

        val prepared = pictures.forScreen(entryOf(small))

        assertEquals(640 to 360, prepared?.let(::sizeOf))
    }

    @Test
    fun aPhotoThatCannotBeReadIsNothingToShowRatherThanACrash() {
        val gone = PhoneMediaEntry(
            kind = PhoneMediaKind.PHOTO,
            key = PhoneMediaIds.keyOf("screen-test", "/storage/emulated/0/Pictures/nope.jpg"),
            title = "nope",
            path = "/storage/emulated/0/Pictures/nope.jpg",
            uri = "content://media/external/images/media/999999999",
        )

        assertNull(pictures.forScreen(gone))
        assertTrue(File(context.cacheDir, "screen-pictures").listFiles().orEmpty().none { it.length() == 0L })
    }
}
