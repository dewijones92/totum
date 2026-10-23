package com.dewijones92.totum.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TheTailIsScannedForSpeechTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun weights(): SpeechWeights =
        instrumentation.targetContext.resources.openRawResource(R.raw.silero_vad).use(SpeechWeights::read)

    private fun file(asset: String): File =
        File(instrumentation.targetContext.cacheDir, asset).also { out ->
            instrumentation.context.assets.open(asset).use { input -> out.outputStream().use(input::copyTo) }
        }

    @Test
    fun aTailWithNoSpeechHeardSaysNothingAndDoesNotEndTheItem() {
        val scan = scanTailForSpeech(file("speech-then-music.m4a").path, DURATION_MS, weights())

        assertNotNull(scan)
        android.util.Log.i("dewidebug", "speech-then-music scan: $scan")
        assertNull("scan $scan", scan!!.speechEndsMs)
        assertTrue(scan.heardMs > 0)
        assertFalse(endsWithNoSpeechLeft(scan.fromMs + 4_000, scan.fromMs + 5_000, DURATION_MS, scan.speechEndsMs))
    }

    @Test
    fun speechIntoTheTailEndsOnlyAfterTheLastWords() {
        val scan = scanTailForSpeech(file("speech-into-the-tail.m4a").path, DURATION_MS, weights())

        assertNotNull(scan)
        android.util.Log.i("dewidebug", "speech-into-the-tail scan: $scan")
        val lastWords = scan!!.speechEndsMs!!
        assertTrue("scan $scan", lastWords in SPEECH_ENDS_MS - SLACK_MS..SPEECH_ENDS_MS + SLACK_MS)
        assertFalse(endsWithNoSpeechLeft(lastWords - 3_000, lastWords - 2_000, DURATION_MS, lastWords))
        assertTrue(endsWithNoSpeechLeft(lastWords + 2_000, lastWords + 3_000, DURATION_MS, lastWords))
    }

    @Test
    fun anAudioOnlyStreamIsScannedOverHttpLikeAFile() {
        val audio = file("speech-into-the-tail.m4a")
        RangeServer(audio).use { server ->
            val scan = scanTailForSpeech(server.url, DURATION_MS, weights())

            assertNotNull(scan)
            android.util.Log.i("dewidebug", "stream scan: $scan, served ${server.served} of ${audio.length()} bytes")
            val lastWords = scan!!.speechEndsMs!!
            assertTrue("scan $scan", lastWords in SPEECH_ENDS_MS - SLACK_MS..SPEECH_ENDS_MS + SLACK_MS)
        }
    }

    private class RangeServer(private val file: File) : java.io.Closeable {
        private val socket = java.net.ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}/speech-into-the-tail.m4a"

        @Volatile
        var served = 0L

        private val thread = Thread {
            while (!socket.isClosed) {
                runCatching { socket.accept() }.getOrNull()?.use(::answer)
            }
        }.apply {
            isDaemon = true
            start()
        }

        private fun answer(client: java.net.Socket) {
            val head = client.getInputStream().bufferedReader().let { reader ->
                generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }.toList()
            }
            val length = file.length()
            val range = head.firstOrNull { it.startsWith("Range:", ignoreCase = true) }
                ?.substringAfter("bytes=")?.split('-')
            val from = range?.getOrNull(0)?.toLongOrNull() ?: 0L
            val to = range?.getOrNull(1)?.toLongOrNull()?.coerceAtMost(length - 1) ?: (length - 1)
            val count = to - from + 1
            val status = if (range == null) "200 OK" else "206 Partial Content"
            android.util.Log.i("dewidebug", "range server: ${head.firstOrNull()} range=$from-$to of $length")
            val out = client.getOutputStream()
            out.write(
                (
                    "HTTP/1.1 $status\r\nContent-Type: audio/mp4\r\nAccept-Ranges: bytes\r\n" +
                        "Content-Length: $count\r\nContent-Range: bytes $from-$to/$length\r\nConnection: close\r\n\r\n"
                    )
                    .toByteArray(),
            )
            if (head.firstOrNull()?.startsWith("HEAD") == true) return
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(from)
                val buffer = ByteArray(BUFFER_BYTES)
                var left = count
                while (left > 0) {
                    val read = raf.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                    if (read <= 0) break
                    runCatching { out.write(buffer, 0, read) }.onFailure { return }
                    served += read
                    left -= read
                }
            }
        }

        override fun close() {
            socket.close()
            thread.join(JOIN_MS)
        }
    }

    private companion object {
        const val BUFFER_BYTES = 16 * 1024
        const val JOIN_MS = 2_000L
        const val DURATION_MS = 60_000L
        const val SPEECH_ENDS_MS = 50_000L
        const val SLACK_MS = 1_500L
    }
}
