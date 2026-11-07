package com.dewijones92.totum.kidlock

import android.content.Context
import com.dewijones92.totum.backup.BackupService
import com.dewijones92.totum.backup.withExtra
import com.dewijones92.totum.reminders.kit.JsonPrefs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class KidTvChoice(val pinKey: String? = null, val tvName: String? = null) {
    val complete: Boolean get() = !pinKey.isNullOrBlank() && !tvName.isNullOrBlank()
}

@Serializable
data class KidTvRunning(val tv: String? = null, val title: String? = null) {
    val on: Boolean get() = !tv.isNullOrBlank()
}

object KidTvCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(choice: KidTvChoice): String = json.encodeToString(KidTvChoice.serializer(), choice)

    fun decode(text: String): KidTvChoice = json.decodeFromString(KidTvChoice.serializer(), text)

    fun encodeRunning(running: KidTvRunning): String = json.encodeToString(KidTvRunning.serializer(), running)

    fun decodeRunning(text: String): KidTvRunning = json.decodeFromString(KidTvRunning.serializer(), text)
}

interface KidTvStore {
    fun read(): KidTvChoice
    fun write(choice: KidTvChoice)
    fun readRunning(): KidTvRunning
    fun writeRunning(running: KidTvRunning)
}

class InMemoryKidTvStore(
    private var choice: KidTvChoice = KidTvChoice(),
    private var running: KidTvRunning = KidTvRunning(),
) : KidTvStore {
    override fun read(): KidTvChoice = choice

    override fun write(choice: KidTvChoice) {
        this.choice = choice
    }

    override fun readRunning(): KidTvRunning = running

    override fun writeRunning(running: KidTvRunning) {
        this.running = running
    }
}

class SharedPrefsKidTvStore(context: Context) : KidTvStore {
    private val prefs = JsonPrefs(context.applicationContext, "totum_kid_tv", "kidlock")

    override fun read(): KidTvChoice = prefs.read(KEY, KidTvChoice(), KidTvCodec::decode)

    override fun write(choice: KidTvChoice) = prefs.write(KEY, KidTvCodec.encode(choice))

    override fun readRunning(): KidTvRunning = prefs.read(RUNNING, KidTvRunning(), KidTvCodec::decodeRunning)

    override fun writeRunning(running: KidTvRunning) = prefs.write(RUNNING, KidTvCodec.encodeRunning(running))

    private companion object {
        const val KEY = "choice"
        const val RUNNING = "running"
    }
}

internal fun BackupService.BackupSettings.withKidTv(kidTv: KidTv): BackupService.BackupSettings =
    withExtra(
        key = BACKUP_KEY,
        tag = "kidlock",
        what = "the Kid TV choice",
        read = { KidTvCodec.encode(kidTv.choice.value) },
        apply = { text -> kidTv.restore(KidTvCodec.decode(text)) },
    )

private const val BACKUP_KEY = "kidtv.v1"
