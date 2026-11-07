package com.dewijones92.totum.data.morning

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.writeTextReplacing
import com.dewijones92.totum.data.ask.AskJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
public data class InboxEntry(
    val group: String,
    val itemId: String,
    val title: String,
    val shielded: Boolean,
    val at: Long,
)

@Serializable
public data class MorningState(
    val digest: Boolean = false,
    val forYou: Boolean = false,
    val inbox: List<InboxEntry> = emptyList(),
    val picked: Map<String, Long> = emptyMap(),
    val lastDigestAt: Long = 0,
)

public interface MorningFile {
    public fun load(): MorningState

    public fun save(state: MorningState)
}

public class InMemoryMorningFile(private var held: MorningState = MorningState()) : MorningFile {
    override fun load(): MorningState = held

    override fun save(state: MorningState) {
        held = state
    }
}

public class FileMorningFile(private val file: File) : MorningFile {
    override fun load(): MorningState = runCatching {
        if (file.exists()) AskJson.decodeFromString(MorningState.serializer(), file.readText()) else MorningState()
    }.getOrElse {
        Diag.warn(TAG, "the morning file could not be read, starting with both features off: $it")
        MorningState()
    }

    override fun save(state: MorningState) {
        runCatching { file.writeTextReplacing(AskJson.encodeToString(MorningState.serializer(), state)) }
            .onFailure { Diag.warn(TAG, "the morning file could not be saved: $it") }
    }
}

public class MorningStore(private val file: MorningFile, private val clock: () -> Long = System::currentTimeMillis) {
    private val _state = MutableStateFlow(file.load())
    public val state: StateFlow<MorningState> = _state.asStateFlow()

    public val digestOn: Boolean get() = _state.value.digest

    public fun setDigest(on: Boolean): Unit = change("digest ${if (on) "on" else "off"}") { it.copy(digest = on) }

    public fun setForYou(on: Boolean): Unit = change("for you ${if (on) "on" else "off"}") { it.copy(forYou = on) }

    public fun addToInbox(entry: InboxEntry) {
        change("inbox + \"${entry.title}\" (${entry.group})") { state ->
            val fresh = state.inbox.filterNot { it.itemId == entry.itemId } + entry
            val recent = fresh.filter { clock() - it.at <= KEEP_INBOX_MS }
            state.copy(inbox = recent.takeLast(MAX_INBOX))
        }
    }

    public fun consumed(entries: List<InboxEntry>, at: Long) {
        val gone = entries.mapTo(mutableSetOf()) { it.itemId }
        change("inbox emptied of ${entries.size}") { state ->
            state.copy(inbox = state.inbox.filterNot { it.itemId in gone }, lastDigestAt = at)
        }
    }

    public fun picked(titles: List<String>, at: Long) {
        change("remembered ${titles.size} pick(s)") { state ->
            val kept = state.picked.filterValues { at - it <= KEEP_PICKS_MS }
            state.copy(picked = kept + titles.associateWith { at })
        }
    }

    public fun pickedLately(now: Long): List<String> =
        _state.value.picked.filterValues { now - it <= LATELY_MS }.keys.toList()

    private fun change(why: String, edit: (MorningState) -> MorningState) {
        _state.update(edit)
        file.save(_state.value)
        val s = _state.value
        Diag.log(TAG, "$why [digest=${s.digest} forYou=${s.forYou} inbox=${s.inbox.size} picked=${s.picked.size}]")
    }

    private companion object {
        const val KEEP_INBOX_MS = 3L * 24 * 60 * 60 * 1000
        const val KEEP_PICKS_MS = 30L * 24 * 60 * 60 * 1000
        const val LATELY_MS = 14L * 24 * 60 * 60 * 1000
        const val MAX_INBOX = 300
    }
}

private const val TAG = "morning"
