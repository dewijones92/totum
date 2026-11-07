package com.dewijones92.totum.ui.common

import android.content.Context
import android.widget.Toast
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.queue.QueueNotice
import kotlin.time.Duration.Companion.milliseconds
import com.dewijones92.totum.domain.ItemShields
import com.dewijones92.totum.domain.titleOf

@Composable
fun QueueNoticeToasts(queue: PlaybackQueue) {
    val context = LocalContext.current
    val offers = LocalSnackbarOffers.current
    val moveUp = stringResource(R.string.queue_move_up)
    LaunchedEffect(queue, offers, moveUp) {
        queue.notices.collect { notice ->
            val message = context.noticeMessage(notice)
            if (notice is QueueNotice.AlreadyQueued && notice.canMoveUp && offers != null) {
                Diag.log("ui", "snackbar: $message")
                offers.offer(message, moveUp, "queue", SnackbarDuration.Long) {
                    queue.moveUp(notice.item)
                }
            } else {
                Diag.log("ui", "toast: $message")
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }
}

internal fun Context.noticeMessage(notice: QueueNotice): String = when (notice) {
    is QueueNotice.RepeatOneOff -> getString(R.string.notice_repeat_one_off, notice.groupTitle)
    is QueueNotice.CarriedOn -> getString(R.string.notice_carried_on, formatClock(notice.positionMs.milliseconds))
    is QueueNotice.AlreadyQueued -> getString(
        when {
            notice.playing -> R.string.notice_already_playing
            notice.next -> R.string.notice_already_next
            else -> R.string.notice_already_queued
        },
        ItemShields.titleOf(notice.item.item),
        notice.position,
    )
    is QueueNotice.SomeAlreadyQueued ->
        resources.getQuantityString(R.plurals.notice_some_already_queued, notice.count, notice.count)
}
