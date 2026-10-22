package com.dewijones92.totum.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.dewijones92.totum.R

data class DownloadsSummary(val onPhone: Int = 0, val running: Int = 0, val failed: Int = 0) {
    @Composable
    fun describe(): String {
        val res = LocalContext.current.resources
        return listOfNotNull(
            res.getQuantityString(R.plurals.downloads_summary_on_phone, onPhone, onPhone),
            running.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.downloads_summary_running, it, it) },
            failed.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.downloads_summary_failed, it, it) },
        ).joinToString(" · ")
    }
}
