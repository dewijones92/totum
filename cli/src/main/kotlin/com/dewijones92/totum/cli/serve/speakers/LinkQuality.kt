package com.dewijones92.totum.cli.serve.speakers

import com.dewijones92.totum.common.Diag
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

internal data class LinkSample(val atSeconds: Long, val rssi: Int?, val linkQuality: Int?, val txPower: Int?) {
    companion object {
        fun parse(text: String): LinkSample? {
            val fields = text.trim().split(' ', '\n').mapNotNull { part ->
                part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
            }.toMap()
            val at = fields["at"]?.toLongOrNull() ?: return null
            val rssi = fields["rssi"]?.toIntOrNull()
            return LinkSample(at, rssi, fields["lq"]?.toIntOrNull(), fields["tx"]?.toIntOrNull())
        }
    }
}

internal fun interface LinkProbe {
    fun sample(): LinkSample?
}

internal class HostFileLinkProbe(private val file: File) : LinkProbe {
    override fun sample(): LinkSample? = try {
        if (file.isFile) LinkSample.parse(file.readText()) else null
    } catch (e: IOException) {
        Diag.warn("speakers", "the Bluetooth link sample at ${file.path} could not be read", e)
        null
    }
}

internal class UnderrunCounter(private val file: File) {
    private var offset = 0L

    var total = 0
        private set

    fun reset() {
        offset = 0
        total = 0
    }

    fun newSinceLast(): Int {
        if (!file.isFile) return 0
        return try {
            RandomAccessFile(file, "r").use { reader ->
                if (reader.length() < offset) offset = 0
                reader.seek(offset)
                val bytes = ByteArray((reader.length() - offset).toInt().coerceAtLeast(0))
                reader.readFully(bytes)
                offset += bytes.size
                String(bytes).lines().count { it.contains("underrun", ignoreCase = true) }
            }.also { total += it }
        } catch (e: IOException) {
            Diag.warn("speakers", "mpv's log at ${file.path} could not be read for underruns", e)
            0
        }
    }
}

internal object LinkWords {
    private const val STALE_AFTER_S = 90L

    fun line(sample: LinkSample?, nowSeconds: Long, underruns: Int, underrunsTotal: Int): String {
        val counted = "underruns=$underruns since the last line ($underrunsTotal in this play)"
        if (sample == null) {
            return "no Bluetooth link quality (no host sample: is totum-bt-link.service installed on the Pi, and the " +
                "speaker connected to the Pi's own adapter?), $counted"
        }
        val age = (nowSeconds - sample.atSeconds).coerceAtLeast(0)
        val numbers = "rssi=${sample.rssi ?: "?"} lq=${sample.linkQuality ?: "?"} tx=${sample.txPower ?: "?"}"
        val fresh = if (age > STALE_AFTER_S) "STALE, the host sample is ${age}s old" else "host sample ${age}s old"
        return "$numbers ($fresh; rssi is hcitool's, 0 within the receiver's best range and more negative " +
            "the further out), $counted"
    }
}
