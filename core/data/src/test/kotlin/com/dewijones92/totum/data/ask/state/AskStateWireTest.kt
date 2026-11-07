package com.dewijones92.totum.data.ask.state

import com.dewijones92.totum.data.ask.AskAnswer
import com.dewijones92.totum.data.ask.AskJson
import com.dewijones92.totum.data.ask.AskSource
import com.dewijones92.totum.data.ask.AskTrace
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class AskStateWireTest {

    private fun gzip(raw: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(raw) } }.toByteArray()

    @Test
    fun `a packed snapshot unpacks to the same state and schema and is smaller than the text`() {
        val built = AppStateBuilder.build(StateTestData.inputs())

        val packed = AskStateWire.pack(built)
        val unpacked = AskStateWire.unpack(packed)

        assertEquals(built.json, unpacked.state)
        assertEquals(StateSchema.of(AppState.serializer().descriptor, built.json), unpacked.schema)
        assertTrue(packed.size < built.json.toString().length)
    }

    @Test
    fun `bytes that are not gzip are refused`() {
        val failure = runCatching { AskStateWire.unpack("{\"state\":{}}".toByteArray()) }.exceptionOrNull()

        assertTrue(failure != null)
    }

    @Test
    fun `gzip of something that is not a snapshot is refused`() {
        val failure = runCatching { AskStateWire.unpack(gzip("[1,2,3]".toByteArray())) }.exceptionOrNull()

        assertTrue(failure != null)
    }

    @Test
    fun `a small file that unpacks to more than the limit is stopped, not read into memory`() {
        val bomb = gzip(ByteArray(AskStateWire.MAX_BYTES.toInt() + 1_000_000))

        val failure = runCatching { AskStateWire.unpack(bomb) }.exceptionOrNull()

        assertTrue(bomb.size < 1_000_000)
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message, failure.message!!.contains("larger than"))
    }

    @Test
    fun `the receipt and an empty envelope round-trip as the phone reads them`() {
        val receipt = AskJson.decodeFromString(
            StateReceipt.serializer(),
            """{"ok":true,"bytes":10,"keptForMs":600000}"""
        )

        assertEquals(StateReceipt(true, 10, 600_000), receipt)
        val envelope = AskStateEnvelope(JsonObject(emptyMap()), JsonObject(emptyMap()))
        assertEquals(
            envelope,
            AskJson.decodeFromString(
                AskStateEnvelope.serializer(),
                AskJson.encodeToString(AskStateEnvelope.serializer(), envelope),
            ),
        )
    }

    @Test
    fun `a trace says which half of an ask took the time and what jq was allowed to do`() {
        val answer = AskAnswer(
            emptyList(),
            "text",
            AskSource.APP,
            AskTrace(route = "app", routeMs = 900, appMs = 14_000, jqCalls = 3, jqDenied = 1, stateBytes = 4_900_000),
        )

        val read = AskJson.decodeFromString(
            AskAnswer.serializer(),
            AskJson.encodeToString(AskAnswer.serializer(), answer)
        )

        assertEquals(answer, read)
        assertNull(read.trace?.webMs)
        assertEquals(1, read.trace?.jqDenied)
    }
}
