package com.dewijones92.totum.targets

import android.content.Context
import android.text.format.DateFormat
import com.dewijones92.totum.R
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetTrouble
import com.dewijones92.totum.data.targets.TroubleKind
import java.util.Date

interface TargetWords {
    fun trouble(
        label: String,
        trouble: TargetTrouble,
        title: String?,
        kind: TargetKind = TargetKind.TV,
    ): String

    fun signInAgain(): String
}

internal class ContextTargetWords(private val context: Context) : TargetWords {

    override fun trouble(
        label: String,
        trouble: TargetTrouble,
        title: String?,
        kind: TargetKind,
    ): String = when (trouble.kind) {
        TroubleKind.NOT_CONNECTED -> context.getString(R.string.target_trouble_not_connected, label)
        TroubleKind.SWITCH_ON -> context.getString(R.string.target_trouble_switch_on, label)
        TroubleKind.SWITCHED_OFF -> context.getString(switchedOff(kind), label)
        TroubleKind.WONT_WAKE -> context.getString(R.string.target_trouble_wont_wake, label)
        TroubleKind.COULD_NOT_PLAY -> context.getString(
            R.string.refused_tap,
            title ?: context.getString(R.string.target_trouble_that_item),
            trouble.reason ?: context.getString(R.string.target_trouble_no_reason),
        )
        TroubleKind.YOUTUBE_BACKOFF -> context.getString(
            R.string.target_trouble_youtube_backoff,
            trouble.untilMs?.let { DateFormat.getTimeFormat(context).format(Date(it)) }
                ?: context.getString(R.string.target_trouble_later),
        )
        TroubleKind.STALLED -> context.getString(
            R.string.target_trouble_stalled,
            title ?: context.getString(R.string.target_trouble_that_item),
            label,
        )
    }

    private fun switchedOff(kind: TargetKind): Int = when (kind) {
        TargetKind.BROWSER -> R.string.target_trouble_closed
        TargetKind.SPEAKER -> R.string.target_trouble_speaker_gone
        else -> R.string.target_trouble_switched_off
    }

    override fun signInAgain(): String = context.getString(R.string.target_sign_in_again)
}
