package com.dewijones92.totum.diagnostics

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.reminders.kit.collapseAndOpen

class SendReportTileService : TileService() {

    override fun onStartListening() {
        val tile = qsTile ?: return
        tile.label = getString(R.string.send_report_tile_label)
        tile.state = Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        Diag.log("diagnostics", "report box asked for from the quick settings tile")
        collapseAndOpen(SendReportActivity.fromTile(this), 2)
    }
}
