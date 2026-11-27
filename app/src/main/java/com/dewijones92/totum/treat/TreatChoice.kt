package com.dewijones92.totum.treat

import android.content.Context
import com.dewijones92.totum.backup.BackupService
import com.dewijones92.totum.backup.withExtra
import com.dewijones92.totum.reminders.kit.JsonPrefs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class TreatEnd { SWITCH_OFF, PAUSE, STOP }

@Serializable
data class TreatChoice(
    val pinKey: String? = null,
    val targetId: String? = null,
    val targetLabel: String? = null,
    val minutes: Int = DEFAULT_MINUTES,
    val end: TreatEnd = TreatEnd.SWITCH_OFF,
    val fade: Boolean = true,
) {
    val complete: Boolean get() = !pinKey.isNullOrBlank() && !targetId.isNullOrBlank()

    val clamped: TreatChoice get() = copy(minutes = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES))

    companion object {
        const val DEFAULT_MINUTES = 15
        const val MIN_MINUTES = 5
        const val MAX_MINUTES = 90
        const val STEP_MINUTES = 5
    }
}

@Serializable
data class TreatRunning(
    val targetId: String? = null,
    val targetLabel: String? = null,
    val title: String? = null,
    val endsAtMs: Long = 0,
    val end: TreatEnd = TreatEnd.SWITCH_OFF,
) {
    val on: Boolean get() = !targetId.isNullOrBlank()
}

object TreatCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(choice: TreatChoice): String = json.encodeToString(TreatChoice.serializer(), choice)

    fun decode(text: String): TreatChoice = json.decodeFromString(TreatChoice.serializer(), text).clamped

    fun encodeRunning(running: TreatRunning): String = json.encodeToString(TreatRunning.serializer(), running)

    fun decodeRunning(text: String): TreatRunning = json.decodeFromString(TreatRunning.serializer(), text)
}

interface TreatStore {
    fun read(): TreatChoice
    fun write(choice: TreatChoice)
    fun readRunning(): TreatRunning
    fun writeRunning(running: TreatRunning)
}

class InMemoryTreatStore(
    private var choice: TreatChoice = TreatChoice(),
    private var running: TreatRunning = TreatRunning(),
) : TreatStore {
    override fun read(): TreatChoice = choice

    override fun write(choice: TreatChoice) {
        this.choice = choice
    }

    override fun readRunning(): TreatRunning = running

    override fun writeRunning(running: TreatRunning) {
        this.running = running
    }
}

class SharedPrefsTreatStore(context: Context) : TreatStore {
    private val prefs = JsonPrefs(context.applicationContext, "totum_treat", "treat")

    override fun read(): TreatChoice = prefs.read(KEY, TreatChoice(), TreatCodec::decode)

    override fun write(choice: TreatChoice) = prefs.write(KEY, TreatCodec.encode(choice))

    override fun readRunning(): TreatRunning = prefs.read(RUNNING, TreatRunning(), TreatCodec::decodeRunning)

    override fun writeRunning(running: TreatRunning) = prefs.write(RUNNING, TreatCodec.encodeRunning(running))

    private companion object {
        const val KEY = "choice"
        const val RUNNING = "running"
    }
}

internal fun BackupService.BackupSettings.withTreat(treat: Treat): BackupService.BackupSettings =
    withExtra(
        key = BACKUP_KEY,
        tag = "treat",
        what = "the treat button's defaults",
        read = { TreatCodec.encode(treat.choice.value) },
        apply = { text -> treat.restore(TreatCodec.decode(text)) },
    )

private const val BACKUP_KEY = "treat.v1"
