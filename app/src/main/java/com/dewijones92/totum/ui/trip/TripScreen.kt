package com.dewijones92.totum.ui.trip

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.trip.TripMode
import com.dewijones92.totum.trip.TripStatus
import com.dewijones92.totum.ui.common.BackHeader

private val HOURS = listOf(1, 2, 4, 8)
private val COUNTS = listOf(3, 5, 10)
private const val DEFAULT_HOURS = 2
private const val MS_PER_HOUR = 60 * 60_000L

@Composable
fun TripScreen(container: AppContainer, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val trip = container.tripMode ?: return
    val status by trip.status.collectAsStateWithLifecycle()
    var hours by rememberSaveable { mutableIntStateOf(DEFAULT_HOURS) }
    var perGroup by rememberSaveable { mutableIntStateOf(trip.settings.value.perGroup) }
    Surface(modifier = modifier.fillMaxSize()) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            BackHeader(stringResource(R.string.trip_title), onBack)
            Text(
                text = stringResource(R.string.trip_intro),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            tripLines(status).forEachIndexed { index, line ->
                Text(
                    text = line,
                    style = if (index == 0) {
                        MaterialTheme.typography.titleMedium
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("trip-line-$index"),
                )
            }
            if (status is TripStatus.Off) {
                Choices(hours, perGroup, { hours = it }, { perGroup = it })
            }
            StartStop(trip, status, hours, perGroup)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choices(hours: Int, perGroup: Int, onHours: (Int) -> Unit, onCount: (Int) -> Unit) {
    Text(
        text = stringResource(R.string.trip_leaving_in),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HOURS.forEach { option ->
            FilterChip(
                selected = option == hours,
                onClick = { onHours(option) },
                label = { Text(pluralStringResource(R.plurals.trip_hours, option, option)) },
                modifier = Modifier.testTag("trip-hours-$option"),
            )
        }
    }
    Text(
        text = stringResource(R.string.trip_how_many),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        COUNTS.forEach { option ->
            FilterChip(
                selected = option == perGroup,
                onClick = { onCount(option) },
                label = { Text(option.toString()) },
                modifier = Modifier.testTag("trip-count-$option"),
            )
        }
    }
}

@Composable
private fun StartStop(trip: TripMode, status: TripStatus, hours: Int, perGroup: Int) {
    val on = status is TripStatus.On
    Button(
        onClick = {
            if (on) {
                trip.disarm("the trip page")
            } else {
                Diag.log("trip", "the trip page asked for $hours hour(s), $perGroup per group")
                trip.arm(System.currentTimeMillis() + hours * MS_PER_HOUR, perGroup, "the trip page")
            }
        },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp).testTag("trip-start"),
    ) {
        Text(stringResource(if (on) R.string.trip_stop else R.string.trip_start))
    }
}
