package com.dewijones92.totum.dailyalarms

import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.dewijones92.totum.R
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Diag

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
        val intent = DailyAlarmsActivity.newAlarm(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    1,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                ),
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
