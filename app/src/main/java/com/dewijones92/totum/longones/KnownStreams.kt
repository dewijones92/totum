package com.dewijones92.totum.longones

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.kept.KeptFacts
import java.io.File

class KnownStreams(file: File) {
    private val kept = KeptFacts(file, "known live streams", TAG)

    suspend fun has(id: String): Boolean = kept.get(id) != null

    suspend fun keep(ids: Collection<String>, via: String): Int {
        val fresh = ids.distinct().filter { kept.get(it) == null }
        if (fresh.isEmpty()) return 0
        kept.put(fresh.associateWith { via })
        Diag.log(
            TAG,
            "dewidebug kept ${fresh.size} live stream(s) as never long ones ($via): " +
                "${fresh.take(LOGGED)}; ${kept.size()} kept in all",
        )
        return fresh.size
    }

    private companion object {
        const val TAG = "longones"
        const val LOGGED = 5
    }
}
