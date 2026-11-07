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

object KidTvCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(choice: KidTvChoice): String = json.encodeToString(KidTvChoice.serializer(), choice)

    fun decode(text: String): KidTvChoice = json.decodeFromString(KidTvChoice.serializer(), text)
}

interface KidTvStore {
    fun read(): KidTvChoice
    fun write(choice: KidTvChoice)
}

class InMemoryKidTvStore(private var choice: KidTvChoice = KidTvChoice()) : KidTvStore {
    override fun read(): KidTvChoice = choice

    override fun write(choice: KidTvChoice) {
        this.choice = choice
    }
}

class SharedPrefsKidTvStore(context: Context) : KidTvStore {
    private val prefs = JsonPrefs(context.applicationContext, "totum_kid_tv", "kidlock")

    override fun read(): KidTvChoice = prefs.read(KEY, KidTvChoice(), KidTvCodec::decode)

    override fun write(choice: KidTvChoice) = prefs.write(KEY, KidTvCodec.encode(choice))

    private companion object {
        const val KEY = "choice"
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
