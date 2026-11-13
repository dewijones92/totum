package com.dewijones92.totum.diagnostics

import android.content.Context
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.ask.state.AppStateInputs
import com.dewijones92.totum.data.diagnostics.StateDump
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

internal class StateDumpSource(
    private val inputs: suspend () -> AppStateInputs,
    private val preferences: () -> Map<String, Map<String, Any?>>,
    private val installId: () -> String,
    private val work: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun gzipped(): ByteArray = withContext(work) {
        val began = System.currentTimeMillis()
        val built = StateDump.build(inputs(), preferences(), installId())
        val kept = (built.json["preferences"] as? JsonObject)?.size
        Diag.log(
            "diagnostics",
            "state dump built in ${System.currentTimeMillis() - began}ms: ${built.gzipped.size} bytes gzipped, " +
                "counts ${built.counts}, $kept preference file(s) kept, " +
                "${built.skippedFiles.size} credential file(s) left out ${built.skippedFiles}, " +
                "${built.droppedKeys} secret key(s) dropped, ${built.redactedValues} value(s) redacted",
        )
        built.gzipped
    }
}

internal fun preferencesOf(context: Context): Map<String, Map<String, Any?>> =
    File(context.dataDir, "shared_prefs").listFiles { file -> file.extension == "xml" }
        .orEmpty()
        .map { it.nameWithoutExtension }
        .sorted()
        .associateWith { name -> context.getSharedPreferences(name, Context.MODE_PRIVATE).all }
