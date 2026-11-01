package com.dewijones92.totum.data.ask

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.writeTextReplacing
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

@Serializable
public data class SavedTurn(
    val request: String,
    val reply: String = "",
    val suggestions: List<Suggestion> = emptyList()
)

@Serializable
public data class SavedChat(val id: String, val at: Long, val turns: List<SavedTurn>) {
    val title: String get() = turns.firstOrNull()?.request.orEmpty()
}

public interface AskChatStore {
    public fun load(): List<SavedChat>

    public fun save(chats: List<SavedChat>)
}

public class InMemoryAskChats : AskChatStore {
    private var chats: List<SavedChat> = emptyList()

    override fun load(): List<SavedChat> = chats

    override fun save(chats: List<SavedChat>) {
        this.chats = chats
    }
}

public class FileAskChats(private val file: File) : AskChatStore {
    private val serializer = ListSerializer(SavedChat.serializer())

    override fun load(): List<SavedChat> = runCatching {
        if (!file.exists()) emptyList() else AskJson.decodeFromString(serializer, file.readText())
    }.getOrElse {
        Diag.warn("ask", "saved chats unreadable, starting afresh: $it")
        emptyList()
    }

    override fun save(chats: List<SavedChat>) {
        runCatching {
            file.writeTextReplacing(AskJson.encodeToString(serializer, chats))
        }.onFailure { Diag.warn("ask", "chats not saved: $it") }
    }
}

public fun List<SavedChat>.keeping(chat: SavedChat, max: Int = MAX_CHATS): List<SavedChat> =
    (listOf(chat) + filterNot { it.id == chat.id }).sortedByDescending { it.at }.take(max)

private const val MAX_CHATS = 20
