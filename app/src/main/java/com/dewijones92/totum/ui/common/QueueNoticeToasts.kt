package com.dewijones92.totum.ui.common

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.queue.QueueNotice

@Composable
fun QueueNoticeToasts(queue: PlaybackQueue) {
    val context = LocalContext.current
    LaunchedEffect(queue) {
        queue.notices.collect { notice ->
            val message = context.noticeMessage(notice)
            Diag.log("ui", "toast: $message")
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}

internal fun Context.noticeMessage(notice: QueueNotice): String = when (notice) {
    is QueueNotice.RepeatOneOff -> getString(R.string.notice_repeat_one_off, notice.groupTitle)
}
