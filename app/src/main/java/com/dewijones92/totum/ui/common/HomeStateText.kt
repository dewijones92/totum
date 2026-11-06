package com.dewijones92.totum.ui.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.res.stringResource
import com.dewijones92.totum.R
import com.dewijones92.totum.data.home.HomeJob
import com.dewijones92.totum.data.home.HomeJobState
import com.dewijones92.totum.data.home.transfer
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItemId

internal class HomeRowOffers(val copyNow: Boolean, val retry: Boolean, @param:StringRes val remove: Int)

internal object HomeRow {
    @StringRes
    fun doneWords(onPhone: Boolean, copiesItself: Boolean): Int =
        when {
            onPhone -> R.string.home_state_done_on_phone
            copiesItself -> R.string.home_state_done
            else -> R.string.home_state_done_kept
        }

    fun offers(job: HomeJob, local: DownloadState): HomeRowOffers = HomeRowOffers(
        copyNow = job.state == HomeJobState.DONE && local !is DownloadState.Downloading &&
            (local !is DownloadState.Downloaded || local.audioOnly && !job.audioOnly),
        retry = job.state == HomeJobState.FAILED,
        remove = if (job.state == HomeJobState.DONE) R.string.home_delete else R.string.cancel,
    )
}

@Composable
internal fun homeStateText(job: HomeJob): String = homeStateText(job, onPhone = false)

@Composable
internal fun homeStateText(job: HomeJob, onPhone: Boolean): String =
    job.transfer()?.let { transferText(it) }
        ?: stringResource(HomeRow.doneWords(onPhone, copiesItself(MediaItemId(job.id))))

@Composable
private fun copiesItself(id: MediaItemId): Boolean {
    val home = LocalHomeDownloads.current ?: return false
    val kept = home.kept.collectAsState().value
    return home.copiesByItself && id.value !in kept
}
