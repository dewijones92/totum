package com.dewijones92.totum.ui.queue

import androidx.compose.runtime.Composable
import com.dewijones92.totum.R
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.ui.common.destructiveTexts
import com.dewijones92.totum.ui.common.rememberHoldToConfirmAsk

@Composable
internal fun rememberClearQueue(queue: PlaybackQueue, size: Int): () -> Unit {
    val confirm = rememberHoldToConfirmAsk()
    val texts = destructiveTexts(R.plurals.queue_clear_confirm, size, R.string.queue_clear_all)
    return { confirm(texts) { queue.clear() } }
}
