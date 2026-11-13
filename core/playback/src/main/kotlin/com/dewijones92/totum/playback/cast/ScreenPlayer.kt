package com.dewijones92.totum.playback.cast

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.playback.SeekSteps
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

@Suppress("TooManyFunctions")
@UnstableApi
internal class ScreenPlayer(
    private val link: ScreenLink,
    private val urlFor: (MediaItem) -> String?,
    private val now: () -> Long = System::currentTimeMillis,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var screenId: String? = null

    private var items: List<MediaItem> = emptyList()
    private var index = 0
    private var prepared = false
    private var wantsToPlay = false
    private var parameters = PlaybackParameters.DEFAULT
    private var tracks = TrackSelectionParameters.DEFAULT
    private var asked = 0L
    private var report: ScreenReport? = null
    private var reportCountsFrom = 0L
    private var loads = 0L
    private val stale = StaleReports(now)

    fun follow() {
        link.onReport = { id, latest ->
            if (id == screenId) {
                main.post {
                    if (reportIsCurrent(latest.load, loads)) {
                        val ignored = stale.current()
                        if (ignored > 0) {
                            Diag.log(TAG, "the page reports load $loads again after $ignored stale report(s)")
                        }
                        if (latest.atMs >= reportCountsFrom) report = latest
                    } else {
                        val verdict = stale.stale(latest.load, loads)
                        verdict.lines.forEach { Diag.log(TAG, it) }
                        if (verdict.resend) sendLoad()
                    }
                    invalidateState()
                }
            }
        }
    }

    override fun getState(): State {
        val current = items.getOrNull(index)
        val builder = State.Builder()
            .setAvailableCommands(screenCommands())
            .setSeekBackIncrementMs(SeekSteps.BACK_MS)
            .setSeekForwardIncrementMs(SeekSteps.FORWARD_MS)
            .setPlayWhenReady(wantsToPlay, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackParameters(parameters)
            .setTrackSelectionParameters(tracks)
            .setPlaybackState(playbackState(current))
        if (current != null) {
            builder.setPlaylist(
                items.mapIndexed { i, item ->
                    MediaItemData.Builder(item.mediaId.ifBlank { "screen-$i" })
                        .setMediaItem(item)
                        .setDurationUs(item.mediaMetadata.durationMs?.times(MICROS_PER_MS) ?: C.TIME_UNSET)
                        .build()
                },
            )
                .setCurrentMediaItemIndex(index)
                .setContentPositionMs(PositionSupplier { positionNow() })
        }
        return builder.build()
    }

    private fun playbackState(current: MediaItem?): Int {
        val latest = report
        return when {
            !prepared || current == null -> Player.STATE_IDLE
            latest == null -> Player.STATE_BUFFERING
            latest.ended -> Player.STATE_ENDED
            latest.buffering -> Player.STATE_BUFFERING
            else -> Player.STATE_READY
        }
    }

    private fun positionNow(): Long {
        val latest = report ?: return asked
        val moving = latest.playing && !latest.buffering
        val running = if (moving) ((now() - latest.atMs) * parameters.speed).toLong() else 0L
        return (latest.positionMs + running).coerceAtLeast(0)
    }

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        items = mediaItems.toList()
        index = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        asked = startPositionMs.takeIf { it != C.TIME_UNSET } ?: 0L
        forgetReport()
        if (prepared) load()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        prepared = true
        load()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        wantsToPlay = playWhenReady
        tell(if (playWhenReady) """{"do":"play"}""" else """{"do":"pause"}""")
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        asked = positionMs.takeIf { it != C.TIME_UNSET } ?: 0L
        forgetReport()
        if (mediaItemIndex != index && mediaItemIndex in items.indices) {
            index = mediaItemIndex
            load()
        } else {
            tell("""{"do":"seek","pos":$asked}""")
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        parameters = playbackParameters
        tell("""{"do":"rate","rate":${playbackParameters.speed}}""")
        return Futures.immediateVoidFuture()
    }

    override fun handleSetTrackSelectionParameters(
        trackSelectionParameters: TrackSelectionParameters,
    ): ListenableFuture<*> {
        tracks = trackSelectionParameters
        val language = captionsWanted(trackSelectionParameters)
        Diag.log(TAG, "captions on the screen -> ${language ?: "off"}")
        tell(subsCommand(language))
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        prepared = false
        forgetReport()
        tell("""{"do":"stop"}""")
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        tell("""{"do":"stop"}""")
        return Futures.immediateVoidFuture()
    }

    private fun load() {
        loads++
        stale.current()
        sendLoad()
    }

    private fun sendLoad() {
        val item = items.getOrNull(index) ?: return
        val url = urlFor(item)
        if (url == null) {
            Diag.warn(
                TAG,
                "${item.mediaId}: nothing the screen can play (not on Wi-Fi, or no stream yet) [load=$loads]"
            )
            return
        }
        val title = item.mediaMetadata.title?.toString().orEmpty()
        tell(
            """{"do":"load","load":$loads,"url":${quoted(url)},"pos":${positionNow()},"play":$wantsToPlay,""" +
                """"rate":${parameters.speed},"title":${quoted(title)},""" +
                """"subs":${captionsWanted(tracks)?.let(::quoted)}}""",
        )
    }

    private fun forgetReport() {
        report = null
        reportCountsFrom = now()
    }

    private fun tell(command: String) {
        val id = screenId ?: return Diag.warn(TAG, "no screen to tell: $command")
        link.send(id, command)
    }

    private companion object {
        const val TAG = "screen"
        const val MICROS_PER_MS = 1_000L
    }
}

internal val SCREEN_COMMAND_IDS: List<Int> = listOf(
    Player.COMMAND_PLAY_PAUSE,
    Player.COMMAND_PREPARE,
    Player.COMMAND_STOP,
    Player.COMMAND_RELEASE,
    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
    Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
    Player.COMMAND_SEEK_TO_PREVIOUS,
    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
    Player.COMMAND_SEEK_TO_NEXT,
    Player.COMMAND_SEEK_TO_MEDIA_ITEM,
    Player.COMMAND_SEEK_BACK,
    Player.COMMAND_SEEK_FORWARD,
    Player.COMMAND_SET_MEDIA_ITEM,
    Player.COMMAND_CHANGE_MEDIA_ITEMS,
    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
    Player.COMMAND_GET_TIMELINE,
    Player.COMMAND_GET_METADATA,
    Player.COMMAND_SET_SPEED_AND_PITCH,
    Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS,
)

@UnstableApi
private fun screenCommands(): Player.Commands =
    Player.Commands.Builder().apply { SCREEN_COMMAND_IDS.forEach(::add) }.build()

internal fun captionsWanted(parameters: TrackSelectionParameters): String? =
    captionsWanted(parameters.preferredTextLanguages, C.TRACK_TYPE_TEXT in parameters.disabledTrackTypes)

internal fun captionsWanted(preferred: List<String>, textOff: Boolean): String? =
    preferred.firstOrNull()?.takeUnless { textOff }

internal fun subsCommand(language: String?): String =
    if (language == null) """{"do":"subs"}""" else """{"do":"subs","lang":${quoted(language)}}"""
