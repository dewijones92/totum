package com.dewijones92.totum.diagnostics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.diagnostics.StateDump
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class StateDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Diag.log(TAG, "dewidebug DEBUG broadcast action=${intent.action}")
        if (intent.action == ACTION_CRASH) {
            throw IllegalStateException("dewidebug DEBUG_CRASH: a deliberate crash to show the crash screen")
        }
        val container = (context.applicationContext as TotumApplication).container
        val pending = goAsync()
        container.applicationScope.launch(Dispatchers.IO) {
            try {
                when (intent.action) {
                    ACTION_LOAD_STATE -> load(context, container)
                    ACTION_DUMP_STATE -> dump(context, container)
                }
            } catch (failure: Exception) {
                Diag.warn(TAG, "dewidebug DEBUG ${intent.action} failed", failure)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun dump(context: Context, container: com.dewijones92.totum.di.AppContainer) {
        val bytes = container.homeReports.stateDump()
        if (bytes == null) {
            Diag.warn(TAG, "dewidebug dump-state: nothing was gathered")
            return
        }
        File(context.filesDir, DUMP_FILE).writeBytes(bytes)
        Diag.log(TAG, "dewidebug dump-state: wrote ${bytes.size} bytes gzipped to files/$DUMP_FILE")
    }

    private suspend fun load(context: Context, container: com.dewijones92.totum.di.AppContainer) {
        val file = File(context.filesDir, LOAD_FILE)
        if (!file.isFile) {
            Diag.warn(TAG, "dewidebug load-state: files/$LOAD_FILE is not there, so nothing was loaded")
            return
        }
        val dump = StateDump.unpack(file.readBytes())
        val backup = StateDump.backupOf(dump)
        if (backup == null) {
            Diag.warn(TAG, "dewidebug load-state: the dump holds no readable backup section, nothing loaded")
            return
        }
        Diag.log(
            TAG,
            "dewidebug load-state: dump format=${dump["format"]} taken=${dump["takenAtMs"]} " +
                "app=${dump["appVersion"]} holds ${backup.subscriptions.size} subs, ${backup.playlists.size} " +
                "playlists, ${backup.history.size} history, ${backup.queue.size} queued, " +
                "${backup.progress.size} progress, ${backup.groups.size} groups, ${backup.settings.size} settings",
        )
        val summary = container.backupService.restore(backup)
        container.appPreferences.setAutoDownloadQueue(false)
        Diag.log(
            TAG,
            "dewidebug load-state: restored $summary; queue auto-download switched off on this emulator; " +
                "the YouTube sign-in, stats, trail and downloads are not part of a restore and were not touched",
        )
        file.delete()
    }

    companion object {
        const val ACTION_CRASH = "com.dewijones92.totum.DEBUG_CRASH"
        const val ACTION_LOAD_STATE = "com.dewijones92.totum.DEBUG_LOAD_STATE"
        const val ACTION_DUMP_STATE = "com.dewijones92.totum.DEBUG_DUMP_STATE"
        const val LOAD_FILE = "load-state.json.gz"
        const val DUMP_FILE = "state-dump.json.gz"
        private const val TAG = "diagnostics"
    }
}
