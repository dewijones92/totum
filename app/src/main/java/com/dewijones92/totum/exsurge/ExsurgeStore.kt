package com.dewijones92.totum.exsurge

import android.content.Context
import androidx.core.content.edit
import com.dewijones92.totum.common.Diag
import java.io.File

class SharedPrefsExsurgeStore(context: Context) : ExsurgePersistence {
    private val prefs = context.getSharedPreferences("exsurge", Context.MODE_PRIVATE)
    private val outcomesFile = File(context.filesDir, "exsurge/outcomes.json")

    override fun loadSettings(): ExsurgeSettings =
        read("settings", ExsurgeSettings()) { ExsurgeCodec.decodeSettings(it) }

    override fun saveSettings(settings: ExsurgeSettings) =
        prefs.edit { putString("settings", ExsurgeCodec.encodeSettings(settings)) }

    override fun loadMemory(): ExsurgeMemory =
        read("memory", ExsurgeMemory()) { ExsurgeCodec.decodeMemory(it) }

    override fun saveMemory(memory: ExsurgeMemory) =
        prefs.edit { putString("memory", ExsurgeCodec.encodeMemory(memory)) }

    override fun loadOutcomes(): List<BreakOutcome> {
        if (!outcomesFile.exists()) return emptyList()
        return runCatching { ExsurgeCodec.decodeOutcomes(outcomesFile.readText()) }
            .onFailure {
                val kept = File(outcomesFile.parentFile, "outcomes.corrupt-${System.currentTimeMillis()}.json")
                val moved = outcomesFile.renameTo(kept)
                val note = "dewidebug exsurge outcomes unreadable; kept aside as ${kept.name} moved=$moved"
                Diag.warn(ExsurgeController.TAG, note, it)
            }
            .getOrDefault(emptyList())
    }

    override fun saveOutcomes(outcomes: List<BreakOutcome>) {
        runCatching {
            outcomesFile.parentFile?.mkdirs()
            val tmp = File(outcomesFile.parentFile, "outcomes.json.tmp")
            tmp.writeText(ExsurgeCodec.encodeOutcomes(outcomes))
            check(tmp.renameTo(outcomesFile)) { "rename failed" }
        }.onFailure { Diag.warn(ExsurgeController.TAG, "dewidebug exsurge outcomes not saved (${outcomes.size})", it) }
    }

    private fun <T> read(key: String, default: T, decode: (String) -> T): T {
        val text = prefs.getString(key, null) ?: return default
        return runCatching { decode(text) }
            .onFailure {
                Diag.warn(
                    ExsurgeController.TAG,
                    "dewidebug exsurge stored $key unreadable, using defaults",
                    it
                )
            }
            .getOrDefault(default)
    }
}

class InMemoryExsurgeStore(
    var settings: ExsurgeSettings = ExsurgeSettings(),
    var memory: ExsurgeMemory = ExsurgeMemory(),
    var outcomes: List<BreakOutcome> = emptyList(),
) : ExsurgePersistence {
    override fun loadSettings() = settings
    override fun saveSettings(settings: ExsurgeSettings) {
        this.settings = settings
    }
    override fun loadMemory() = memory
    override fun saveMemory(memory: ExsurgeMemory) {
        this.memory = memory
    }
    override fun loadOutcomes() = outcomes
    override fun saveOutcomes(outcomes: List<BreakOutcome>) {
        this.outcomes = outcomes
    }
}
