package com.dewijones92.totum.recorder

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.reminders.kit.collapseAndOpen

class RecorderTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        if (RecorderState.recording) {
            RecorderService.stop(this, "the Quick Settings tile")
        } else {
            Diag.log(TAG, "dewidebug recorder options asked for from the quick settings tile")
            collapseAndOpen(RecorderActivity.intent(this, FROM_TILE), REQUEST)
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        val recording = RecorderState.recording
        tile.icon = Icon.createWithResource(this, R.drawable.ic_recorder)
        tile.label = getString(R.string.recorder_tile_label)
        tile.state = if (recording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = getString(if (recording) R.string.recorder_tile_recording else R.string.recorder_tile_ready)
        tile.updateTile()
    }

    companion object {
        private const val TAG = "recorder"
        private const val REQUEST = 3
        const val FROM_TILE = "quick settings tile"

        internal fun refresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, RecorderTileService::class.java)) }
                .onFailure { Diag.warn(TAG, "dewidebug recorder tile refresh refused", it) }
        }
    }
}
