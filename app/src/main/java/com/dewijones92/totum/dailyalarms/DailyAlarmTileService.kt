package com.dewijones92.totum.dailyalarms

import android.app.PendingIntent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.dewijones92.totum.TotumApplication

class DailyAlarmTileService : TileService() {

    override fun onStartListening() {
        val alarms = (application as TotumApplication).container.dailyAlarms.view.value
        val first = alarms.alarms.firstOrNull()
        val tile = qsTile ?: return
        tile.label = first?.label ?: getString(com.dewijones92.totum.R.string.dailyalarm_tile_label)
        tile.subtitle = when (val state = first?.let { alarms.state(it.id) }) {
            null, DayState.Idle -> if (first?.enabled == true) "Waiting" else "Off"
            is DayState.Asking -> "Asked"
            is DayState.Set -> "Set ${DailyAlarmNotifications.hhmm(state.time)}"
            is DayState.Ringing -> "Ringing"
            is DayState.Snoozed -> "Snoozed"
            is DayState.Done -> "Done today"
        }
        tile.state = if (first?.enabled == true) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        val open = PendingIntent.getActivity(
            this,
            0,
            DailyAlarmsActivity.intent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        startActivityAndCollapse(open)
    }
}
