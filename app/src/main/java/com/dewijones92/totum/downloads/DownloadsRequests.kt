package com.dewijones92.totum.downloads

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.dewijones92.totum.MainActivity
import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DownloadsSection { IN_PROGRESS, FAILED, ON_THE_PHONE }

@Composable
fun AnswerDownloadsRequests(consume: Boolean, open: (DownloadsSection) -> Unit) {
    val asked by DownloadsRequests.requested.collectAsState()
    LaunchedEffect(asked) {
        val section = asked ?: return@LaunchedEffect
        Diag.log(
            "nav",
            "Downloads at ${section.name.lowercase()} answered (${if (consume) "the Library" else "the shell"})"
        )
        open(section)
        if (consume) DownloadsRequests.consumed()
    }
}

object DownloadsRequests {
    private val pending = MutableStateFlow<DownloadsSection?>(null)
    val requested: StateFlow<DownloadsSection?> = pending.asStateFlow()

    const val EXTRA_OPEN_DOWNLOADS = "totum.openDownloads"
    private const val REQUEST_CODE_BASE = 7400

    fun open(section: DownloadsSection, from: String) {
        Diag.log("nav", "open Downloads at ${section.name.lowercase()} (from $from)")
        pending.value = section
    }

    fun consumed() {
        pending.value = null
    }

    fun handle(intent: Intent, via: String) {
        val name = intent.getStringExtra(EXTRA_OPEN_DOWNLOADS) ?: return
        intent.removeExtra(EXTRA_OPEN_DOWNLOADS)
        val section = DownloadsSection.entries.firstOrNull { it.name == name }
        if (section == null) {
            Diag.warn("nav", "a notification asked for Downloads at \"$name\", which is no section; ignored")
            return
        }
        open(section, "a notification via $via")
    }

    fun pendingIntent(context: Context, section: DownloadsSection): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_CODE_BASE + section.ordinal,
        Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_DOWNLOADS, section.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
