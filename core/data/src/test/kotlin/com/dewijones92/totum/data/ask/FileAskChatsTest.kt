package com.dewijones92.totum.data.ask

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class FileAskChatsTest {

    @Test
    fun `chats survive a save and a fresh read, newest first and at most twenty`() {
        val dir = Files.createTempDirectory("chats").toFile()
        val file = dir.resolve("ask-chats.json")
        var chats = emptyList<SavedChat>()
        (1..25).forEach { n -> chats = chats.keeping(SavedChat("c$n", n.toLong(), listOf(SavedTurn("ask $n")))) }
        FileAskChats(file).save(chats)
        val read = FileAskChats(file).load()
        assertEquals(20, read.size)
        assertEquals("ask 25", read.first().title)
        assertEquals(listOf("ask-chats.json"), dir.list()!!.toList())
        dir.deleteRecursively()
    }
}
