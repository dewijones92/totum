package com.dewijones92.totum.cli.serve.speakers

import com.dewijones92.totum.common.Diag
import kotlin.math.roundToInt

internal object HardwareVolume {
    const val MAX_RAW = 127

    fun rawOf(percent: Int): Int = (percent.coerceIn(0, MAX_PERCENT) * MAX_RAW / MAX_PERCENT.toDouble()).roundToInt()

    fun percentOf(raw: Int): Int = (raw.coerceIn(0, MAX_RAW) * MAX_PERCENT / MAX_RAW.toDouble()).roundToInt()

    private const val MAX_PERCENT = 100
}

internal sealed interface TransportVolume {
    data class Level(val raw: Int) : TransportVolume {
        val percent: Int get() = HardwareVolume.percentOf(raw)
    }

    data class Unavailable(val reason: String) : TransportVolume
}

internal interface SpeakerTransport {
    suspend fun volume(): TransportVolume

    suspend fun setVolume(raw: Int): TransportVolume
}

internal class BluezTransport(private val mac: String, private val shell: Shell) : SpeakerTransport {
    private val transport = Regex("""/org/bluez/hci\d+/dev_${mac.replace(':', '_')}/fd\d+""", RegexOption.IGNORE_CASE)

    private suspend fun path(): String? {
        val tree = shell.run(listOf("busctl", "--system", "tree", "org.bluez", "--list")) ?: return null
        return tree.lines().map { it.trim() }.firstOrNull { transport.matches(it) }
    }

    override suspend fun volume(): TransportVolume {
        val found = path() ?: return TransportVolume.Unavailable(NO_TRANSPORT)
        return read(found)
    }

    override suspend fun setVolume(raw: Int): TransportVolume {
        val found = path() ?: return TransportVolume.Unavailable(NO_TRANSPORT)
        val set = shell.run(
            listOf(
                "busctl", "--system", "set-property", "org.bluez", found, INTERFACE, "Volume", "q",
                raw.coerceIn(0, HardwareVolume.MAX_RAW).toString(),
            ),
        ) ?: return TransportVolume.Unavailable("busctl refused to set Volume on $found")
        Diag.log(
            "speakers",
            "$mac: set Volume $raw on $found${set.trim().takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()}"
        )
        return read(found)
    }

    private suspend fun read(found: String): TransportVolume {
        val said = shell.run(listOf("busctl", "--system", "get-property", "org.bluez", found, INTERFACE, "Volume"))
            ?: return TransportVolume.Unavailable("$found has no Volume property (no absolute volume)")
        val raw = READ.find(said)?.groupValues?.get(1)?.toIntOrNull()
        if (raw == null) {
            Diag.warn("speakers", "$mac: could not read a volume out of \"${said.trim().take(SAID)}\"")
            return TransportVolume.Unavailable("Volume came back as \"${said.trim().take(SAID)}\"")
        }
        return TransportVolume.Level(raw)
    }

    private companion object {
        const val INTERFACE = "org.bluez.MediaTransport1"
        const val NO_TRANSPORT = "no A2DP transport (not connected, or not streaming yet)"
        const val SAID = 80
        val READ = Regex("""^\s*q\s+(\d+)""")
    }
}
