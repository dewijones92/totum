package com.dewijones92.totum

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.AppShell
import kotlinx.coroutines.launch

/**
 * A [FragmentActivity], not a bare `ComponentActivity`, purely so Cast works.
 *
 * `MediaRouteButton` shows its device picker as a **DialogFragment**, so tapping it
 * against a plain ComponentActivity throws `IllegalStateException: The activity must be a
 * subclass of FragmentActivity` and takes the app down. Two crash reports from real use
 * (0.1.143 and 0.1.149) are exactly this, and nothing in a Compose-only app otherwise
 * needs fragments — which is why the requirement is invisible until someone taps Cast.
 */
class MainActivity : FragmentActivity() {

    private val container by lazy { (application as TotumApplication).container }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            TotumTheme { AppShell(container) }
        }
        handleShareIntent(intent)
    }

    /** A YouTube link shared to us (share sheet or opened directly) plays here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    /**
     * Stores the token the home server's sign-in page handed back.
     *
     * Returns true when the intent was that and nothing else should look at it. Checked before
     * the share handler because a `totum://auth` URL is a VIEW intent too, and the share path
     * would otherwise try to resolve it as a video.
     */
    private fun handleAuthIntent(intent: Intent): Boolean {
        val data = intent.data?.takeIf { it.scheme == "totum" && it.host == "auth" } ?: return false
        val token = data.getQueryParameter("token").orEmpty()
        // The Prowlarr key rides the same deep link as the token, because it is the same secret
        // in every way that matters: the gate has already established who is asking, and making
        // someone copy a key by hand from a server that just authenticated them is busywork.
        val prowlarrKey = data.getQueryParameter("key").orEmpty()
        // VALUES are never logged. Whether each arrived is, because "did sign-in work?" is the
        // first question when the home server section is empty — and a token without a key is a
        // specific, silent half-failure worth telling apart from both of them missing.
        Diag.log(
            "torrent",
            "home server sign-in returned token=${token.isNotBlank()} prowlarrKey=${prowlarrKey.isNotBlank()}",
        )
        if (token.isNotBlank()) container.appPreferences.setHomeServerToken(token)
        if (prowlarrKey.isNotBlank()) {
            val base = container.appPreferences.settings.value.homeServerBase
            container.appPreferences.setHomeServer(base, prowlarrKey)
        }
        // Consumed, so a rotation or process restart cannot re-apply it.
        setIntent(Intent())
        return true
    }

    private fun handleShareIntent(intent: Intent) {
        if (handleAuthIntent(intent)) return
        val url = intent.sharedWatchUrl() ?: return
        // Logged because this path was completely silent: a shared link that misbehaved left
        // nothing in a report tying the playback to the share (0.1.228).
        Diag.log("share", "shared link -> $url")
        // Consumed, so it plays ONCE — and consumed TWO ways, because clearing the field is not
        // enough on its own.
        //
        // `setIntent(Intent())` only replaces the Activity's in-memory intent. The TASK keeps the
        // intent it was launched with, so reopening from recents — especially after the process has
        // been killed — delivers the original ACTION_SEND again. Report 0.1.346 caught exactly that:
        // one shared link fired five times over five hours (21:22, then 02:16, 02:20, 02:21,
        // 02:22), barging a TED talk in over whatever was playing each time. The clear alone had
        // been in place the whole while.
        //
        // Marking the intent itself is what survives that, because the extra travels with the
        // intent the task redelivers.
        intent.putExtra(EXTRA_SHARE_HANDLED, true)
        setIntent(Intent())
        // Resolved first so the queue entry carries a real title rather than a URL; a
        // shared link is a deliberate, occasional action, so the extra resolve is cheap.
        lifecycleScope.launch {
            val item = container.videoPlaybackLauncher.describe(url, SHARED_SOURCE)
                // A share that resolves to nothing used to vanish without a word (report 0.1.477: no
                // network, 53s of yt-dlp retries, then silence). The link is queued by its id instead
                // and resolves when it plays; a bad connection is the common cause, not a bad link.
                ?: placeholderFor(url, SHARED_SOURCE)?.also {
                    Diag.warn(
                        "share",
                        "shared link could not be resolved now; queued by its id so it is not lost -> $url",
                    )
                }
                ?: run {
                    Diag.warn("share", "shared link is not a YouTube video, so nothing was queued -> $url")
                    return@launch
                }
            container.playbackQueue.playNow(PlayableItem(item, PlayHandle.Video(url)))
        }
    }

    /** The YouTube watch URL from a VIEW (link) or SEND (share text) intent, if any. */
    private fun Intent.sharedWatchUrl(): HttpUrl? = sharedWatchUrl(
        rawText = when (action) {
            Intent.ACTION_VIEW -> dataString
            Intent.ACTION_SEND -> getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        },
        alreadyHandled = getBooleanExtra(EXTRA_SHARE_HANDLED, false),
    )

    private companion object {
        /**
         * Marks a share intent as spent. On the intent rather than in a field, so it survives the
         * task being redelivered after the process is killed — the case a cleared field misses.
         */
        const val EXTRA_SHARE_HANDLED = "com.dewijones92.totum.SHARE_HANDLED"

        val SHARED_SOURCE = SourceId("shared")
        val URL_PATTERN = Regex("""https?://\S+""")
        val WATCH_MARKERS = listOf(
            "youtube.com/watch",
            "m.youtube.com/watch",
            "youtu.be/",
            "youtube.com/shorts/",
        )
    }
}
