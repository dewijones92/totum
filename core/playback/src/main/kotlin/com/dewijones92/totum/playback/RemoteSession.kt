package com.dewijones92.totum.playback

import com.dewijones92.totum.common.Diag
import java.util.concurrent.atomic.AtomicReference

public data class RemoteItem(
    val queueIndex: Int,
    val id: String,
    val title: String,
    val author: String?,
    val artworkUrl: String?,
)

public data class RemoteFacts(
    val deviceId: String,
    val deviceLabel: String,
    val statusLine: String,
    val colour: Long,
    val previous: RemoteItem?,
    val current: RemoteItem,
    val next: RemoteItem?,
    val playing: Boolean,
    val starting: Boolean,
    val positionMs: Long,
    val durationMs: Long?,
)

public sealed interface RemoteCommand {
    public data object Play : RemoteCommand
    public data object Pause : RemoteCommand
    public data object Next : RemoteCommand
    public data object Previous : RemoteCommand
    public data class SeekTo(val positionMs: Long) : RemoteCommand
}

public fun interface RemoteCommands {
    public fun send(command: RemoteCommand)
}

public object RemoteSessionHost {

    private val shown = AtomicReference<RemoteFacts?>(null)

    @Volatile
    private var listener: ((RemoteFacts?) -> Unit)? = null

    @Volatile
    public var commands: RemoteCommands =
        RemoteCommands { Diag.warn(TAG, "dewidebug $it was asked of the selected device but nothing is listening") }

    public val current: RemoteFacts? get() = shown.get()

    public fun publish(facts: RemoteFacts?) {
        shown.set(facts)
        listener?.invoke(facts)
    }

    internal fun attach(onFacts: (RemoteFacts?) -> Unit): RemoteFacts? {
        listener = onFacts
        return shown.get()
    }

    internal fun detach() {
        listener = null
    }

    public const val HINT_REMOTE_SESSION: String = "com.dewijones92.totum.REMOTE_SESSION"

    private const val TAG = "remote-session"
}
