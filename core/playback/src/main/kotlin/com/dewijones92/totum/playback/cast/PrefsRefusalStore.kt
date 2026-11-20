package com.dewijones92.totum.playback.cast

import android.content.Context
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.VideoCodecFamily

internal class PrefsRefusalStore(context: Context) : RefusalStore {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load(): Map<String, Set<VideoCodecFamily>> = prefs.all.mapNotNull { (model, value) ->
        val families = (value as? Set<*>).orEmpty().mapNotNull { name ->
            VideoCodecFamily.entries.firstOrNull { it.name == name }
        }.toSet()
        families.takeIf { it.isNotEmpty() }?.let { model to it }
    }.toMap()

    override fun save(refusals: Map<String, Set<VideoCodecFamily>>) {
        prefs.edit().apply {
            clear()
            refusals.forEach { (model, families) -> putStringSet(model, families.map { it.name }.toSet()) }
        }.apply()
        Diag.log("cast", "dewidebug codec refusals saved: ${refusals.mapValues { (_, v) -> v.map { it.name } }}")
    }

    private companion object {
        const val FILE = "totum_cast_codec_refusals"
    }
}
