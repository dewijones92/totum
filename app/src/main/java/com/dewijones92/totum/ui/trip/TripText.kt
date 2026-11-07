package com.dewijones92.totum.ui.trip

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.dewijones92.totum.R
import com.dewijones92.totum.trip.TripProgress
import com.dewijones92.totum.trip.TripStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun departureText(epochMs: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale) }
    return format.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
}

@Composable
internal fun tripLines(status: TripStatus): List<String> = when (status) {
    TripStatus.Off -> listOf(stringResource(R.string.trip_tile_off))
    is TripStatus.On -> buildList {
        add(stringResource(R.string.trip_tile_leaving, departureText(status.departureAtMs)))
        if (status.waitingForWifi) add(stringResource(R.string.trip_waiting_wifi))
        addAll(progressLines(status.progress))
    }
}

@Composable
private fun progressLines(progress: TripProgress?): List<String> {
    if (progress == null) return listOf(stringResource(R.string.trip_progress_none))
    return buildList {
        add(pluralStringResource(R.plurals.trip_progress, progress.wanted, progress.onPhone, progress.wanted))
        if (progress.fetching > 0) {
            add(pluralStringResource(R.plurals.trip_fetching, progress.fetching, progress.fetching))
        }
        if (progress.fromPi > 0) add(pluralStringResource(R.plurals.trip_from_pi, progress.fromPi, progress.fromPi))
        if (progress.skipped > 0) add(pluralStringResource(R.plurals.trip_skipped, progress.skipped, progress.skipped))
        if (progress.failed > 0) add(pluralStringResource(R.plurals.trip_failed, progress.failed, progress.failed))
        if (progress.lowSpace) add(stringResource(R.string.trip_low_space))
    }
}
