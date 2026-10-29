package com.dewijones92.totum.playback.cast

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.dewijones92.totum.common.Diag
import java.math.BigInteger
import java.security.SecureRandom

internal class ScreenSession(
    val phone: Player,
    val switchTo: (Player) -> Unit,
    val current: () -> Player?,
    val hasItem: () -> Boolean,
    val wantsAnItem: () -> Unit,
)

@UnstableApi
internal class PcScreens(
    private val streams: CastStreams,
    private val status: CastStatus,
    private val key: String,
    page: () -> ByteArray,
    private val session: ScreenSession,
) {
    private val main = Handler(Looper.getMainLooper())
    private val link = ScreenLink(key, page)
    private val screen = ScreenPlayer(link, streams::urlFor)
    val player: TimeMappedCastPlayer = TimeMappedCastPlayer(screen, streams::timelineFor)

    init {
        streams.screens = link
        screen.follow()
        link.onScreensChanged = { present -> status.screens(present) }
        link.onWatch = { id -> main.post { watch(id) } }
        val base = streams.serveScreens()
        status.screenPage(base?.let { "$it/screen#key=$key" })
        Diag.log(TAG, "the PC screen page is served at ${base ?: "no Wi-Fi address yet"}/screen")
    }

    fun refreshPage() {
        val base = streams.serveScreens()
        status.screenPage(base?.let { "$it/screen#key=$key" })
        Diag.log(TAG, "screen page address refreshed: ${base ?: "no Wi-Fi address"}/screen")
    }

    fun watch(id: String) {
        val hadItem = session.hasItem()
        Diag.log(
            TAG,
            "Watch here on screen $id: " + if (hadItem) {
                "moving what the phone has and playing it there"
            } else {
                "the phone has nothing loaded, so the queue is asked for its current item"
            },
        )
        play(id)
        if (hadItem) player.play() else session.wantsAnItem()
    }

    fun observe(source: Player) = source.addListener(
        object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (player === session.current()) nowPlaying(player)
            }
        },
    )

    fun nowPlaying(source: Player) {
        val item = source.currentMediaItem
        val title = source.mediaMetadata.title ?: item?.mediaMetadata?.title
        link.nowPlaying = item?.let {
            NowPlaying(
                title = title?.toString() ?: it.mediaId,
                by = (source.mediaMetadata.artist ?: it.mediaMetadata.artist)?.toString(),
                playing = source.isPlaying,
                onScreen = screen.screenId.takeIf { source === player },
            )
        }
    }

    fun play(id: String?) {
        if (id.isNullOrBlank()) {
            Diag.log(TAG, "back to the phone from screen ${screen.screenId}; its streams and locks are let go")
            status.connected(null)
            session.switchTo(session.phone)
            streams.stopAll()
            return
        }
        val named = link.present().firstOrNull { it.id == id }
        val from = screen.screenId
        if (session.current() === player && from != null && from != id) {
            Diag.log(
                TAG,
                "moving from screen $from to $id: the first screen stops, the second takes the item and its place"
            )
            session.switchTo(session.phone)
        }
        screen.screenId = id
        status.connected(CastTv(named?.name ?: "the PC", screenProfile(named)))
        Diag.log(TAG, "playing on screen ${named?.name} ($id) [codecs=${named?.codecs}]")
        session.switchTo(player)
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
