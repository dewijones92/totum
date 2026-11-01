package com.dewijones92.totum.downloads.home

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.dewijones92.totum.R
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.launch

class SendHomeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)
        if (savedInstanceState == null) send(text) else Diag.log("home", "send home replayed after a restore; ignored")
        finish()
    }

    private fun send(text: String?) {
        val app = application as TotumApplication
        val container = app.container
        val sender = SendHome(
            container.homeDownloads,
            { container.homeTorrentServer },
            { url -> container.videoPlaybackLauncher.describe(url, SOURCE) },
            { container.appPreferences.settings.value.downloadChoice },
        )
        Toast.makeText(app, R.string.send_home_sending, Toast.LENGTH_SHORT).show()
        container.applicationScope.launch {
            val words = when (val sent = sender.send(text)) {
                is Sent.Video -> app.getString(R.string.home_notice_started, sent.title)
                is Sent.Torrent -> app.resources.getQuantityString(
                    R.plurals.send_home_torrent,
                    sent.files,
                    sent.files,
                    sent.name
                )
                is Sent.Refused -> app.getString(R.string.send_home_refused, sent.why.words)
            }
            Toast.makeText(app, words, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        val SOURCE = SourceId("shared")
    }
}
