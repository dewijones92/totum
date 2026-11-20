package com.dewijones92.totum.playback.cast

import com.google.android.gms.cast.MediaError
import com.google.android.gms.cast.framework.media.RemoteMediaClient

internal class CastReceiverWatch(private val log: ReceiverLog = ReceiverLog()) : RemoteMediaClient.Callback() {

    private var client: RemoteMediaClient? = null

    fun attach(now: RemoteMediaClient?) {
        if (now === client) return
        client?.unregisterCallback(this)
        log.reset()
        client = now
        now?.registerCallback(this)
        look()
    }

    fun detach() = attach(null)

    override fun onStatusUpdated() = look()

    override fun onSendingRemoteMediaRequest() = log.sent()

    override fun onMediaError(error: MediaError) {
        log.error(
            error.type,
            error.detailedErrorCode,
            error.reason,
            error.requestId,
            error.customData?.toString(),
        )
    }

    private fun look() {
        val status = client?.mediaStatus ?: return
        log.status(
            status.playerState,
            status.idleReason,
            status.currentItemId,
            status.loadingItemId,
            status.streamPosition,
        )
    }
}
