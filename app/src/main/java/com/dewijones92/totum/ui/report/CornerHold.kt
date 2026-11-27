package com.dewijones92.totum.ui.report

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.diagnostics.quick.CornerHoldRecogniser
import com.dewijones92.totum.diagnostics.quick.CornerHoldRecogniser.Verdict
import com.dewijones92.totum.diagnostics.quick.CornerZone

internal val CORNER_ZONE_SIZE: Dp = 48.dp
private const val LOG_ABANDONED_AFTER_MS = 400L

internal fun Modifier.cornerHold(
    enabled: Boolean,
    topInsetPx: Int,
    zoneSize: Dp = CORNER_ZONE_SIZE,
    onHeld: () -> Unit,
): Modifier =
    pointerInput(enabled, zoneSize, topInsetPx) {
        if (!enabled) return@pointerInput
        val zone = CornerZone.topRight(size.width.toFloat(), zoneSize.toPx(), topInsetPx.toFloat())
        val recogniser = CornerHoldRecogniser(zone::contains, slopPx = viewConfiguration.touchSlop)
        awaitPointerEventScope {
            var lastAtMs = 0L
            while (true) {
                val due = recogniser.dueAtMs()
                val event = if (due == null) {
                    awaitPointerEvent(PointerEventPass.Initial)
                } else {
                    withTimeoutOrNull((due - lastAtMs).coerceAtLeast(1L)) {
                        awaitPointerEvent(PointerEventPass.Initial)
                    }
                }
                if (event == null) {
                    due?.let { fired(recogniser.tick(it), onHeld) }
                } else {
                    event.changes.firstOrNull()?.let { lastAtMs = it.uptimeMillis }
                    follow(event, recogniser, onHeld)
                }
            }
        }
    }

private fun follow(event: PointerEvent, recogniser: CornerHoldRecogniser, onHeld: () -> Unit) {
    event.changes.forEach { change ->
        val id = change.id.value
        val at = change.uptimeMillis
        val owned = recogniser.owns(id)
        val verdict = when {
            change.changedToDownIgnoreConsumed() -> recogniser.down(id, change.position.x, change.position.y, at)
            change.changedToUpIgnoreConsumed() -> recogniser.up(id, at)
            change.positionChanged() -> recogniser.move(id, change.position.x, change.position.y, at)
            else -> Verdict.Idle
        }
        fired(verdict, onHeld)
        if (owned || recogniser.owns(id)) change.consume()
    }
}

private fun fired(verdict: Verdict, onHeld: () -> Unit) {
    when (verdict) {
        Verdict.Fired -> {
            Diag.log("diagnostics", "dewidebug corner hold: held the top-right corner, opening a report")
            onHeld()
        }
        is Verdict.Abandoned -> if (verdict.heldMs >= LOG_ABANDONED_AFTER_MS) {
            val needed = CornerHoldRecogniser.HOLD_MS
            Diag.log(
                "diagnostics",
                "dewidebug corner hold: not a report (${verdict.why} after ${verdict.heldMs} ms of $needed)",
            )
        }
        is Verdict.Holding, Verdict.Idle -> Unit
    }
}
