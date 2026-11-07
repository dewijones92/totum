package com.dewijones92.totum.diagnostics

import com.dewijones92.totum.data.morning.InboxEntry
import com.dewijones92.totum.data.morning.MorningState
import com.dewijones92.totum.data.spoiler.ShieldState
import com.dewijones92.totum.data.spoiler.ShieldedChannel
import com.dewijones92.totum.data.spoiler.StoredTitle
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.jobs.LastRun
import org.junit.Assert.assertEquals
import org.junit.Test

class FeatureDiagnosticsTest {

    @Test
    fun `a report can say what the morning job, the shield, the jobs and the targets were doing`() {
        val now = 10_000_000L
        val out = featureDiagnostics(
            morning = MorningState(
                digest = true,
                forYou = false,
                inbox = listOf(InboxEntry("Tennis", "a", "A", false, now - 1)),
                picked = mapOf("X" to 1L),
                lastDigestAt = 5L,
            ),
            shield = ShieldState(
                channels = listOf(ShieldedChannel("UC1", "Tennis TV")),
                safe = mapOf("a" to StoredTitle("t", true, 1), "b" to StoredTitle("u", false, 2)),
                revealed = setOf("c"),
            ),
            signedInToHome = false,
            jobs = mapOf("morning" to LastRun(now - 120_000, false, "❌ No home server", null)),
            targets = listOf(TargetState(Target("kitchen", "Kitchen"), linked = true, problem = "no answer")),
            nowMs = now,
        )

        assertEquals("true", out["morning.digestOn"])
        assertEquals("1", out["morning.inbox"])
        assertEquals("Tennis TV", out["spoiler.channels"])
        assertEquals("2", out["spoiler.safeTitlesKnown"])
        assertEquals("false", out["spoiler.canWriteSafeTitles"])
        assertEquals("FAILED 2 min ago: ❌ No home server", out["job.morning"])
        assertEquals("Kitchen linked=true playing=false queued=0 problem=no answer", out["targets.kitchen"])
    }
}
