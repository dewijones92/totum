package com.dewijones92.totum.domain

public enum class TransferPlace { WEB, PHONE, PI }

public enum class TransferPhase { WAITING, RUNNING, FAILED }

public data class Transfer(
    val from: TransferPlace,
    val to: TransferPlace,
    val phase: TransferPhase,
    val percent: Int? = null,
    val reason: String? = null,
) {
    init {
        require(from != to) { "a transfer goes somewhere: $from -> $to" }
        require(percent == null || percent in PERCENT_RANGE) { "percent out of range: $percent" }
    }

    public companion object {
        private val PERCENT_RANGE = 0..PERCENT

        public fun running(from: TransferPlace, to: TransferPlace, percent: Int?): Transfer =
            Transfer(from, to, TransferPhase.RUNNING, percent)

        public fun waiting(from: TransferPlace, to: TransferPlace): Transfer =
            Transfer(from, to, TransferPhase.WAITING)

        public fun failed(from: TransferPlace, to: TransferPlace, reason: String?): Transfer =
            Transfer(from, to, TransferPhase.FAILED, reason = reason)
    }
}

public fun percentOf(done: Long, total: Long?): Int? =
    total?.takeIf { it > 0 }?.let { (done.coerceAtLeast(0) * PERCENT / it).toInt().coerceIn(0, PERCENT) }

public fun DownloadState.Downloading.transfer(fromPi: Boolean): Transfer = Transfer.running(
    if (fromPi) TransferPlace.PI else TransferPlace.WEB,
    TransferPlace.PHONE,
    percentOf(downloadedBytes, totalBytes),
)

private const val PERCENT = 100
