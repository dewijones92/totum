package com.dewijones92.totum.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider

internal enum class Card { PHONE, DEVICE }

internal data class CardInputs(
    val deviceShown: Boolean,
    val devicePlaying: Boolean,
    val phoneHasItem: Boolean,
    val phonePlaying: Boolean,
)

internal data class CardChoice(val card: Card, val why: String) {
    fun shows(session: Card): Boolean = card == session
}

@UnstableApi
internal object NotificationCards {

    fun choose(inputs: CardInputs): CardChoice = when {
        !inputs.deviceShown -> CardChoice(Card.PHONE, "no device is shown")
        !inputs.phoneHasItem -> CardChoice(Card.DEVICE, "the phone holds nothing")
        inputs.phonePlaying && !inputs.devicePlaying ->
            CardChoice(Card.PHONE, "the phone is playing and the device is not")
        inputs.phonePlaying -> CardChoice(Card.DEVICE, "both are playing, and the device is the one chosen")
        else -> CardChoice(Card.DEVICE, "the device is chosen and the phone is not playing")
    }

    fun cardOf(sessionId: String): Card =
        if (sessionId == RemoteSessionController.REMOTE_SESSION_ID) Card.DEVICE else Card.PHONE

    const val PHONE_NOTIFICATION_ID = DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID
    const val DEVICE_NOTIFICATION_ID = 1002

    fun notificationIdOf(card: Card): Int = if (card == Card.DEVICE) DEVICE_NOTIFICATION_ID else PHONE_NOTIFICATION_ID
}
