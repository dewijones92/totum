package com.dewijones92.totum.dailyalarms

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.reminders.kit.collapseAndOpen

class DailyAlarmTileService : TileService() {

    override fun onStartListening() {
        val alarms = (application as TotumApplication).container.dailyAlarms.view.value
        val first = alarms.daily.firstOrNull { !it.isReminder } ?: alarms.daily.firstOrNull()
        val tile = qsTile ?: return
        tile.label = first?.label ?: getString(com.dewijones92.totum.R.string.dailyalarm_tile_label)
        tile.subtitle = when (val state = first?.let { alarms.state(it.id) }) {
            null, DayState.Idle -> if (first?.enabled == true) "⏳ Waiting" else "⚪ Off"
            is DayState.Asking -> "❓ Asked"
            is DayState.Set -> "✅ Set ${DailyAlarmNotifications.hhmm(state.time)}"
            is DayState.Ringing -> "🔔 Ringing"
            is DayState.Snoozed -> "😴 Snoozed"
            is DayState.Reminding -> "🔔 Reminding"
            is DayState.Done -> "🏁 Done today"
        }
        tile.state = if (first?.enabled == true) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() = collapseAndOpen(DailyAlarmsActivity.intent(this), 0)
}
