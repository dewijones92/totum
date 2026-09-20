package com.dewijones92.totum.diagnostics

import com.dewijones92.totum.domain.AccountProgressOutbox
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.ReconciledAccountProgress
import com.dewijones92.totum.video.ProgressOutboxDrain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * The account-side facts a report needs, kept current so capturing one never touches the database.
 *
 * [DiagnosticSnapshot.capture] runs on whatever thread just crashed, so every value it reads has
 * to be already in memory. These were accumulating as loose `@Volatile` fields and observer
 * wiring in `AppContainer`, which is neither where they belong nor somewhere anybody would look
 * for them.
 */
internal class AccountReportValues(
    private val outbox: AccountProgressOutbox,
    private val reconciled: ReconciledAccountProgress,
) {
    @Volatile
    private var pending: Int = -1

    @Volatile
    private var acted: Map<MediaItemId, Long> = emptyMap()

    @Volatile
    private var stuck: String = ""

    /** How many updates the account has not been told about yet. */
    fun pendingUpdates(): Int = pending

    /** The account figures already acted on, so a surprising resume can be re-judged. */
    fun actedOn(): Map<MediaItemId, Long> = acted

    /**
     * The outbox rows that keep failing, worst first.
     *
     * Nothing is ever dropped from the outbox any more, so "is anything permanently stuck, and
     * which?" is the one question the design's own risk raises — and a bare count cannot answer
     * it. Report 0.1.496 needed a code read to find that three ids were behind everything.
     */
    fun stuckUpdates(): String = stuck

    fun start(scope: CoroutineScope) {
        outbox.observePendingCount().onEach { pending = it }.launchIn(scope)
        reconciled.observeReconciled().onEach { acted = it }.launchIn(scope)
        outbox.observeStuck(ProgressOutboxDrain.STUBBORN_AFTER, WORST_IN_A_REPORT)
            .onEach { rows -> stuck = rows.joinToString(" | ") { "${it.itemId.value}×${it.attempts}" } }
            .launchIn(scope)
    }

    private companion object {
        /** Enough stuck rows to see a pattern, few enough not to crowd a bounded report buffer. */
        const val WORST_IN_A_REPORT = 5
    }
}
