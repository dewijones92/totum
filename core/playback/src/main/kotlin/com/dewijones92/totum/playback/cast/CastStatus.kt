package com.dewijones92.totum.playback.cast

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.session.MediaSession
import com.dewijones92.totum.domain.CastScreen

internal class CastTv(val name: String, val screen: CastScreen)

internal interface CastTvPort {
    fun current(): CastTv?
    fun videos(refused: Map<String, String>, sentHeights: Map<String, Int>)
}

internal class CastStatus(private val session: () -> MediaSession?) : CastTvPort {

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var tv: CastTv? = null
    private var refusals: Map<String, String> = emptyMap()
    private var sentHeights: Map<String, Int> = emptyMap()

    override fun current(): CastTv? = tv

    override fun videos(refused: Map<String, String>, sentHeights: Map<String, Int>) {
        main.post {
            refusals = refused
            this.sentHeights = sentHeights
            publish()
        }
    }

    fun connected(now: CastTv?) {
        tv = now
        if (now == null) {
            refusals = emptyMap()
            sentHeights = emptyMap()
        }
        publish()
    }

    private fun publish() {
        val extras = Bundle().apply {
            tv?.let { putString(EXTRA_CAST_DEVICE, it.name) }
            putBundle(EXTRA_CAST_REFUSED, Bundle().apply { refusals.forEach(::putString) })
            putBundle(EXTRA_CAST_SENT_HEIGHT, Bundle().apply { sentHeights.forEach(::putInt) })
        }
        session()?.setSessionExtras(extras)
    }

    companion object {
        const val EXTRA_CAST_DEVICE: String = "cast_device"
        const val EXTRA_CAST_REFUSED: String = "cast_refused"
        const val EXTRA_CAST_SENT_HEIGHT: String = "cast_sent_height"
    }
}
