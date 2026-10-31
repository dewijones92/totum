package com.dewijones92.totum.data.ask

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
public enum class AskKind(public val emoji: String) {
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
}

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
public data class AskTurn(val request: String, val suggested: List<String> = emptyList())

@Serializable
public data class AskRequest(val turns: List<AskTurn>, val have: List<String> = emptyList()) {
    init {
        require(turns.isNotEmpty()) { "an ask needs a request" }
    }
}

@Serializable
public data class AskAnswer(val suggestions: List<Suggestion>)

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

public val AskJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}
