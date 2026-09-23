package com.dewijones92.totum

import android.app.Activity
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.dewijones92.totum.common.Breadcrumbs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A share plays when it is shared, and never again when Android hands the same intent back.
 *
 * Drives `MainActivity` itself, because the call site is where this broke: 0.1.346 and 0.1.514 both
 * replayed an old share over whatever was playing, with a unit-tested rule underneath that was
 * given the wrong input. Asserted from the activity's own `share` lines.
 *
 * The links have no video id, so each is turned away before any resolve: nothing starts the engine
 * and nothing reaches the device's real queue, whichever way the test goes.
 */
class ReplayedShareIsIgnoredTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before
    fun clearTrail() = Breadcrumbs.clear()

    @After
    fun finishEverything() = instrumentation.runOnMainSync {
        Stage.entries.forEach { stage ->
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).forEach(Activity::finish)
        }
    }

    @Test
    fun aShareReopenedFromRecentsIsNotPlayedAgain() {
        instrumentation.startActivitySync(share(FIRST, Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
        val trail = shareTrailOnce { it.any(::ignored) }

        assertEquals("Recents replayed an old share and it played. Trail: $trail", 0, trail.count(::played))
        assertTrue("the replay was not recorded as ignored. Trail: $trail", trail.any(::ignored))
    }

    @Test
    fun aShareIsPlayedOnceThoughTheActivityIsRebuilt() {
        val activity = instrumentation.startActivitySync(share(FIRST))
        shareTrailOnce { it.any(::played) }
        instrumentation.runOnMainSync { activity.recreate() }
        val trail = shareTrailOnce { it.any(::ignored) }

        assertEquals("the share must play exactly once. Trail: $trail", 1, trail.count(::played))
        assertTrue("the rebuild was not recorded as a replay. Trail: $trail", trail.any(::ignored))
    }

    /** The other half: a replay guard that also swallowed real shares would pass both tests above. */
    @Test
    fun aNewShareIntoTheOpenActivityIsPlayed() {
        instrumentation.startActivitySync(share(FIRST))
        shareTrailOnce { it.any(::played) }
        instrumentation.targetContext.startActivity(
            share(SECOND, Intent.FLAG_ACTIVITY_SINGLE_TOP, clearTask = false),
        )
        val trail = shareTrailOnce { lines -> lines.count(::played) >= 2 }

        assertEquals("both shares must play. Trail: $trail", 2, trail.count(::played))
        assertTrue(
            "the second share did not arrive by onNewIntent. Trail: $trail",
            trail.any { "via=onNewIntent" in it },
        )
        assertTrue("a fresh share was taken for a replay. Trail: $trail", trail.none(::ignored))
    }

    private fun share(url: String, extraFlags: Int = 0, clearTask: Boolean = true) =
        Intent(instrumentation.targetContext, MainActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "have a look $url")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or extraFlags)
            .addFlags(if (clearTask) Intent.FLAG_ACTIVITY_CLEAR_TASK else 0)

    private fun shareTrailOnce(done: (List<String>) -> Boolean): List<String> {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!done(shareTrail()) && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
        instrumentation.waitForIdleSync()
        return shareTrail()
    }

    private fun shareTrail() = Breadcrumbs.snapshot().filter { it.tag == "share" }.map { it.message }

    private fun played(line: String) = line.startsWith("shared link ->")

    private fun ignored(line: String) = line.startsWith("ignored a replayed share")

    private companion object {
        const val FIRST = "https://www.youtube.com/watch?v=no-video"
        const val SECOND = "https://www.youtube.com/watch?v=no-video-either"
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 50L
    }
}
