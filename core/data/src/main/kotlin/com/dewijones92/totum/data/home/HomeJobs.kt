package com.dewijones92.totum.data.home

import com.dewijones92.totum.data.backup.BackupItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class PhoneDecoder(val codec: String, val maxHeight: Int, val hardware: Boolean = false)

@Serializable
public data class HomeJobRequest(
    val item: BackupItem,
    val audioOnly: Boolean,
    val maxHeight: Int? = null,
    val decoders: List<PhoneDecoder> = emptyList(),
)

@Serializable
public enum class HomeJobState {
    @SerialName("waiting")
    WAITING,

    @SerialName("downloading")
    DOWNLOADING,

    @SerialName("done")
    DONE,

    @SerialName("failed")
    FAILED,
}

@Serializable
public data class HomeJob(
    val id: String,
    val item: BackupItem,
    val state: HomeJobState,
    val audioOnly: Boolean,
    val bytes: Long = 0,
    val totalBytes: Long? = null,
    val error: String? = null,
)

@Serializable
public enum class HomeSeverity {
    @SerialName("warning")
    WARNING,

    @SerialName("broken")
    BROKEN,
}

@Serializable
public data class HomeProblem(val key: String, val severity: HomeSeverity, val text: String)

@Serializable
public data class HomeJobs(
    val jobs: List<HomeJob>,
    val freeBytes: Long,
    val youTubeSignedIn: Boolean,
    val problems: List<HomeProblem> = emptyList(),
)

@Serializable
public data class AccountHandover(val accessToken: String, val refreshToken: String, val expiresAtEpochSeconds: Long)
