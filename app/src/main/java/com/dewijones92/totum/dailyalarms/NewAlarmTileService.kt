package com.dewijones92.totum.dailyalarms

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.dewijones92.totum.R
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.reminders.kit.collapseAndOpen

class NewAlarmTileService : TileService() {

    override fun onStartListening() {
        val view = (application as TotumApplication).container.dailyAlarms.view.value
        val tile = qsTile ?: return
        tile.label = getString(R.string.oneoff_new)
        tile.subtitle = view.oneOffs.firstOrNull()?.let {
            "⏰ ${DailyAlarmNotifications.hhmm(it.defaultTime)} ${it.label}"
        }
        tile.state = Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        Diag.log(DailyAlarmController.TAG, "dewidebug dailyalarm new alarm sheet opened from the quick settings tile")
        collapseAndOpen(DailyAlarmsActivity.newAlarm(this), 1)
    }
}
