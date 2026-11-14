package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.CastScreen
import com.dewijones92.totum.domain.QualityLabel
import com.dewijones92.totum.playback.CastProblem
import com.dewijones92.totum.playback.CastStage
import com.dewijones92.totum.playback.cast.CastHostPort
import com.dewijones92.totum.playback.cast.CastTv
import com.dewijones92.totum.playback.cast.CastTvPort
import com.dewijones92.totum.playback.cast.MediaTools
import java.util.concurrent.ConcurrentHashMap

internal class PiCastTv(
    val label: String,
    private val screen: () -> CastScreen? = { null },
    private val model: () -> String?,
) {
    @Volatile var notice: String? = null

    fun asCastTv(): CastTv = CastTv(label, screen() ?: CastScreen.of(model()))
}

internal class PiCastTvs : CastTvPort {
    private val byMedia = ConcurrentHashMap<String, PiCastTv>()

    @Volatile private var lastMedia: String? = null

    fun expect(mediaId: String, tv: PiCastTv) {
        byMedia[mediaId] = tv
        lastMedia = mediaId
    }

    fun forget(mediaId: String) {
        byMedia.remove(mediaId)
    }

    override fun current(): CastTv? = lastMedia?.let(byMedia::get)?.asCastTv()

    override fun current(mediaId: String): CastTv? = byMedia[mediaId]?.asCastTv()

    override fun videos(
        refused: Map<String, String>,
        sent: Map<String, QualityLabel>,
        fetchingHeights: Map<String, Int>,
        droppedFrom: Map<String, Int>,
    ) {
        byMedia.forEach { (mediaId, tv) ->
            val what = refused[mediaId]
            if (what != null) {
                tv.notice = "📺 ${tv.label} gets sound only: its video is $what, beyond what ${tv.label} can show"
                Diag.warn("cast-target", "${tv.label}: ${tv.notice}")
            } else if (mediaId in sent && tv.notice?.startsWith("📺") == true) {
                tv.notice = null
            }
            sent[mediaId]?.let { Diag.log("cast-target", "${tv.label}: sending ${it.text}") }
            droppedFrom[mediaId]?.let { Diag.log("cast-target", "${tv.label}: stepped down from ${it}p") }
        }
    }

    override fun problem(now: CastProblem?) {
        val tv = lastMedia?.let(byMedia::get) ?: return
        tv.notice = now?.let(::textOf)
        Diag.log("cast-target", "${tv.label}: cast problem now ${now ?: "none"}")
    }

    override fun failed(mediaId: String, reason: String?) {
        val tv = byMedia[mediaId] ?: return
        tv.notice = reason?.let { "❌ The TV couldn't start it: $it" }
        Diag.log("cast-target", "${tv.label}: failure notice ${reason ?: "cleared"}")
    }

    override fun stage(mediaId: String, now: CastStage?, percent: Int?) {
        val tv = byMedia[mediaId] ?: return
        Diag.log("cast-target", "${tv.label}: stage ${now ?: "flowing"}${percent?.let { " $it%" }.orEmpty()}")
    }

    private fun textOf(problem: CastProblem): String = when (problem) {
        CastProblem.NO_ADDRESS -> "⚠️ The Pi has no address the TV can fetch from (TOTUM_CAST_BASE)"
        CastProblem.TV_NOT_FETCHING -> "⚠️ The TV is not fetching from the Pi"
        CastProblem.VPN_IN_THE_WAY -> "⚠️ A VPN may be in the way of the TV reaching the Pi"
        CastProblem.SOUND_UNDECODABLE -> "⚠️ The Pi could not decode this item's sound"
    }
}

internal class PiCastHost(private val base: String?) : CastHostPort {
    override val vpnInTheWay: Boolean = false

    override fun baseUrl(port: Int): String? = base?.trimEnd('/')

    override fun hold(active: Boolean) = Unit
}

internal fun piMediaTools(workDir: java.io.File): MediaTools = MediaTools(
    ffmpeg = java.io.File(System.getenv("TOTUM_FFMPEG_PATH") ?: "/usr/bin/ffmpeg"),
    ffprobe = java.io.File(System.getenv("TOTUM_FFPROBE_PATH") ?: "/usr/bin/ffprobe"),
    cacheDir = java.io.File(workDir, "cache"),
    keepDir = workDir,
)
