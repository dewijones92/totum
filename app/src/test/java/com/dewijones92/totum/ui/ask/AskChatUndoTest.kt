package com.dewijones92.totum.ui.ask

import com.dewijones92.totum.data.ask.AskCopies
import com.dewijones92.totum.data.ask.InMemoryAskChats
import com.dewijones92.totum.data.ask.SavedChat
import com.dewijones92.totum.data.ask.SavedTurn
import com.dewijones92.totum.operations.Operations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AskChatUndoTest {

    @Before fun setMainDispatcher() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun chat(id: String, at: Long) = SavedChat(id, at, listOf(SavedTurn("question $id")))

    private fun viewModel(store: InMemoryAskChats) =
        AskViewModel(null, AskCopies({ emptyList() }), { emptyList() }, Operations(), chats = store)

    private fun stored(vararg chats: SavedChat) = InMemoryAskChats().also { it.save(chats.toList()) }

    @Test
    fun `forgetting a chat removes it from the saved list and the earlier chats and hands it back`() {
        val store = stored(chat("new", 300), chat("mid", 200), chat("old", 100))
        val vm = viewModel(store)
        assertEquals("the newest chat is the open one", listOf("mid", "old"), vm.uiState.value.recent.map { it.id })

        val gone = vm.forget("mid")

        assertEquals("mid", gone?.id)
        assertEquals(listOf("new", "old"), store.load().map { it.id })
        assertEquals(listOf("old"), vm.uiState.value.recent.map { it.id })
    }

    @Test
    fun `undo puts the chat back in date order, in the store and in the list`() {
        val store = stored(chat("new", 300), chat("mid", 200), chat("old", 100))
        val vm = viewModel(store)
        val gone = vm.forget("mid")!!

        vm.unforget(gone)

        assertEquals(listOf("new", "mid", "old"), store.load().map { it.id })
        assertEquals(listOf("mid", "old"), vm.uiState.value.recent.map { it.id })
    }

    @Test
    fun `forgetting a chat that is not there removes nothing and offers nothing to undo`() {
        val store = stored(chat("new", 300), chat("old", 100))
        val vm = viewModel(store)

        assertNull(vm.forget("missing"))

        assertEquals(listOf("new", "old"), store.load().map { it.id })
    }

    @Test
    fun `undoing the same chat twice leaves one copy`() {
        val store = stored(chat("new", 300), chat("old", 100))
        val vm = viewModel(store)
        val gone = vm.forget("old")!!

        vm.unforget(gone)
        vm.unforget(gone)

        assertEquals(listOf("new", "old"), store.load().map { it.id })
    }
}
