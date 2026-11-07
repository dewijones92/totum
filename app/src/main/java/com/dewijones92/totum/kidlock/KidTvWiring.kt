package com.dewijones92.totum.kidlock

import android.content.Context
import com.dewijones92.totum.R
import com.dewijones92.totum.cast.CastTarget
import com.dewijones92.totum.cast.MediaRouterTvConnector
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.di.AppContainer
import com.google.android.gms.cast.framework.CastContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal fun kidTvFor(c: AppContainer, context: Context): KidTv = KidTv(
    store = SharedPrefsKidTvStore(context),
    pins = { c.pinStore.pins.value },
    ports = KidTvPorts(
        connector = MediaRouterTvConnector(context, CastTarget.device),
        castDevice = CastTarget.device,
        play = { pin -> c.pinPlayer.play(pin, from = "the Kid TV button") },
        pause = { c.playbackController.setPlaying(false) },
        endCast = { endCurrentCast(context) },
        startingLabel = { title, tv -> context.getString(R.string.kidtv_starting, title, tv) },
        settleHandOver = { settleCastHandOver(c) },
    ),
    lock = c.kidLock,
    operations = c.operations,
    scope = c.applicationScope,
)

private fun endCurrentCast(context: Context) {
    runCatching { CastContext.getSharedInstance(context).sessionManager.endCurrentSession(true) }
        .onFailure { Diag.warn("kidlock", "could not end the cast session", it) }
}

private suspend fun settleCastHandOver(c: AppContainer) {
    delay(MOVE_STARTS_MS)
    val settled = withTimeoutOrNull(MOVE_WAIT_MS) {
        c.operations.running.first { running -> running.none { it.key == CAST_MOVE_KEY } }
    }
    Diag.log(
        "kidlock",
        "waited for the cast hand-over before starting the playlist: settled=${settled != null} " +
            "(${MOVE_STARTS_MS}ms head start, up to ${MOVE_WAIT_MS}ms)",
    )
}

private const val CAST_MOVE_KEY = "cast-move"
private const val MOVE_STARTS_MS = 1_500L
private const val MOVE_WAIT_MS = 20_000L
