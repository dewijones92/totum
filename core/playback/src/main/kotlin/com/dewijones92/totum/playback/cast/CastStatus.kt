package com.dewijones92.totum.playback.cast

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.session.MediaSession
import com.dewijones92.totum.domain.CastScreen
import com.dewijones92.totum.playback.CastProblem

internal class CastTv(val name: String, val screen: CastScreen)

internal interface CastTvPort {
    fun current(): CastTv?
    fun videos(refused: Map<String, String>, sentHeights: Map<String, Int>, fetchingHeights: Map<String, Int>)
    fun problem(now: CastProblem?)
}

internal class CastStatus(private val session: () -> MediaSession?) : CastTvPort {

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var tv: CastTv? = null
    private var refusals: Map<String, String> = emptyMap()
    private var sentHeights: Map<String, Int> = emptyMap()
    private var fetchingHeights: Map<String, Int> = emptyMap()
    private var problem: CastProblem? = null

    override fun current(): CastTv? = tv

    override fun videos(
        refused: Map<String, String>,
        sentHeights: Map<String, Int>,
        fetchingHeights: Map<String, Int>,
    ) {
        main.post {
            refusals = refused
            this.sentHeights = sentHeights
            this.fetchingHeights = fetchingHeights
            publish()
        }
    }

    override fun problem(now: CastProblem?) {
        main.post {
            if (problem == now) return@post
            problem = now
            publish()
        }
    }

    fun connected(now: CastTv?) {
        tv = now
        if (now == null) {
            refusals = emptyMap()
            sentHeights = emptyMap()
            fetchingHeights = emptyMap()
            problem = null
        }
        publish()
    }

    private fun publish() {
        val extras = Bundle().apply {
            tv?.let { putString(EXTRA_CAST_DEVICE, it.name) }
            putBundle(EXTRA_CAST_REFUSED, Bundle().apply { refusals.forEach(::putString) })
            problem?.let { putString(EXTRA_CAST_PROBLEM, it.name) }
            putBundle(EXTRA_CAST_SENT_HEIGHT, Bundle().apply { sentHeights.forEach(::putInt) })
            putBundle(EXTRA_CAST_FETCH_HEIGHT, Bundle().apply { fetchingHeights.forEach(::putInt) })
        }
        session()?.setSessionExtras(extras)
    }

    companion object {
        const val EXTRA_CAST_DEVICE: String = "cast_device"
        const val EXTRA_CAST_REFUSED: String = "cast_refused"
        const val EXTRA_CAST_SENT_HEIGHT: String = "cast_sent_height"
        const val EXTRA_CAST_PROBLEM: String = "cast_problem"
        const val EXTRA_CAST_FETCH_HEIGHT: String = "cast_fetch_height"
    }
}
