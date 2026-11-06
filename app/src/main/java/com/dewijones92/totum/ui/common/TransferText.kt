package com.dewijones92.totum.ui.common

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import com.dewijones92.totum.R
import com.dewijones92.totum.domain.Transfer
import com.dewijones92.totum.domain.TransferPhase
import com.dewijones92.totum.domain.TransferPlace

@StringRes
internal fun placeWords(place: TransferPlace): Int = when (place) {
    TransferPlace.WEB -> R.string.transfer_place_web
    TransferPlace.PHONE -> R.string.transfer_place_phone
    TransferPlace.PI -> R.string.transfer_place_pi
}

@StringRes
private fun verbWords(transfer: Transfer): Int = when {
    transfer.from == TransferPlace.WEB -> R.string.transfer_verb_download
    transfer.to == TransferPlace.PI -> R.string.transfer_verb_upload
    else -> R.string.transfer_verb_copy
}

internal fun transferRoute(resources: Resources, transfer: Transfer): String = resources.getString(
    R.string.transfer_route,
    resources.getString(verbWords(transfer)),
    resources.getString(placeWords(transfer.from)),
    resources.getString(placeWords(transfer.to)),
)

internal fun sendingToPi(from: TransferPlace): Transfer = Transfer.running(from, TransferPlace.PI, null)

internal fun transferTitled(resources: Resources, transfer: Transfer, subject: String): String =
    resources.getString(R.string.transfer_titled, transferRoute(resources, transfer), subject)

internal fun transferText(resources: Resources, transfer: Transfer): String = when (transfer.phase) {
    TransferPhase.WAITING -> resources.getString(R.string.transfer_waiting, transferRoute(resources, transfer))
    TransferPhase.RUNNING ->
        transfer.percent
            ?.let { resources.getString(R.string.transfer_percent, transferRoute(resources, transfer), it) }
            ?: resources.getString(R.string.transfer_starting, transferRoute(resources, transfer))
    TransferPhase.FAILED -> resources.getString(
        R.string.transfer_failed,
        transferRoute(resources, transfer),
        transfer.reason.orEmpty(),
    )
}

@Composable
internal fun transferText(transfer: Transfer): String = transferText(LocalResources.current, transfer)
