package com.dewijones92.totum.playback

import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.MediaExtractorCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@UnstableApi
@RunWith(AndroidJUnit4::class)
class TracksAtTheEndOfAnMkvTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun id(value: Long): ByteArray {
        val bytes = ByteBuffer.allocate(8).putLong(value).array()
        return bytes.dropWhile { it.toInt() == 0 }.toByteArray()
    }

    private fun size(length: Int): ByteArray =
        ByteBuffer.allocate(8).putLong(length.toLong()).array().also { it[0] = 0x01 }

    private fun element(id: Long, payload: ByteArray) = id(id) + size(payload.size) + payload

    private fun uint(id: Long, value: Long) = element(id, id(value).ifEmpty { byteArrayOf(0) })

    private fun text(id: Long, value: String) = element(id, value.toByteArray())

    private fun float64(id: Long, value: Double) = element(id, ByteBuffer.allocate(8).putDouble(value).array())

    private fun ebmlHeader() = element(
        EBML,
        uint(0x4286, 1) + uint(0x42F7, 1) + uint(0x42F2, 4) + uint(0x42F3, 8) +
            text(0x4282, "matroska") + uint(0x4287, 4) + uint(0x4285, 2),
    )

    private fun tracks() = element(
        TRACKS,
        element(
            0xAE,
            uint(0xD7, 1) + uint(0x73C5, 1) + uint(0x83, 2) + text(0x86, "A_PCM/INT/LIT") +
                element(0xE1, float64(0xB5, 48_000.0) + uint(0x9F, 1) + uint(0x6264, 16)),
        ),
    )

    private fun info() = element(INFO, uint(0x2AD7B1, 1_000_000) + float64(0x4489, 1_000.0))

    private fun cluster() = element(
        CLUSTER,
        uint(0xE7, 0) + element(0xA3, byteArrayOf(0x81.toByte(), 0, 0, 0x80.toByte()) + ByteArray(960)),
    )

    private fun seekHeadTo(position: Int) = element(
        SEEK_HEAD,
        element(0x4DBB, element(0x53AB, id(TRACKS)) + element(0x53AC, ByteBuffer.allocate(4).putInt(position).array())),
    )

    private fun mkv(tracksFirst: Boolean): File {
        val head = ByteArrayOutputStream()
        val body = ByteArrayOutputStream()
        val seekHeadLength = seekHeadTo(0).size
        if (tracksFirst) {
            body.write(seekHeadTo(seekHeadLength + info().size))
            body.write(info())
            body.write(tracks())
            body.write(cluster())
        } else {
            body.write(seekHeadTo(seekHeadLength + info().size + cluster().size))
            body.write(info())
            body.write(cluster())
            body.write(tracks())
        }
        head.write(ebmlHeader())
        head.write(element(SEGMENT, body.toByteArray()))
        return File(context.cacheDir, if (tracksFirst) "tracks-first.mkv" else "tracks-last.mkv").also {
            it.writeBytes(head.toByteArray())
        }
    }

    private fun tracksSeenBy(file: File): String {
        val pool = Executors.newSingleThreadExecutor()
        return try {
            pool.submit(
                Callable {
                    val extractor = MediaExtractorCompat(
                        totumExtractors(),
                        DefaultDataSource.Factory(context),
                    )
                    try {
                        extractor.setDataSource(Uri.fromFile(file), 0)
                        val mimes = (0 until extractor.trackCount).map {
                            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                        }
                        TrackSummary.of(mimes, seekable = null)
                    } catch (e: java.io.IOException) {
                        "failed: ${e.javaClass.simpleName}: ${e.message}"
                    } finally {
                        extractor.release()
                    }
                },
            ).get(TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            "hung for ${TIMEOUT_S}s with no answer: ${e.message}"
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun theControlWithItsTrackListUpFrontHasOneTrack() {
        assertEquals("1 track(s): audio/raw; seekability unknown", tracksSeenBy(mkv(tracksFirst = true)))
    }

    @Test
    fun aTrackListAfterTheFirstClusterIsWhatMedia3Reports() {
        val seen = tracksSeenBy(mkv(tracksFirst = false))

        android.util.Log.i("dewidebug", "Media3 on an MKV whose Tracks come after its Cluster: $seen")
        assertEquals(EXPECTED_WHEN_THE_TRACK_LIST_IS_AT_THE_END, seen)
    }

    private companion object {
        const val EBML = 0x1A45DFA3L
        const val SEGMENT = 0x18538067L
        const val SEEK_HEAD = 0x114D9B74L
        const val INFO = 0x1549A966L
        const val CLUSTER = 0x1F43B675L
        const val TRACKS = 0x1654AE6BL
        const val TIMEOUT_S = 20L
        const val EXPECTED_WHEN_THE_TRACK_LIST_IS_AT_THE_END = "to be filled in from the first run"
    }
}
