package com.dewijones92.totum.data.spoiler

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.writeTextReplacing
import com.dewijones92.totum.data.ask.AskJson
import com.dewijones92.totum.data.ask.AskOutcome
import com.dewijones92.totum.domain.ItemFace
import com.dewijones92.totum.domain.ItemShield
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.youTubeChannelId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
public data class ShieldedChannel(val id: String? = null, val name: String) {
    public fun covers(channelId: String?, author: String?): Boolean =
        (id != null && id == channelId) || (author != null && nameKey(author) == nameKey(name))
}

@Serializable
public data class StoredTitle(val title: String, val spoils: Boolean, val at: Long)

@Serializable
public data class ShieldState(
    val channels: List<ShieldedChannel> = emptyList(),
    val safe: Map<String, StoredTitle> = emptyMap(),
    val revealed: Set<String> = emptySet(),
) {
    public fun shieldsChannel(channelId: String?, author: String?): Boolean =
        channels.any { it.covers(channelId, author) }
}

public interface ShieldStore {
    public fun load(): ShieldState

    public fun save(state: ShieldState)
}

public class InMemoryShieldStore(private var state: ShieldState = ShieldState()) : ShieldStore {
    override fun load(): ShieldState = state

    override fun save(state: ShieldState) {
        this.state = state
    }
}

public class FileShieldStore(private val file: File) : ShieldStore {
    override fun load(): ShieldState = runCatching {
        if (file.exists()) AskJson.decodeFromString(ShieldState.serializer(), file.readText()) else ShieldState()
    }.getOrElse {
        Diag.warn(TAG, "the spoiler shield's file could not be read, starting with nothing shielded: $it")
        ShieldState()
    }

    override fun save(state: ShieldState) {
        runCatching { file.writeTextReplacing(AskJson.encodeToString(ShieldState.serializer(), state)) }
            .onFailure { Diag.warn(TAG, "the spoiler shield could not be saved: $it") }
    }
}

