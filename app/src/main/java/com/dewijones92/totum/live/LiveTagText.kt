package com.dewijones92.totum.live

import android.content.Context
import android.text.format.DateUtils
import com.dewijones92.totum.R
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.video.LiveStates
import com.dewijones92.totum.video.LiveTag
import com.dewijones92.totum.video.liveTagOf
import com.dewijones92.totum.video.videoIdOf
import java.time.Instant

fun Context.liveTagText(tag: LiveTag): String = when (tag) {
    LiveTag.Live -> getString(R.string.tag_live)
    LiveTag.WasLive -> getString(R.string.tag_was_live)
    is LiveTag.StartsSoon -> tag.startsAt?.let { getString(R.string.tag_starts_at, startsAtText(it)) }
        ?: getString(R.string.tag_starts_soon)
}

fun Context.startsAtText(at: Instant): String = DateUtils.formatDateTime(
    this,
    at.toEpochMilli(),
    DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL or
        if (DateUtils.isToday(at.toEpochMilli())) 0 else DateUtils.FORMAT_SHOW_DATE,
)

class LiveNoticeLabels(private val context: Context, private val states: LiveStates) {
    fun titled(item: MediaItem): String {
        val tag = liveTagOf(item.contentKind, videoIdOf(item.id)?.let(states::of))
        return tag?.let { "${context.liveTagText(it)} · ${item.title}" } ?: item.title
    }
}
