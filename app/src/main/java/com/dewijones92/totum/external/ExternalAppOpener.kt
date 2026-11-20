package com.dewijones92.totum.external

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.IPlayer

class ExternalAppOpener(private val context: Context) {

    fun open(url: HttpUrl, title: String): Boolean {
        val own = IPlayer.APP_PACKAGE.takeIf { IPlayer.isEpisode(url) }
        if (own != null && start(url, own)) {
            Diag.log("external", "dewidebug opened \"$title\" in the iPlayer app ($own) $url")
            tell(R.string.external_opening_iplayer)
            return true
        }
        val browser = start(url, packageName = null)
        tell(if (browser) R.string.external_opening_browser else R.string.external_nothing_opens)
        Diag.log(
            "external",
            "dewidebug \"$title\" $url -> " +
                if (browser) {
                    "iPlayer app ${if (own == null) "not asked for" else "not installed or not handling it"}; " +
                        "opened in the browser instead"
                } else {
                    "no app could open it"
                },
        )
        return browser
    }

    private fun tell(message: Int) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }

    private fun start(url: HttpUrl, packageName: String?): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url.value))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .also { if (packageName != null) it.setPackage(packageName) }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Diag.log(
                "external",
                "dewidebug nothing handles $url${packageName?.let { " in $it" }.orEmpty()}: ${e.message}"
            )
            false
        }
    }
}
