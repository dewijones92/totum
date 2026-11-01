package com.dewijones92.totum.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.dewijones92.totum.R
import com.dewijones92.totum.data.home.HomeJobState
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.downloads.home.HomeDownloads

internal val LocalHomeDownloads = staticCompositionLocalOf<HomeDownloads?> { null }

@Composable
internal fun HomeDownloadActions(id: MediaItemId?, onDismiss: () -> Unit) {
    val home = LocalHomeDownloads.current ?: return
    id ?: return
    val local = downloadStateOf(id)
    val anyJob = homeJobOf(id)
    if (anyJob == null && local is DownloadState.Downloaded && home.available) {
        SheetAction(
            { home.sendFromPhone(id, move = false) },
            Icons.Outlined.Home,
            R.string.home_copy_to_home,
            onDismiss
        )
        SheetAction({ home.sendFromPhone(id, move = true) }, Icons.Outlined.Home, R.string.home_move_to_home, onDismiss)
        return
    }
    val job = anyJob?.takeIf { it.state == HomeJobState.DONE } ?: return
    val onPhone = local is DownloadState.Downloaded
    SheetAction(
        { home.copyNow(id) }.takeIf { HomeRow.offers(job, local).copyNow },
        Icons.Outlined.Download,
        R.string.home_copy_now,
        onDismiss,
    )
    SheetAction(
        {
            home.deleteOnPhoneOnly(id)
        }.takeIf { onPhone },
        Icons.Outlined.DeleteOutline,
        R.string.home_delete_phone_only,
        onDismiss
    )
    SheetAction(
        { home.cancel(id) },
        Icons.Outlined.Home,
        if (onPhone) R.string.home_delete_home_only else R.string.home_delete_at_home,
        onDismiss,
    )
}
