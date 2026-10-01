package com.dewijones92.totum.exsurge

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.dewijones92.totum.R
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Diag
import java.time.format.DateTimeFormatter

class ExsurgeTileService : TileService() {
    private val exsurge get() = (application as TotumApplication).container.exsurge

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        val enable = !exsurge.view.value.settings.enabled
        Diag.log(ExsurgeController.TAG, "dewidebug exsurge tile toggled enabled=$enable")
        exsurge.updateSettings("tile") { it.copy(enabled = enable) }
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val view = exsurge.view.value
        tile.icon = Icon.createWithBitmap(SurgiusPainter.glyphBitmap(ICON_PX))
        tile.label = getString(R.string.exsurge_tile_label)
        tile.state = if (view.settings.enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = when (view.memory.state) {
            ExsurgeState.Off -> getString(R.string.exsurge_tile_off)
            is ExsurgeState.Dormant, is ExsurgeState.Paused -> getString(R.string.exsurge_tile_sleeping)
            is ExsurgeState.Sitting -> (view.banner as? BannerLine.Sitting)?.summonsAt
                ?.let { getString(R.string.exsurge_tile_next, TIME.format(it.atZone(view.zone))) }
                ?: getString(R.string.exsurge_tile_done)
            is ExsurgeState.Summoned, is ExsurgeState.Snoozed -> getString(R.string.exsurge_tile_now)
            is ExsurgeState.Rising, is ExsurgeState.OnBreak -> getString(R.string.exsurge_tile_break)
        }
        tile.updateTile()
    }

    companion object {
        private const val ICON_PX = 96
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        fun refresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, ExsurgeTileService::class.java)) }
                .onFailure { Diag.warn(ExsurgeController.TAG, "dewidebug exsurge tile refresh refused", it) }
        }
    }
}
