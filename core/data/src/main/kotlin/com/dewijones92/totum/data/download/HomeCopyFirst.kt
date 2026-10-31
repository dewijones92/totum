package com.dewijones92.totum.data.download

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.HomeCopy
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.playedFromHome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

public class HomeCopyFirst(
    private val atHome: (PlayableItem) -> HomeCopy?,
    private val http: DownloadStrategy,
    private val next: DownloadStrategy,
) : DownloadStrategy {

    override fun download(item: PlayableItem, target: File, audioOnly: Boolean): Flow<DownloadState> =
        download(item, target, audioOnly, maxHeight = null)

    override fun download(item: PlayableItem, target: File, audioOnly: Boolean, maxHeight: Int?): Flow<DownloadState> {
        val home = atHome(item)?.takeIf { audioOnly || !it.audioOnly }
        if (home == null) return next.download(item, target, audioOnly, maxHeight)
        Diag.log("download", "\"${item.item.title}\": copying the finished copy from home (${home.url})")
        return http.download(item.playedFromHome(home), target, audioOnly).map { state ->
            if (state is DownloadState.Downloaded) state.copy(audioOnly = home.audioOnly) else state
        }
    }
}
