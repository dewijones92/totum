package com.dewijones92.totum.diagnostics

import com.dewijones92.totum.data.morning.MorningState
import com.dewijones92.totum.data.spoiler.ShieldState
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.jobs.LastRun

@Suppress("LongParameterList")
internal fun featureDiagnostics(
    morning: MorningState,
    shield: ShieldState,
    signedInToHome: Boolean,
    jobs: Map<String, LastRun>,
    targets: List<TargetState>,
    nowMs: Long,
): Map<String, String> = buildMap {
    put("morning.digestOn", morning.digest.toString())
    put("morning.forYouOn", morning.forYou.toString())
    put("morning.inbox", morning.inbox.size.toString())
    put("morning.inboxGroups", morning.inbox.map { it.group }.distinct().joinToString().ifEmpty { "none" })
    put("morning.lastDigestAtMs", morning.lastDigestAt.toString())
    put("morning.picksRemembered", morning.picked.size.toString())
    put("spoiler.channels", shield.channels.joinToString { it.name }.ifEmpty { "none" })
    put("spoiler.safeTitlesKnown", shield.safe.size.toString())
    put("spoiler.neutralOnly", shield.safe.values.count { it.spoils }.toString())
    put("spoiler.revealedByHand", shield.revealed.size.toString())
    put("spoiler.canWriteSafeTitles", signedInToHome.toString())
    jobs.toSortedMap().forEach { (name, run) ->
        val ago = (nowMs - run.ranAtMs) / MS_PER_MINUTE
        put("job.$name", "${if (run.succeeded) "ok" else "FAILED"} $ago min ago: ${run.detail.ifEmpty { "-" }}")
    }
    put("targets.count", targets.size.toString())
    targets.forEach { state ->
        put(
            "targets.${state.target.id}",
            "${state.target.label} linked=${state.linked} playing=${state.playing} queued=${state.entries.size} " +
                "problem=${state.problem ?: "none"}",
        )
    }
}

private const val MS_PER_MINUTE = 60_000L
