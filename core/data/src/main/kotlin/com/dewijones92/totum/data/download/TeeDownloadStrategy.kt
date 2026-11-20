package com.dewijones92.totum.data.download

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.StreamTee
import com.dewijones92.totum.domain.TeeOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import java.io.File

public class TeeDownloadStrategy(
    private val fallback: DownloadStrategy,
    private val tee: StreamTee,
) : DownloadStrategy {

    override fun download(item: PlayableItem, target: File, audioOnly: Boolean): Flow<DownloadState> =
        download(item, target, audioOnly, maxHeight = null)

    override fun download(
        item: PlayableItem,
        target: File,
        audioOnly: Boolean,
        maxHeight: Int?,
    ): Flow<DownloadState> = channelFlow {
        val id = item.item.id.value
        val why = when {
            !audioOnly -> "a video download was chosen, which is not the audio the player streams"
            !tee.canTee(id) -> "no audio stream of it is playing through the cache"
            else -> null
        }
        if (why != null) {
            Diag.log("tee", "dewidebug $id: downloading it on its own: $why")
            fallback.download(item, target, audioOnly, maxHeight).collect { send(it) }
            return@channelFlow
        }
        Diag.log("tee", "dewidebug $id: downloading it from the bytes its stream already fetched")
        send(DownloadState.Downloading(0, null))
        val outcome = tee.complete(
            id,
            target
        ) { held, total -> trySend(DownloadState.Downloading(held, total.takeIf { it >= held })) }
        when (outcome) {
            is TeeOutcome.Completed -> {
                Diag.log(
                    "tee",
                    "dewidebug $id: made its download from the stream's bytes: file ${outcome.bytes} bytes; " +
                        "network bytes for this audio: the tee ${outcome.teeNetworkBytes}, " +
                        "the stream and tee together ${outcome.streamNetworkBytes} " +
                        "(${ratio(outcome.streamNetworkBytes, outcome.bytes)}x the file)",
                )
                send(DownloadState.Downloaded(target.absolutePath, audioOnly = true))
            }
            is TeeOutcome.Failed -> {
                Diag.warn(
                    "tee",
                    "dewidebug $id: the stream's bytes could not become its download (${outcome.why}); " +
                        "downloading it on its own",
                )
                fallback.download(item, target, audioOnly, maxHeight).collect { send(it) }
            }
        }
    }

    private fun ratio(network: Long, file: Long): String =
        if (network < 0 || file <= 0) "unknown" else "%.2f".format(network.toDouble() / file)
}
