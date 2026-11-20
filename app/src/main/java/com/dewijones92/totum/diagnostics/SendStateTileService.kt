package com.dewijones92.totum.diagnostics

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.dewijones92.totum.R
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.launch

class SendStateTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        if (sending) {
            Diag.log(TAG, "dewidebug send state tile tapped while a send is already running: ignored")
            return
        }
        val container = (application as TotumApplication).container
        val app = applicationContext
        sending = true
        lastResult = null
        render()
        container.applicationScope.launch {
            val message = container.sendMyState(app.resources, FROM).message(app.resources)
            sending = false
            lastResult = message
            Toast.makeText(app, message, Toast.LENGTH_LONG).show()
            refresh(app)
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_send_state)
        tile.label = getString(R.string.send_state_tile_label)
        tile.state = if (sending) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = if (sending) getString(R.string.settings_send_state_sending) else lastResult
        tile.updateTile()
    }

    companion object {
        private const val TAG = "diagnostics"
        private const val FROM = "the Quick Settings tile"

        @Volatile private var sending = false

        @Volatile private var lastResult: String? = null

        private fun refresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, SendStateTileService::class.java)) }
                .onFailure { Diag.warn(TAG, "dewidebug send state tile refresh refused", it) }
        }
    }
}
