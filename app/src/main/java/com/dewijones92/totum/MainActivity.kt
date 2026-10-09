package com.dewijones92.totum

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.fragment.app.FragmentActivity
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.AppShell
import com.dewijones92.totum.ui.common.LocalNow
import com.dewijones92.totum.ui.common.MusicPage
import com.dewijones92.totum.ui.common.RequestNotificationPermissionOnce
import com.dewijones92.totum.ui.common.mayAskForNotifications
import com.dewijones92.totum.ui.common.rememberTickingNow
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

    private val mayAsk = mutableStateOf(false)

    private val openPlayerRequest = mutableIntStateOf(0)
    private val openMusicPage = mutableStateOf<MusicPage?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        // Never over a video: the dialog is another activity, so it pauses ours and releases the
        // video surface. Opening a YouTube link is the headline entry point and the first thing
        // anyone does on a fresh install, which is exactly when nothing has been granted yet.
        //
        // The predicate lives in mayAskForNotifications, with a test, because it has been wrong
        // twice. It covers same-process recreation (a density or locale change, "don't keep
        // activities"), where the share is a replay and would otherwise read as "nothing to play".
        // It does NOT cover a cold start: `state` is written from the MediaController listener
        // registered in an async connect callback, so onCreate reads null whatever is about to
        // happen.
        val restored = savedInstanceState != null
        mayAsk.value = mayAskForNotifications(
            hasSharedLink = intent.isFreshShare(restored),
            state = container.playbackController.state.value,
        )
        setContent {
            TotumTheme {
                CompositionLocalProvider(LocalNow provides rememberTickingNow()) {
                    AppShell(
                        container,
                        openPlayerRequest = openPlayerRequest.intValue,
                        openMusicPage = openMusicPage.value,
                        askForNotifications = if (mayAsk.value) {
                            { RequestNotificationPermissionOnce() }
                        } else {
                            {}
                        },
                    )
                }
            }
        }
        if (!restored) handleOpenPlayer(intent, via = "onCreate")
        handleShareIntent(intent, via = "onCreate", restored = restored)
    }

    /** A YouTube link shared to us (share sheet or opened directly) plays here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A share queued while the process was dead lands here after onCreate(saved state) had
        // already allowed the ask, and before the first composition makes it.
        if (intent.isFreshShare(restored = false)) mayAsk.value = false
        handleOpenPlayer(intent, via = "onNewIntent")
        handleShareIntent(intent, via = "onNewIntent", restored = false)
    }

    private fun handleOpenPlayer(intent: Intent, via: String) {
        if (!intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) return
        intent.removeExtra(EXTRA_OPEN_PLAYER)
        openPlayerRequest.intValue++
        Diag.log("nav", "dewidebug open the player requested via $via (request ${openPlayerRequest.intValue})")
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

    private fun handleShareIntent(intent: Intent, via: String, restored: Boolean) {
        if (handleAuthIntent(intent)) return
        val album = sharedAlbumPlaylistId(intent.sharedText())
        val url = intent.sharedWatchUrl()
        if (url == null && album == null) return
        val arrival = intent.arrival(restored)
        val facts = "${arrival.why}; via=$via flags=0x${Integer.toHexString(intent.flags)} restored=$restored"
        if (arrival != ShareArrival.FRESH) {
            Diag.log("share", "ignored a replayed share, nothing queued [$facts] -> ${url ?: album}")
            return
        }
        if (album != null) {
            Diag.log("share", "shared link names album playlist $album; opening its album page [$facts]")
            openMusicPage.value = MusicPage.Album(null, album, getString(R.string.music_kind_album))
        }
        if (url == null) return
        Diag.log("share", "shared link -> $url [$facts]")
        val placeholder = placeholderFor(url, SHARED_SOURCE) ?: run {
            Diag.warn("share", "shared link has no video id, so nothing was queued -> $url")
            return
        }
        // App-scoped: a resolve can take a minute offline, and one tied to this activity was cancelled
        // by a rebuild, which now reads the share as a replay, so the link would be lost.
        container.applicationScope.launch {
            val item = container.videoPlaybackLauncher.describe(url, SHARED_SOURCE)
                // Report 0.1.477: shared offline, 53s of retries, then nothing. Queued by its id instead,
                // and resolved when it plays; a bad connection is the common cause, not a bad link.
                ?: placeholder.also {
                    Diag.warn(
                        "share",
                        "shared link could not be resolved now; queued by its id so it is not lost -> $url",
                    )
                }
            container.playbackQueue.playNow(PlayableItem(item, PlayHandle.Video(url)))
        }
    }

    /** The YouTube watch URL from a VIEW (link) or SEND (share text) intent, if any. */
    private fun Intent.sharedWatchUrl(): HttpUrl? = sharedWatchUrl(sharedText())

    private fun Intent.sharedText(): String? = when (action) {
        Intent.ACTION_VIEW -> dataString
        Intent.ACTION_SEND -> getStringExtra(Intent.EXTRA_TEXT)
        else -> null
    }

    private fun Intent.arrival(restored: Boolean): ShareArrival = shareArrival(
        launchedFromHistory = (flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0,
        restored = restored,
    )

    private fun Intent.isFreshShare(restored: Boolean): Boolean =
        arrival(restored) == ShareArrival.FRESH && sharedWatchUrl() != null

    companion object {
        private val SHARED_SOURCE = SourceId("shared")
        private const val EXTRA_OPEN_PLAYER = "totum.openPlayer"

        fun intent(context: Context, openPlayer: Boolean): Intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OPEN_PLAYER, openPlayer)
    }
}