@Suppress("TooManyFunctions")
public class SpoilerShield(
    private val store: ShieldStore,
    private val check: suspend (List<SpoilerQuery>) -> AskOutcome<SpoilerAnswer>,
    private val watched: (MediaItemId) -> Boolean,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val debounceMs: Long = DEBOUNCE_MS,
) : ItemShield {
    private val _state = MutableStateFlow(store.load())
    public val state: StateFlow<ShieldState> = _state.asStateFlow()

    private val lock = Any()
    private val pending = LinkedHashMap<String, SpoilerQuery>()
    private val inFlight = HashSet<String>()
    private val retryAfter = HashMap<String, Long>()
    private val flushScheduled = AtomicBoolean(false)
    private val noted = ConcurrentHashMap.newKeySet<String>()

    override fun face(item: MediaItem): ItemFace {
        val snapshot = _state.value
        val id = item.id.value
        val why = whyNot(snapshot, item)
        if (why != null) {
            note(id, "plain: $why", item)
            return ItemFace(item.title, item.thumbnailUrl, item.duration)
        }
        val known = snapshot.safe[id]
        if (known == null) enqueue(item)
        note(id, if (known == null) "hidden, waiting for a safe title" else "safe title", item)
        return ItemFace(
            title = known?.title ?: SpoilerRules.NEUTRAL,
            thumbnailUrl = item.thumbnailUrl,
            duration = null,
            blurThumbnail = true,
            shielded = true,
        )
    }

    public fun isShielded(item: MediaItem): Boolean = whyNot(_state.value, item) == null

    public fun channelShielded(channelId: String?, author: String?): Boolean =
        _state.value.shieldsChannel(channelId, author)

    public fun setChannel(channel: ShieldedChannel, on: Boolean) {
        change("channel \"${channel.name}\" ${if (on) "shielded" else "unshielded"}") { state ->
            val rest = state.channels.filterNot { it.covers(channel.id, channel.name) }
            state.copy(channels = if (on) rest + channel else rest)
        }
    }

    public fun reveal(id: MediaItemId) {
        change("title of ${id.value} revealed by hand") { it.copy(revealed = it.revealed + id.value) }
    }

    public fun hideAgain(id: MediaItemId) {
        change("title of ${id.value} hidden again by hand") { it.copy(revealed = it.revealed - id.value) }
    }

    public fun revealedByHand(id: MediaItemId): Boolean = id.value in _state.value.revealed

    public fun safeTitleOf(itemId: String): String = _state.value.safe[itemId]?.title ?: SpoilerRules.NEUTRAL

    private fun whyNot(state: ShieldState, item: MediaItem): String? {
        val channelId = item.sourceUrl?.youTubeChannelId
        return when {
            !state.shieldsChannel(channelId, item.author) -> "its channel is not shielded"
            item.id.value in state.revealed -> "revealed by hand"
            watched(item.id) -> "already watched"
            else -> null
        }
    }

    private fun note(id: String, what: String, item: MediaItem) {
        if (noted.add("$id|$what")) {
            Diag.log(TAG, "item $id by \"${item.author}\" shown $what [channelId=${item.sourceUrl?.youTubeChannelId}]")
        }
    }

    private fun change(why: String, edit: (ShieldState) -> ShieldState) {
        _state.update(edit)
        store.save(_state.value)
        Diag.log(TAG, "$why; ${_state.value.channels.size} channel(s) shielded, ${_state.value.safe.size} titles known")
    }

    private fun enqueue(item: MediaItem) {
        val id = item.id.value
        val now = clock()
        val queued = synchronized(lock) {
            val retryAt = retryAfter[id] ?: 0L
            if (id in pending || id in inFlight || retryAt > now) {
                false
            } else {
                pending[id] = SpoilerQuery(id, item.title, item.author.orEmpty())
                true
            }
        }
        if (queued) scheduleFlush()
    }

    private fun scheduleFlush() {
        if (!flushScheduled.compareAndSet(false, true)) return
        scope.launch {
            delay(debounceMs)
            flushScheduled.set(false)
            flush()
        }
    }

    internal suspend fun flush() {
        val batch = synchronized(lock) {
            val taken = pending.values.take(BATCH)
            taken.forEach {
                pending.remove(it.id)
                inFlight += it.id
            }
            taken
        }
        if (batch.isEmpty()) return
        Diag.log(TAG, "asking for safe titles of ${batch.size} item(s): ${batch.joinToString { it.id }}")
        when (val outcome = check(batch)) {
            is AskOutcome.Answered -> settle(batch, outcome.value)
            is AskOutcome.Failed -> giveBackLater(batch, outcome.reason)
        }
        if (synchronized(lock) { pending.isNotEmpty() }) scheduleFlush()
    }

    private fun settle(batch: List<SpoilerQuery>, answer: SpoilerAnswer) {
        val now = clock()
        val byId = answer.verdicts.associateBy { it.id }
        val settled = mutableMapOf<String, StoredTitle>()
        val missing = mutableListOf<String>()
        batch.forEach { query ->
            val verdict = byId[query.id]
            if (verdict == null) {
                missing += query.id
            } else {
                val decided = SpoilerRules.decide(query.title, verdict.spoils, verdict.safeTitle)
                settled[query.id] = StoredTitle(decided.title, decided.spoils, now)
                Diag.log(TAG, "item ${query.id}: ${decided.why}; shown as \"${decided.title}\"")
            }
        }
        synchronized(lock) {
            batch.forEach { inFlight -= it.id }
            missing.forEach { retryAfter[it] = now + RETRY_MS }
        }
        if (missing.isNotEmpty()) Diag.warn(TAG, "no verdict for ${missing.size} item(s): $missing; trying again later")
        if (settled.isNotEmpty()) {
            change("${settled.size} safe title(s) received") { state ->
                state.copy(safe = newest(state.safe + settled))
            }
        }
    }

    private fun giveBackLater(batch: List<SpoilerQuery>, reason: String) {
        val now = clock()
        synchronized(lock) {
            batch.forEach {
                inFlight -= it.id
                retryAfter[it.id] = now + RETRY_MS
            }
        }
        Diag.warn(
            TAG,
            "safe titles for ${batch.size} item(s) not received ($reason); they stay hidden, " +
                "retry in ${RETRY_MS / MINUTE} min",
        )
    }

    public companion object {
        private const val DEBOUNCE_MS = 400L
        private const val BATCH = 25
        private const val RETRY_MS = 10 * 60_000L
        private const val MINUTE = 60_000L
    }
}

private const val TAG = "spoiler"

private fun nameKey(name: String): String = name.trim().lowercase()

private fun newest(titles: Map<String, StoredTitle>, keep: Int = 3_000): Map<String, StoredTitle> =
    if (titles.size <= keep) {
        titles
    } else {
        titles.entries.sortedBy { it.value.at }.takeLast(keep).associate { it.toPair() }
    }
