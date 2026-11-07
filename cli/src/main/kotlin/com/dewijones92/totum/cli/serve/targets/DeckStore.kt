package com.dewijones92.totum.cli.serve.targets

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.writeTextReplacing
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.data.targets.TargetsJson
import java.io.File

internal class DeckStore(private val file: File) {

    fun save(state: TargetState, id: String) {
        runCatching { file.writeTextReplacing(TargetsJson.encodeToString(TargetState.serializer(), state)) }
            .onFailure { Diag.warn("targets", "$id: queue not saved: $it") }
    }

    fun restore(id: String): TargetState? {
        if (!file.exists()) return null
        return runCatching { TargetsJson.decodeFromString(TargetState.serializer(), file.readText()) }
            .onFailure { Diag.warn("targets", "$id: saved queue unreadable, starting empty: $it") }
            .getOrNull()
    }
}
