package com.dewijones92.totum.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Whether a load failure is SABR saying it will not serve this track again.
 *
 * A named rule rather than a line inside the policy, because the policy itself cannot be unit tested
 * here: building a `LoadErrorInfo` needs a `DataSpec`, which needs `android.net.Uri`, which is null on
 * a plain JVM -- so a test of the policy fails for every input including the ones that should pass.
 * The decision is the part worth guarding, so it lives where a test can reach it.
 *
 * Walks the cause chain because Media3 wraps a `DataSource.open` failure before the policy sees it,
 * and walks it a BOUNDED number of times so a self-referential chain cannot hang the loader thread.
 */
internal fun Throwable.isSabrGivingUp(): Boolean {
    var at: Throwable? = this
    var depth = 0
    while (at != null && depth < MAX_CAUSE_DEPTH) {
        if (at is SabrGaveUpException) return true
        at = at.cause.takeIf { it !== at }
        depth++
    }
    return false
}

private const val MAX_CAUSE_DEPTH = 10

/**
 * Media3's ordinary retry policy, except that it never retries a track SABR has abandoned.
 *
 * Everything else keeps the default behaviour deliberately: a timeout, a 5xx and a dropped connection
 * are all worth another go, and they are most of what this policy is for. `C.TIME_UNSET` is how the
 * interface spells "do not retry" -- the error surfaces at once, which is what reaches `StreamRecovery`
 * and gets the item re-resolved onto extraction.
 */
@UnstableApi
internal class DoNotRetryWhatSabrHasGivenUpOn : DefaultLoadErrorHandlingPolicy() {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if (loadErrorInfo.exception.isSabrGivingUp()) {
            C.TIME_UNSET
        } else {
            super.getRetryDelayMsFor(loadErrorInfo)
        }
}
