package com.dewijones92.totum.data.phone.google

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.phone.ImportedPhoneMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

public sealed interface PickerState {
    public data object NeedsSetup : PickerState

    public data object SignedOut : PickerState

    public data object Ready : PickerState

    public data object SigningIn : PickerState

    public data object CreatingSession : PickerState

    public data class WaitingForPick(val pickerUri: String, val giveUpAtMs: Long?) : PickerState

    public data class ReadingPicks(val found: Int) : PickerState

    public data class Importing(val done: Int, val total: Int, val current: String?) : PickerState

    public data class Finished(val imported: Int, val alreadyHad: Int, val failed: List<String>) : PickerState

    public data class Failed(val reason: String) : PickerState

    public data object Expired : PickerState

    public data object Cancelled : PickerState

    public val busy: Boolean
        get() = this is SigningIn || this is CreatingSession || this is WaitingForPick || this is ReadingPicks ||
            this is Importing
}

public fun interface GoogleSignInPort {
    public suspend fun signIn(): GoogleSignIn
}

public fun interface BrowserOpener {
    public fun open(url: String): Boolean
}

public fun interface PickProgress {
    public fun report(found: Int?, step: Int?, steps: Int?)
}

public class GooglePhotosPicker(
    private val config: GooglePhotosConfig,
    private val oauth: GoogleOAuthClient,
    private val signInPort: GoogleSignInPort,
    private val api: PhotosPickerApi,
    imports: ImportedPhoneMedia,
    private val browser: BrowserOpener,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val importer = PickedImporter(api, imports, clock)
    private val mutable = MutableStateFlow<PickerState>(initial())
    public val state: StateFlow<PickerState> = mutable.asStateFlow()

    private fun initial(): PickerState = when {
        !config.isSetUp -> PickerState.NeedsSetup
        !oauth.signedIn() -> PickerState.SignedOut
        else -> PickerState.Ready
    }

    public fun refresh() {
        if (mutable.value.busy) return
        val next = initial()
        if (next != mutable.value && mutable.value !is PickerState.Finished) {
            Diag.log("google-photos", "state ${mutable.value::class.simpleName} -> ${next::class.simpleName}")
            mutable.value = next
        }
    }

    public fun dismissResult() {
        if (!mutable.value.busy) mutable.value = initial()
    }

    public fun signOut() {
        oauth.signOut()
        mutable.value = initial()
    }

    private fun set(next: PickerState) {
        Diag.log("google-photos", "state ${mutable.value::class.simpleName} -> $next")
        mutable.value = next
    }

    private fun finish(end: PickerState): PickerState {
        set(end)
        return end
    }

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    public suspend fun pick(progress: PickProgress? = null): PickerState {
        refusalToStart()?.let { return it }
        var session: PickerSession? = null
        try {
            signInIfNeeded()?.let { return finish(it) }
            set(PickerState.CreatingSession)
            val created = when (val answer = api.createSession()) {
                is PickerCall.Ok -> answer.value
                is PickerCall.No -> return finish(PickerState.Failed(answer.reason))
            }
            session = created
            return waitAndImport(created, progress)
        } catch (cancelled: CancellationException) {
            mutable.value = PickerState.Cancelled
            throw cancelled
        } catch (failure: Exception) {
            Diag.warn("google-photos", "the pick failed", failure)
            return finish(PickerState.Failed("❌ ${failure.message ?: failure.javaClass.simpleName}"))
        } finally {
            session?.let { endQuietly(it.id) }
        }
    }

    private fun refusalToStart(): PickerState? = when {
        !config.isSetUp -> finish(PickerState.NeedsSetup)
        mutable.value.busy -> {
            Diag.log("google-photos", "a pick is already running; not starting a second one")
            mutable.value
        }
        else -> null
    }

    private suspend fun signInIfNeeded(): PickerState.Failed? {
        if (oauth.accessToken() != null) return null
        set(PickerState.SigningIn)
        return when (val signedIn = signInPort.signIn()) {
            GoogleSignIn.Done -> null
            is GoogleSignIn.Failed -> PickerState.Failed(signedIn.reason)
        }
    }

    private suspend fun waitAndImport(session: PickerSession, progress: PickProgress?): PickerState {
        val uri = session.pickerUri ?: return finish(PickerState.Failed("❌ Google gave no page to pick from"))
        val limit = PickerDurations.millis(session.pollingConfig?.timeoutIn)?.takeIf { it > 0 }
            ?.let { clock() + it }
            ?: PickerDurations.expiryMs(session.expireTime)
        if (!browser.open(uri)) return finish(PickerState.Failed("🌐 No browser could open Google Photos"))
        set(PickerState.WaitingForPick(uri, limit))
        if (!waitForPick(session, limit)) return mutable.value
        set(PickerState.ReadingPicks(0))
        val items = when (
            val read = importer.readAll(session.id) { found ->
                set(PickerState.ReadingPicks(found))
                progress?.report(found, null, null)
            }
        ) {
            is PickerCall.Ok -> read.value
            is PickerCall.No -> return finish(PickerState.Failed(read.reason))
        }
        val done = importer.importAll(items) { index, total, name ->
            set(PickerState.Importing(index, total, name))
            progress?.report(total, index, total)
        }
        progress?.report(items.size, items.size, items.size)
        return finish(done)
    }

    private suspend fun endQuietly(id: String) {
        runCatching { withContext(NonCancellable) { api.endSession(id) } }
            .onFailure { Diag.warn("google-photos", "the picker session $id could not be ended", it) }
    }

    private suspend fun waitForPick(first: PickerSession, giveUpAtMs: Long?): Boolean {
        var current = first
        var failures = 0
        var interval = intervalOf(first, DEFAULT_POLL_MS)
        while (!current.mediaItemsSet) {
            if (passed(giveUpAtMs)) return ended(PickerState.Expired)
            pause(interval)
            when (val answer = api.session(first.id)) {
                is PickerCall.Ok -> {
                    failures = 0
                    current = answer.value
                    interval = intervalOf(current, interval)
                    if (!current.mediaItemsSet && passed(PickerDurations.expiryMs(current.expireTime))) {
                        return ended(PickerState.Expired)
                    }
                }
                is PickerCall.No -> {
                    failures++
                    Diag.warn("google-photos", "polling failed ($failures of $MAX_POLL_FAILURES): ${answer.reason}")
                    if (failures >= MAX_POLL_FAILURES || answer.httpCode in FINAL_CODES) {
                        return ended(PickerState.Failed(answer.reason))
                    }
                }
            }
        }
        Diag.log("google-photos", "the picks are ready after ${first.id.take(ID_SHOWN)}…")
        return true
    }

    private fun ended(state: PickerState): Boolean {
        set(state)
        return false
    }

    private fun passed(atMs: Long?): Boolean = atMs != null && clock() >= atMs

    private companion object {
        const val DEFAULT_POLL_MS = 3_000L
        const val ID_SHOWN = 8
        const val MAX_POLL_FAILURES = 3
        val FINAL_CODES = setOf(401, 403)
    }
}

private const val MIN_POLL_MS = 1_000L

private fun intervalOf(session: PickerSession, fallback: Long): Long =
    PickerDurations.millis(session.pollingConfig?.pollInterval)?.coerceAtLeast(MIN_POLL_MS) ?: fallback
