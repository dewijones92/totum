package com.dewijones92.totum.domain

import java.io.File

public sealed interface TeeOutcome {
    public data class Completed(val bytes: Long, val teeNetworkBytes: Long, val streamNetworkBytes: Long) : TeeOutcome

    public data class Failed(val why: String) : TeeOutcome
}

public interface StreamTee {

    public fun canTee(itemId: String): Boolean

    public suspend fun complete(
        itemId: String,
        target: File,
        progress: (heldBytes: Long, totalBytes: Long) -> Unit,
    ): TeeOutcome
}
