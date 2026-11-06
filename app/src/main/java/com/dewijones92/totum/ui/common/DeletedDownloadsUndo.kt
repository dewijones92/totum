package com.dewijones92.totum.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalResources
import com.dewijones92.totum.R
import com.dewijones92.totum.downloads.PendingDownloadDeletes

@Composable
fun DeletedDownloadsUndo(deletes: PendingDownloadDeletes?) {
    val offers = LocalSnackbarOffers.current
    val resources = LocalResources.current
    LaunchedEffect(deletes, offers) {
        if (deletes == null || offers == null) return@LaunchedEffect
        deletes.offers.collect { offer ->
            val message = offer.title?.let { resources.getString(R.string.download_deleted_titled, it) }
                ?: resources.getQuantityString(R.plurals.download_deleted_count, offer.ids.size, offer.ids.size)
            offers.offer(message, resources.getString(R.string.undo), "download", SnackbarDuration.Long) {
                deletes.undo(offer.ids, "the snackbar's Undo")
            }
        }
    }
}
