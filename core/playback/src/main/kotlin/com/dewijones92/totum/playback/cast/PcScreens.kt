package com.dewijones92.totum.playback.cast

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.dewijones92.totum.common.Diag
import java.math.BigInteger
import java.security.SecureRandom

@UnstableApi
internal class PcScreens(
    private val streams: CastStreams,
    private val status: CastStatus,
    private val phone: Player,
    private val switchTo: (Player) -> Unit,
    key: String,
    page: () -> ByteArray,
) {
    private val link = ScreenLink(key, page)
    private val screen = ScreenPlayer(link, streams::urlFor)
    val player: TimeMappedCastPlayer = TimeMappedCastPlayer(screen, streams::timelineFor)

    init {
        streams.screens = link
        screen.follow()
        link.onScreensChanged = { present -> status.screens(present) }
        val base = streams.serveScreens()
        status.screenPage(base?.let { "$it/screen#key=$key" })
        Diag.log(TAG, "the PC screen page is served at ${base ?: "no Wi-Fi address yet"}/screen")
    }

    fun play(id: String?) {
        if (id.isNullOrBlank()) {
            Diag.log(TAG, "back to the phone from screen ${screen.screenId}")
            status.connected(null)
            switchTo(phone)
            return
        }
        val named = link.present().firstOrNull { it.id == id }
        screen.screenId = id
        status.connected(CastTv(named?.name ?: "the PC", screenProfile(named)))
        Diag.log(TAG, "playing on screen ${named?.name} ($id) [codecs=${named?.codecs}]")
        switchTo(player)
    }

    companion object {
        private const val TAG = "screen"
        private const val KEY = "key"
        private const val KEY_BITS = 128
        private const val KEY_RADIX = 36

        fun keyIn(prefs: SharedPreferences): String {
            prefs.getString(KEY, null)?.let { return it }
            val key = BigInteger(KEY_BITS, SecureRandom()).toString(KEY_RADIX)
            prefs.edit { putString(KEY, key) }
            return key
        }
    }
}
