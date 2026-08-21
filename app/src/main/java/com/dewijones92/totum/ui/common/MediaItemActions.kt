package com.dewijones92.totum.ui.common

import android.widget.Toast
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.source.SourceLocator
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.toPlayableOrNull
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.settings.AppPreferences
import com.dewijones92.totum.settings.PlaybackMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The per-row long-press actions shared by every feed, both pillars — queue and
 * playlist wiring lives here once so no screen re-implements it. A feed item's
 * pillar/handle is inferred from its media URL ([toPlayableOrNull]); items
 * without a playable URL yet simply can't be queued (the action no-ops).
 */
/**
 * The two things a row action needs from the surrounding UI and cannot do itself: say
 * something, and open the player. Bundled because they travel together and are the only
 * parts of [MediaItemActions] that are not pure app state.
 */
interface UiEffects {
    fun announce(message: String)
    fun expandPlayer()
}

/**
 * Reading and changing the listen/watch mode — one concern, so one dependency.
 *
 * They arrived here as two: an [AppPreferences] to write with, and a hand-rolled
 * `mode == AUDIO` to read with. That read is wrong on [PlaybackMode.AUTO], which is the shipped
 * default and means "audio on mobile data" — so on 4G a row said "Listen only" while already
 * listening, and never offered the picture back. Bundling them means the next caller cannot take the
 * write and re-invent the read.
 */
interface ListenMode {
    /** Whether we are listening RIGHT NOW, network resolved. */
    val listening: Boolean

    /** Make listening (or watching) the mode from here on. */
    fun choose(audio: Boolean)
}

class MediaItemActions internal constructor(
    private val queue: PlaybackQueue,
    private val openPlaylistPicker: (MediaItem) -> Unit,
    private val locator: SourceLocator,
    private val scope: CoroutineScope,
    private val mode: ListenMode,
    private val ui: UiEffects,
) {
    /**
     * Whether we are listening, so a row can label its action "Listen only" vs "Watch with video".
     *
     * Asked of the caller rather than derived here, because this read used to be `mode == AUDIO` and
     * that is FALSE on [PlaybackMode.AUTO] however metered the connection is -- and AUTO is the shipped
     * default. On mobile data the app played audio while every row offered "Listen only" and never
     * offered the picture back. One answer now, the one playback uses.
     */
    val audioMode: Boolean get() = mode.listening

    /**
     * Plays [item] the other way round and **makes that the mode**, announcing it —
     * a row action that silently changed a global setting would be baffling, and
     * hiding the mode in a settings screen would be worse.
     */
    fun switchMode(item: MediaItem, toAudio: Boolean, audioOnMessage: String, videoOnMessage: String) {
        mode.choose(audio = toAudio)
        // Asking a row for video is asking for the PICTURE, so it has to overrule a sound-only rescue
        // the same way the player's own Watch button does. Without this the mode changed, the toast said
        // video was on, and the item came back as sound only -- because the refusal is sticky per item
        // and only WatchViewModel cleared it. Report 0.1.444, "can't see video?mmm": mode=VIDEO and
        // three routes still carrying listen=true. See TheSoundRungPlaysTheFailINGItemTest.
        if (!toAudio) queue.wantsThePictureAgain(item.id)
        ui.announce(if (toAudio) audioOnMessage else videoOnMessage)
        val playable = item.toPlayableOrNull() ?: return
        scope.launch { queue.playNow(playable) }
    }
    fun playNext(item: MediaItem) {
        item.toPlayableOrNull()?.let(queue::playNext)
    }

    fun addToQueue(item: MediaItem) {
        item.toPlayableOrNull()?.let(queue::enqueue)
    }

    fun addToPlaylist(item: MediaItem) {
        openPlaylistPicker(item)
    }

    /**
     * Plays the item **without touching the queue** — a one-off, so a carefully
     * built queue survives. The counterpart to tapping, which queues.
     *
     * Opens the full player with it, once it is actually playing. Peeking is "show me
     * this one thing", so leaving it to start invisibly behind the list you peeked from
     * made the action feel like it had done nothing (Dewi, 2026-07-30). Gated on the play
     * succeeding — expanding on a failed resolve would leave the player poised to spring
     * open the next time anything played.
     */
    fun peek(item: MediaItem) {
        val playable = item.toPlayableOrNull() ?: run {
            Diag.warn("peek", "\"${item.title}\" has nothing playable to peek at")
            return
        }
        scope.launch {
            val playing = queue.peek(playable)
            Diag.log("peek", "\"${item.title}\" -> ${if (playing) "playing, opening the player" else "did not start"}")
            if (playing) ui.expandPlayer()
        }
    }

    /**
     * Resolves the item's source and hands it to [onResolved] to navigate to.
     * A subscribed podcast feed is a local lookup; a video's channel is resolved
     * through the engine, so this may take a moment. Does nothing when the source
     * can't be determined.
     */
    fun goToSource(item: MediaItem, onResolved: (MediaSource) -> Unit) {
        scope.launch { locator.locate(item)?.let(onResolved) }
    }
}

/** Wires [MediaItemActions] from the container and hosts the add-to-playlist picker dialog. */
/**
 * Wires [MediaItemActions] from the container, hosting the add-to-playlist picker and
 * the snackbar its mode switch announces through. [snackbar] lets a screen that
 * already has a host share it; otherwise messages fall back to a toast.
 */
@Composable
fun rememberMediaItemActions(
    container: AppContainer,
    snackbar: SnackbarHostState? = null,
): MediaItemActions {
    val adder = com.dewijones92.totum.ui.playlist.rememberPlaylistAdder(container)
    // Two scopes, deliberately. Starting playback goes on the application scope so that
    // changing tabs mid-resolve cannot cancel it; only the snackbar — which genuinely has
    // nothing to say once its host is gone — stays tied to the composition.
    val uiScope = rememberCoroutineScope()
    val context = LocalContext.current
    val expandPlayer = LocalExpandPlayer.current
    return remember(container, adder, uiScope, snackbar, expandPlayer) {
        MediaItemActions(
            queue = container.playbackQueue,
            openPlaylistPicker = adder,
            locator = container.sourceLocator,
            scope = container.applicationScope,
            mode = object : ListenMode {
                override val listening: Boolean get() = container.listeningNow
                override fun choose(audio: Boolean) =
                    container.appPreferences.setPlaybackMode(if (audio) PlaybackMode.AUDIO else PlaybackMode.VIDEO)
            },
            ui = object : UiEffects {
                override fun announce(message: String) {
                    if (snackbar != null) {
                        uiScope.launch { snackbar.showSnackbar(message) }
                    } else {
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                }

                override fun expandPlayer() = expandPlayer()
            },
        )
    }
}
