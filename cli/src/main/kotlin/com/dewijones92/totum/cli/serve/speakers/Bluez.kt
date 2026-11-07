package com.dewijones92.totum.cli.serve.speakers

import com.dewijones92.totum.cli.serve.targets.FoundTarget
import com.dewijones92.totum.cli.serve.targets.TargetKit
import com.dewijones92.totum.cli.serve.targets.TargetLink
import com.dewijones92.totum.cli.serve.targets.TargetSource
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetKind
import kotlinx.coroutines.delay

internal data class FoundSpeaker(val speaker: Target, val mac: String)

internal class BluezSpeakers(private val shell: Shell) {
    suspend fun find(): List<FoundSpeaker> {
        val listed = shell.run(listOf("bluetoothctl", "devices", "Paired"))
            ?: shell.run(listOf("bluetoothctl", "paired-devices"))
            ?: return emptyList<FoundSpeaker>().also { Diag.warn("speakers", "bluetoothctl gave no paired devices") }
        val found = DEVICE.findAll(listed).toList().mapNotNull { match ->
            val mac = match.groupValues[1]
            val info = shell.run(listOf("bluetoothctl", "info", mac)).orEmpty()
            val label = ALIAS.find(info)?.groupValues?.get(1)?.trim() ?: match.groupValues[2].trim()
            FoundSpeaker(Target(idOf(mac), label, TargetKind.SPEAKER), mac).takeIf { AUDIO_SINK in info }
                .also {
                    if (it == null) {
                        Diag.log(
                            "speakers",
                            "paired $mac \"$label\" is not a speaker (no Audio Sink)"
                        )
                    }
                }
        }
        Diag.log(
            "speakers",
            "found ${found.size} speaker(s): ${found.joinToString { "${it.speaker.label} ${it.mac}" }}"
        )
        return found
    }

    companion object {
        fun idOf(mac: String): String = mac.replace(":", "").lowercase()
        private val DEVICE = Regex("""(?m)^Device ([0-9A-F:]{17}) (.*)$""")
        private val ALIAS = Regex("""(?m)^\s*Alias: (.*)$""")
        private const val AUDIO_SINK = "Audio Sink"
    }
}

internal class BluezLink(
    private val mac: String,
    private val shell: Shell,
    private val pollMs: Long = POLL_MS,
    private val tries: Int = TRIES,
) : TargetLink {
    private val sink = "bluez_sink.${mac.replace(':', '_')}.a2dp_sink"
    private var connectedHere = false

    override suspend fun connect(): String? {
        if (sinkIndex() != null) {
            Diag.log("speakers", "$mac already linked, sink $sink")
            return sink
        }
        Diag.log(
            "speakers",
            "linking $mac: ${shell.run(listOf("bluetoothctl", "connect", mac))?.trim()?.lines()?.lastOrNull()}"
        )
        repeat(tries) { attempt ->
            if (sinkIndex() != null) {
                connectedHere = true
                Diag.log("speakers", "$mac linked after ${attempt + 1} look(s), sink $sink")
                return sink
            }
            delay(pollMs)
        }
        Diag.warn("speakers", "$mac: no sink $sink after ${tries * pollMs / MS_PER_S}s")
        return null
    }

    override suspend fun release() {
        if (!connectedHere) {
            Diag.log("speakers", "$mac was linked before Totum asked, left linked")
            return
        }
        val index = sinkIndex()
        val inputs = shell.run(listOf("pactl", "list", "short", "sink-inputs")).orEmpty().lines()
        val others = index?.let { i -> inputs.count { it.split('\t').getOrNull(1) == i } } ?: 0
        if (others > 0) {
            Diag.log("speakers", "$mac still playing $others other stream(s), left linked")
            return
        }
        connectedHere = false
        Diag.log(
            "speakers",
            "unlinking $mac: ${shell.run(listOf("bluetoothctl", "disconnect", mac))?.trim()?.lines()?.lastOrNull()}"
        )
    }

    private suspend fun sinkIndex(): String? = shell.run(listOf("pactl", "list", "short", "sinks")).orEmpty().lines()
        .map { it.split('\t') }.firstOrNull { it.getOrNull(1) == sink }?.firstOrNull()

    private companion object {
        const val POLL_MS = 1_000L
        const val TRIES = 15
        const val MS_PER_S = 1_000
    }
}

internal class SpeakerSource(private val shell: Shell, private val dir: java.io.File) : TargetSource {
    private val discovery = BluezSpeakers(shell)

    override suspend fun find(fresh: Boolean): List<FoundTarget> = discovery.find().map { found ->
        FoundTarget(found.speaker) { TargetKit(MpvPlayer(found.speaker.id, dir), BluezLink(found.mac, shell)) }
    }
}
