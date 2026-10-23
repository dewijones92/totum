package com.dewijones92.totum.data.download

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.PlayableItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * Tries [primary], and on a failure [shouldFallBack] recognises, tries [secondary] instead.
 *
 * The shape mirrors `FallbackSearchSource`, for the same reason: one path handles almost
 * everything, and a second exists for the cases the first cannot reach — here, the videos YouTube
 * serves to the signed-in app and refuses to yt-dlp. Novara's members-only uploads sat in Dewi's
 * queue for days as "asking again cannot help", correctly, because nothing was ever going to ask
 * differently (report 0.1.346).
 *
 * **Only the primary's progress is forwarded while it is trying.** A download that reports 40% and
 * then restarts from zero under a different mechanism would read as a broken download; the states
 * before a failure are dropped, so the fallback's attempt is the only one anyone sees.
 */
public class FallbackDownloadStrategy(
    private val primary: DownloadStrategy,
    private val secondary: DownloadStrategy,
    /** Whether this failure is one the second path could plausibly fix. */
    private val shouldFallBack: (DownloadState.Failed) -> Boolean,
) : DownloadStrategy {

    override fun download(item: PlayableItem, target: File, audioOnly: Boolean): Flow<DownloadState> =
        download(item, target, audioOnly, maxHeight = null)

    override fun download(
        item: PlayableItem,
        target: File,
        audioOnly: Boolean,
        maxHeight: Int?,
    ): Flow<DownloadState> = flow {
        var failure: DownloadState.Failed? = null
        primary.download(item, target, audioOnly, maxHeight).collect { state ->
            if (state is DownloadState.Failed) failure = state else emit(state)
        }
        val refused = failure
        if (refused == null) return@flow
        if (!shouldFallBack(refused)) {
            emit(refused)
            return@flow
        }
        // Both sides named, because "it failed and then it worked" is otherwise indistinguishable
        // from a flaky connection, and the WHOLE point of this class is which path served it.
        Diag.log(
            "download",
            "\"${item.item.title}\" was refused (${refused.reason.take(REASON_CHARS)}) — " +
                "trying the app's own signed-in path",
        )
        secondary.download(item, target, audioOnly, maxHeight).collect { state ->
            // The first failure is the more informative one: it says WHY the ordinary path could
            // not do it. Kept alongside the second, rather than replaced by it.
            if (state is DownloadState.Failed) {
                emit(DownloadState.Failed("${refused.reason.take(REASON_CHARS)} → ${state.reason}"))
            } else {
                emit(state)
            }
        }
    }

    private companion object {
        const val REASON_CHARS = 80
    }
}
