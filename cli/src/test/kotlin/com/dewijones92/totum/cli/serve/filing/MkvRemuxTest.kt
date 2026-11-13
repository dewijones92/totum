package com.dewijones92.totum.cli.serve.filing

import com.dewijones92.totum.cli.serve.ProcessResult
import com.dewijones92.totum.cli.serve.runProcess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class MkvRemuxTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun id(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun element(idHex: String, payload: ByteArray = ByteArray(0)): ByteArray {
        val size = payload.size
        val sizeBytes = if (size < 0x7F) {
            byteArrayOf((0x80 or size).toByte())
        } else {
            byteArrayOf(0x40.or(size shr 8).toByte(), size.toByte())
        }
        return id(idHex) + sizeBytes + payload
    }

    private fun matroska(vararg children: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply { children.forEach { write(it) } }.toByteArray()
        val header = element("1A45DFA3", element("4282", "matroska".toByteArray()))
        val segment = id("18538067") + byteArrayOf(0x01) + 7.downTo(1).map { (body.size shr (8 * (it - 1))).toByte() }
            .toByteArray() + body
        return header + segment
    }

    private val info = element("1549A966", ByteArray(10))
    private val tracks = element("1654AE6B", ByteArray(20))
    private val cluster = element("1F43B675", ByteArray(300))
    private val seekHead = element("114D9B74", ByteArray(16))
    private val void = element("EC", ByteArray(40))

    private fun file(name: String, bytes: ByteArray) = tmp.newFile(name).apply { writeBytes(bytes) }

    @Test
    fun `tracks before the first cluster is the readable layout`() {
        assertEquals(
            MkvLayout.TRACKS_FIRST,
            MkvLayout.of(file("a.mkv", matroska(seekHead, void, info, tracks, cluster))),
        )
    }

    @Test
    fun `tracks after the first cluster is detected`() {
        assertEquals(
            MkvLayout.TRACKS_AFTER_CLUSTERS,
            MkvLayout.of(file("b.mkv", matroska(seekHead, void, info, cluster, cluster, tracks))),
        )
    }

    @Test
    fun `a segment of unknown size is read to the end of the file`() {
        val plain = matroska(info, cluster, tracks)
        val unknown = plain.copyOf().also { bytes ->
            val at = plain.indexOfFirst { it == 0x18.toByte() } + 4
            bytes[at] = 0x01
            for (i in 1..7) bytes[at + i] = 0xFF.toByte()
        }

        assertEquals(MkvLayout.TRACKS_AFTER_CLUSTERS, MkvLayout.of(file("c.mkv", unknown)))
    }

    @Test
    fun `something that is not matroska, or is cut short, is not mistaken for it`() {
        assertEquals(MkvLayout.NOT_MKV, MkvLayout.of(file("d.mkv", ByteArray(64) { 7 })))
        assertEquals(MkvLayout.NOT_MKV, MkvLayout.of(file("e.mkv", ByteArray(0))))
        assertEquals(MkvLayout.UNREADABLE, MkvLayout.of(file("f.mkv", matroska(info).copyOf(40))))
    }

    @Test
    fun `a file with the late layout is remuxed through a temp file and replaced, and the result reads`() {
        val late = file("film.mkv", matroska(info, cluster, tracks))
        val ran = mutableListOf<List<String>>()
        val remux = MkvRemux(run = { args, _ ->
            ran += args
            File(args.last()).writeBytes(matroska(info, tracks, cluster) + ByteArray(late.length().toInt()))
            ProcessResult(0, "", "", false)
        })

        val outcome = remux.fixIfNeeded(late)

        assertTrue(outcome.toString(), outcome is MkvRemuxOutcome.Fixed)
        assertEquals(MkvLayout.TRACKS_FIRST, MkvLayout.of(late))
        assertTrue("copied, not transcoded: $ran", "copy" in ran.single() && "-c" in ran.single())
        assertEquals("written beside it, then renamed", late.parentFile, File(ran.single().last()).parentFile)
        assertFalse(late.parentFile.listFiles().orEmpty().any { it.name.endsWith(MkvRemux.PART_SUFFIX) })
    }

    @Test
    fun `a file that already reads, or is not an mkv, is left alone without running ffmpeg`() {
        var asked = 0
        val remux = MkvRemux(run = { _, _ ->
            asked++
            ProcessResult(0, "", "", false)
        })

        assertEquals(MkvRemuxOutcome.NotNeeded, remux.fixIfNeeded(file("ok.mkv", matroska(info, tracks, cluster))))
        assertEquals(MkvRemuxOutcome.NotNeeded, remux.fixIfNeeded(file("late.mp4", matroska(info, cluster, tracks))))
        assertEquals(0, asked)
    }

    @Test
    fun `a failed remux keeps the original byte for byte and leaves no temp file`() {
        val bytes = matroska(info, cluster, tracks)
        val late = file("film.mkv", bytes)
        val remux = MkvRemux(run = { args, _ ->
            File(args.last()).writeBytes(ByteArray(3))
            ProcessResult(1, "", "Invalid data found", false)
        })

        val outcome = remux.fixIfNeeded(late)

        assertTrue(outcome.toString(), outcome is MkvRemuxOutcome.Failed)
        assertTrue(bytes.contentEquals(late.readBytes()))
        assertFalse(late.parentFile.listFiles().orEmpty().any { it.name.endsWith(MkvRemux.PART_SUFFIX) })
    }

    @Test
    fun `real ffmpeg reads a file with the late layout and writes one that reads`() {
        val ffmpeg = runCatching { runProcess(listOf("ffmpeg", "-version"), 10_000).exitCode == 0 }.getOrDefault(false)
        assumeTrue("ffmpeg is not installed", ffmpeg)
        val base = File(tmp.root, "base.mkv")
        val made = runProcess(
            listOf(
                "ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-f", "lavfi",
                "-i", "testsrc=duration=2:size=64x48:rate=10", "-c:v", "libx264", "-f", "matroska", base.path,
            ),
            60_000,
        )
        assumeTrue(made.stderr, made.exitCode == 0)
        val late = File(tmp.root, "late.mkv").apply { writeBytes(reordered(base.readBytes())) }
        assertEquals(MkvLayout.TRACKS_AFTER_CLUSTERS, MkvLayout.of(late))

        val outcome = MkvRemux().fixIfNeeded(late)

        assertTrue(outcome.toString(), outcome is MkvRemuxOutcome.Fixed)
        assertEquals(MkvLayout.TRACKS_FIRST, MkvLayout.of(late))
        val probe = runProcess(
            listOf("ffprobe", "-v", "error", "-show_entries", "stream=codec_name", "-of", "csv=p=0", late.path),
            30_000,
        )
        assertEquals(probe.stderr, "h264", probe.stdout.trim())
    }

    private fun reordered(base: ByteArray): ByteArray {
        val children = mutableMapOf<String, ByteArray>()
        var at = 0
        val head = readElement(base, at)
        at += head.second.size
        val segment = readHeader(base, at)
        var inner = at + segment.headerLength
        while (inner < base.size) {
            val child = readElement(base, inner)
            children[child.first] = child.second
            inner += child.second.size
        }
        val infoBytes = children.getValue("1549A966")
        val clusterBytes = children.getValue("1F43B675")
        val tracksBytes = children.getValue("1654AE6B")
        val seekPosition = SEEK_HEAD_BYTES + infoBytes.size + clusterBytes.size
        val seek = id("4DBB") + byteArrayOf(0x8E.toByte()) + id("53AB") + byteArrayOf(0x84.toByte()) + id("1654AE6B") +
            id("53AC") + byteArrayOf(0x84.toByte()) + seekPosition.toBigEndian()
        val seekHeadBytes = id("114D9B74") + byteArrayOf((0x80 or seek.size).toByte()) + seek
        check(seekHeadBytes.size == SEEK_HEAD_BYTES)
        val body = seekHeadBytes + infoBytes + clusterBytes + tracksBytes
        return head.second + id("18538067") + byteArrayOf(0x01) + body.size.toLong().toBigEndian7() + body
    }

    private fun Int.toBigEndian() = ByteArray(4) { (this shr (8 * (3 - it))).toByte() }

    private fun Long.toBigEndian7() = ByteArray(7) { (this shr (8 * (6 - it))).toByte() }

    private class Header(val idHex: String, val headerLength: Int, val size: Long)

    private fun readHeader(bytes: ByteArray, at: Int): Header {
        val first = bytes[at].toInt() and 0xFF
        val idLength = Integer.numberOfLeadingZeros(first) - 24 + 1
        val idHex = (0 until idLength).joinToString("") { "%02X".format(bytes[at + it]) }
        val sizeAt = at + idLength
        val sizeFirst = bytes[sizeAt].toInt() and 0xFF
        val sizeLength = Integer.numberOfLeadingZeros(sizeFirst) - 24 + 1
        var size = (sizeFirst and (0xFF shr sizeLength)).toLong()
        for (i in 1 until sizeLength) size = (size shl 8) or (bytes[sizeAt + i].toLong() and 0xFF)
        return Header(idHex, idLength + sizeLength, size)
    }

    private fun readElement(bytes: ByteArray, at: Int): Pair<String, ByteArray> {
        val header = readHeader(bytes, at)
        val length = header.headerLength + header.size.toInt()
        return header.idHex to bytes.copyOfRange(at, at + length)
    }

    private companion object {
        const val SEEK_HEAD_BYTES = 22
    }
}
