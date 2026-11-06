package com.dewijones92.totum.ui.common

import androidx.annotation.StringRes
import com.dewijones92.totum.R
import com.dewijones92.totum.domain.ChosenWay
import com.dewijones92.totum.domain.PlayReason

internal data class PlayReasonPhrase(
    @StringRes val withDetail: Int,
    @StringRes val withoutDetail: Int,
    val detail: String? = null,
)

internal fun PlayReason?.phrase(): PlayReasonPhrase = when (this) {
    null -> alone(R.string.why_unknown)
    is PlayReason.Chosen -> alone(way.phraseRes())
    is PlayReason.Next -> PlayReasonPhrase(R.string.why_next_group, R.string.why_next_queue, group)
    is PlayReason.Previous -> PlayReasonPhrase(R.string.why_previous_group, R.string.why_previous_queue, group)
    PlayReason.RepeatOne -> alone(R.string.why_repeat_one)
    is PlayReason.RepeatGroup -> PlayReasonPhrase(R.string.why_repeat_group_named, R.string.why_repeat_group, group)
    PlayReason.RepeatQueue -> alone(R.string.why_repeat_queue)
    is PlayReason.Explore -> PlayReasonPhrase(R.string.why_explore_named, R.string.why_explore, relatedTo)
    is PlayReason.RelatedAfterQueue ->
        PlayReasonPhrase(R.string.why_related_after_queue_named, R.string.why_related_after_queue, relatedTo)
    is PlayReason.Radio -> PlayReasonPhrase(R.string.why_radio_named, R.string.why_radio, station)
    PlayReason.NewUpload -> alone(R.string.why_new_upload)
}

private fun alone(@StringRes res: Int) = PlayReasonPhrase(res, res)

@StringRes
private fun ChosenWay.phraseRes(): Int = when (this) {
    ChosenWay.TAP -> R.string.why_chosen_tap
    ChosenWay.PEEK -> R.string.why_chosen_peek
    ChosenWay.LINK -> R.string.why_chosen_link
    ChosenWay.PIN -> R.string.why_chosen_pin
    ChosenWay.SHORTS_REEL -> R.string.why_chosen_shorts
    ChosenWay.ASK_CARD -> R.string.why_chosen_ask
    ChosenWay.QUEUE_ROW -> R.string.why_chosen_queue_row
    ChosenWay.BACK_OR_FORWARD -> R.string.why_chosen_trail
    ChosenWay.SWITCH -> R.string.why_chosen_switch
    ChosenWay.SHUFFLE -> R.string.why_chosen_shuffle
    ChosenWay.NOTIFICATION -> R.string.why_chosen_notification
}
