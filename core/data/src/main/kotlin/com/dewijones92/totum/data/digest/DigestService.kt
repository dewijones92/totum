package com.dewijones92.totum.data.digest

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.writeTextReplacing
import com.dewijones92.totum.data.ai.AiTasks
import com.dewijones92.totum.data.ai.HttpAiJobs
import com.dewijones92.totum.data.ask.AskJson
import com.dewijones92.totum.data.ask.AskOutcome
import com.dewijones92.totum.data.transcript.Cue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

public data class DigestSubject(val itemId: String, val title: String, val author: String, val durationSec: Int)

@Serializable
public data class StoredDigest(val digest: TranscriptDigest, val at: Long, val blocks: Int)

public interface DigestStore {
    public fun load(): Map<String, StoredDigest>

    public fun save(digests: Map<String, StoredDigest>)
}

public class InMemoryDigestStore(private var held: Map<String, StoredDigest> = emptyMap()) : DigestStore {
    override fun load(): Map<String, StoredDigest> = held

    override fun save(digests: Map<String, StoredDigest>) {
        held = digests
    }
}

public class FileDigestStore(private val file: File) : DigestStore {
    private val serializer = MapSerializer(String.serializer(), StoredDigest.serializer())

    override fun load(): Map<String, StoredDigest> = runCatching {
        if (file.exists()) AskJson.decodeFromString(serializer, file.readText()) else emptyMap()
    }.getOrElse {
        Diag.warn(TAG, "the saved summaries could not be read, starting afresh: $it")
        emptyMap()
    }

    override fun save(digests: Map<String, StoredDigest>) {
        runCatching { file.writeTextReplacing(AskJson.encodeToString(serializer, digests)) }
            .onFailure { Diag.warn(TAG, "the summaries could not be saved: $it") }
    }
}

public class DigestService(
    private val jobs: () -> HttpAiJobs?,
    private val store: DigestStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _digests = MutableStateFlow(store.load())
    public val digests: StateFlow<Map<String, StoredDigest>> = _digests.asStateFlow()

    public fun saved(subject: DigestSubject, cues: List<Cue>): StoredDigest? =
        _digests.value[subject.itemId]?.takeIf { it.blocks == TranscriptText.blocks(cues).size }

    public suspend fun summarise(
        subject: DigestSubject,
        cues: List<Cue>,
        force: Boolean = false,
    ): AskOutcome<TranscriptDigest> {
        saved(subject, cues)?.takeUnless { force }?.let {
            Diag.log(TAG, "${subject.itemId}: summary already kept from ${it.at}; no call made")
            return AskOutcome.Answered(it.digest, 0)
        }
        val blocks = TranscriptText.blocks(cues)
        val rendered = TranscriptText.render(blocks)
        val client = jobs() ?: return AskOutcome.Failed("❌ No home server to summarise with").also {
            Diag.warn(TAG, "${subject.itemId}: no home server is set up")
        }
        Diag.log(
            TAG,
            "${subject.itemId}: summarising \"${subject.title}\" (${subject.durationSec}s): ${cues.size} cues, " +
                "${blocks.size} blocks, ${rendered.length} chars sent",
        )
        val request = TranscriptDigestRequest(subject.title, subject.author, subject.durationSec, rendered)
        val outcome = client.run(
            AiTasks.TRANSCRIPT_DIGEST,
            request,
            TranscriptDigestRequest.serializer(),
            TranscriptDigest.serializer(),
        )
        if (outcome is AskOutcome.Answered) remember(subject, outcome.value, blocks.size)
        return outcome
    }

    public suspend fun ask(subject: DigestSubject, cues: List<Cue>, question: String): AskOutcome<TranscriptAnswer> {
        val blocks = TranscriptText.blocks(cues)
        val rendered = TranscriptText.forQuestion(blocks, question)
        val client = jobs() ?: return AskOutcome.Failed("❌ No home server to ask")
        val chosen = rendered.length < TranscriptText.render(blocks, Int.MAX_VALUE).length
        Diag.log(
            TAG,
            "${subject.itemId}: asking \"$question\" of \"${subject.title}\": ${blocks.size} blocks, " +
                "${rendered.length} chars sent${if (chosen) " (the parts that fit the question)" else ""}",
        )
        val request = TranscriptAskRequest(subject.title, subject.durationSec, question, rendered)
        return client.run(
            AiTasks.TRANSCRIPT_ASK,
            request,
            TranscriptAskRequest.serializer(),
            TranscriptAnswer.serializer(),
        )
    }

    private fun remember(subject: DigestSubject, digest: TranscriptDigest, blocks: Int) {
        _digests.update { held ->
            val kept = held + (subject.itemId to StoredDigest(digest, clock(), blocks))
            val newest = kept.entries.sortedBy { it.value.at }.takeLast(KEEP)
            if (kept.size <= KEEP) kept else newest.associate { it.toPair() }
        }
        store.save(_digests.value)
    }

    private companion object {
        const val KEEP = 300
    }
}

private const val TAG = "digest"
