package com.dewijones92.totum.playback.cast

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.session.MediaSession
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.QualityLabel
import com.dewijones92.totum.playback.CastProblem
import com.dewijones92.totum.playback.CastStage

internal class CastStatus(private val session: () -> MediaSession?) : CastTvPort {

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var tv: CastTv? = null
    private var refusals: Map<String, String> = emptyMap()
    private var sent: Map<String, QualityLabel> = emptyMap()
    private var fetchingHeights: Map<String, Int> = emptyMap()
    private var droppedFrom: Map<String, Int> = emptyMap()
    private var problem: CastProblem? = null
    private var stage: Triple<String, CastStage, Int?>? = null
    private var screens: List<Screen> = emptyList()
    private var screenPage: String? = null

    override fun current(): CastTv? = tv

    override fun videos(
        refused: Map<String, String>,
        sent: Map<String, QualityLabel>,
        fetchingHeights: Map<String, Int>,
        droppedFrom: Map<String, Int>,
    ) {
        main.post {
            refusals = refused
            this.sent = sent
            this.fetchingHeights = fetchingHeights
            this.droppedFrom = droppedFrom
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

    override fun stage(mediaId: String, now: CastStage?, percent: Int?) {
        main.post {
            val next = now?.let { Triple(mediaId, it, percent) }
            if (next == null && stage?.first != mediaId) return@post
            if (stage == next) return@post
            if (stage?.second != next?.second) Diag.log("cast", "$mediaId: stage ${now ?: "done (the TV is fetching)"}")
            stage = next
            publish()
        }
    }

    fun screens(present: List<Screen>) {
        main.post {
            screens = present
            publish()
        }
    }

    fun screenPage(url: String?) {
        main.post {
            screenPage = url
            publish()
        }
    }

    fun connected(now: CastTv?) {
        tv = now
        if (now == null) {
            refusals = emptyMap()
            sent = emptyMap()
            fetchingHeights = emptyMap()
            droppedFrom = emptyMap()
            problem = null
            stage = null
        }
        publish()
    }

    private fun publish() {
        val extras = Bundle().apply {
            tv?.let { putString(EXTRA_CAST_DEVICE, it.name) }
            putBundle(EXTRA_CAST_REFUSED, Bundle().apply { refusals.forEach(::putString) })
            problem?.let { putString(EXTRA_CAST_PROBLEM, it.name) }
            stage?.let { (id, now, percent) ->
                putString(EXTRA_CAST_STAGE_ITEM, id)
                putString(EXTRA_CAST_STAGE, now.name)
                percent?.let { putInt(EXTRA_CAST_STAGE_PERCENT, it) }
            }
            putSentVideos()
            putBundle(EXTRA_CAST_FETCH_HEIGHT, Bundle().apply { fetchingHeights.forEach(::putInt) })
            putBundle(EXTRA_SCREENS, Bundle().apply { screens.forEach { putString(it.id, it.name) } })
            screenPage?.let { putString(EXTRA_SCREEN_PAGE, it) }
        }
        session()?.setSessionExtras(extras)
    }

    private fun Bundle.putSentVideos() {
        putBundle(EXTRA_CAST_SENT_HEIGHT, Bundle().apply { sent.forEach { (id, label) -> putInt(id, label.height) } })
        putBundle(EXTRA_CAST_SENT_CODEC, Bundle().apply { sent.forEach { (id, label) -> putFamily(id, label) } })
        putBundle(EXTRA_CAST_DROPPED_FROM, Bundle().apply { droppedFrom.forEach(::putInt) })
    }

    private fun Bundle.putFamily(id: String, label: QualityLabel) {
        label.family?.let { putString(id, it.name) }
    }

    companion object {
        const val EXTRA_CAST_DEVICE: String = "cast_device"
        const val EXTRA_CAST_REFUSED: String = "cast_refused"
        const val EXTRA_CAST_SENT_HEIGHT: String = "cast_sent_height"
        const val EXTRA_CAST_SENT_CODEC: String = "cast_sent_codec"
        const val EXTRA_CAST_DROPPED_FROM: String = "cast_dropped_from"
        const val EXTRA_CAST_PROBLEM: String = "cast_problem"
        const val EXTRA_CAST_STAGE: String = "cast_stage"
        const val EXTRA_CAST_STAGE_ITEM: String = "cast_stage_item"
        const val EXTRA_CAST_STAGE_PERCENT: String = "cast_stage_percent"
        const val EXTRA_CAST_FETCH_HEIGHT: String = "cast_fetch_height"
        const val EXTRA_SCREENS: String = "screens"
        const val EXTRA_SCREEN_PAGE: String = "screen_page"
    }
}
