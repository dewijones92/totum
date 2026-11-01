package com.dewijones92.totum.data.ask

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
public enum class AskKind(public val emoji: String, public val vetted: Boolean = true) {
    @SerialName("film")
    FILM("🎬"),

    @SerialName("series")
    SERIES("📺"),

    @SerialName("youtube")
    YOUTUBE("▶️"),

    @SerialName("podcast")
    PODCAST("🎙️"),

    @SerialName("audiobook")
    AUDIOBOOK("🎧"),

    @SerialName("song")
    SONG("🎵", vetted = false),

    @SerialName("album")
    ALBUM("💿", vetted = false),

    @SerialName("artist")
    ARTIST("🎤", vetted = false),

    @SerialName("radio")
    RADIO("📻", vetted = false),

    @SerialName("latest")
    LATEST("🆕", vetted = false),
}

@Serializable
public data class Taste(
    val channels: List<String> = emptyList(),
    val shows: List<String> = emptyList(),
    val artists: List<String> = emptyList(),
    val albums: List<String> = emptyList(),
    val songs: List<String> = emptyList(),
    val played: List<String> = emptyList(),
)

@Serializable
public data class Suggestion(
    val kind: AskKind,
    val title: String,
    val year: Int? = null,
    val why: String,
    val searchWords: String,
    val alreadyHave: Boolean = false,
) {
    val label: String get() = title + (year?.let { " ($it)" } ?: "")
}

@Serializable
public data class AskTurn(
    val request: String,
    val suggested: List<String> = emptyList(),
    val reply: String = "",
)

@Serializable
public data class AskRequest(
    val turns: List<AskTurn>,
    val have: List<String> = emptyList(),
    val taste: Taste = Taste(),
) {
    init {
        require(turns.isNotEmpty()) { "an ask needs a request" }
    }
}

@Serializable
public data class AskAnswer(val suggestions: List<Suggestion>, val reply: String = "")

@Serializable
public data class CopyToVet(val name: String, val sizeBytes: Long? = null, val detail: String? = null)

@Serializable
public data class VetItem(val suggestion: Suggestion, val copies: List<CopyToVet>)

@Serializable
public data class VetRequest(val items: List<VetItem>)

@Serializable
public data class Verdict(val item: Int, val copy: Int, val ok: Boolean, val reason: String)

@Serializable
public data class VetAnswer(val verdicts: List<Verdict>)

@Serializable
public enum class JobState {
    @SerialName("running")
    RUNNING,

    @SerialName("done")
    DONE,

    @SerialName("failed")
    FAILED,
}

@Serializable
public data class AskJob(
    val id: String,
    val state: JobState,
    val elapsedMs: Long = 0,
    val ask: AskAnswer? = null,
    val vet: VetAnswer? = null,
    val error: String? = null,
)

public fun AskAnswer.tidied(taste: Taste): AskAnswer = AskAnswer(
    suggestions = suggestions.map { it.tidied(taste) },
    reply = reply.replace(MARKDOWN_LINK) { it.groupValues[1] },
)

private fun Suggestion.tidied(taste: Taste): Suggestion {
    val dated = if (kind in DATED) this else copy(year = null)
    val followed = taste.shows.firstOrNull { it.squashed() == title.squashed() }
    return if (kind == AskKind.PODCAST && followed != null) {
        dated.copy(kind = AskKind.LATEST, title = followed, searchWords = followed)
    } else {
        dated
    }
}

public fun String.squashed(): String = lowercase().replace("&", "and").filter(Char::isLetterOrDigit)

private val DATED = setOf(AskKind.FILM, AskKind.SERIES, AskKind.AUDIOBOOK)
private val MARKDOWN_LINK = Regex("""\[([^\]]+)]\((https?://[^)\s]+)\)""")

public val AskJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}
