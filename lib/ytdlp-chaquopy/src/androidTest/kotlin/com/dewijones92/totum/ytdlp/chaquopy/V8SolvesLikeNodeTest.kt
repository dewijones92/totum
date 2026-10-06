package com.dewijones92.totum.ytdlp.chaquopy

import android.content.Context
import androidx.javascriptengine.JavaScriptSandbox
import androidx.test.core.app.ApplicationProvider
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.zip.GZIPInputStream

class V8SolvesLikeNodeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val isolates = SandboxIsolates(context)
    private val runtime = V8ChallengeRuntime(isolates)

    @After
    fun close() {
        runtime.close()
        isolates.close()
    }

    @Test
    fun v8GivesNodesAnswersForARealPlayerInFullAndFromMemory() {
        assumeTrue("this WebView has no JavaScript sandbox", JavaScriptSandbox.isSupported())
        val library = solverLibrary()
        val player = checkNotNull(javaClass.classLoader?.getResourceAsStream(PLAYER)) { "no $PLAYER" }.use {
            GZIPInputStream(it).readBytes().decodeToString()
        }
        val playerJson = Json.encodeToString(player)

        runtime.loadLibrary("test", library)
        val raw = answers(
            runtime.solveWithPlayer(PLAYER_URL, playerJson, preprocessed = false, requestsJson = REQUESTS)
        )
        val kept = answers(checkNotNull(runtime.solveKept(PLAYER_URL, REQUESTS)) { "the player was not kept" })

        assertEquals(NODE_ANSWERS, raw)
        assertEquals(NODE_ANSWERS, kept)
    }

    private fun solverLibrary(): String {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
        val python = Python.getInstance()
        val solver = python.getModule("yt_dlp_ejs.yt.solver")
        return python.getModule("totum_ytdlp")
            .callAttr("v8_library_code", solver.callAttr("lib"), solver.callAttr("core")).toString()
    }

    private fun answers(output: String): Map<String, String> =
        (
            Json.parseToJsonElement(output).jsonObject.getValue("responses").jsonArray[0].jsonObject
                .getValue("data") as JsonObject
            ).mapValues { it.value.jsonPrimitive.content }

    private companion object {
        const val PLAYER = "player-1f293754.js.gz"
        const val PLAYER_URL = "https://www.youtube.com/s/player/1f293754/player_ias.vflset/en_US/base.js"
        const val REQUESTS = """[{"type": "n", "challenges": ["JbsW7nF_4hNj0Z4ki", "npLyD0ayScMOcFPkr"]}]"""
        val NODE_ANSWERS = mapOf("JbsW7nF_4hNj0Z4ki" to "SDp7o0PdGsEhWg", "npLyD0ayScMOcFPkr" to "zbQJfjp7ursJig")
    }
}
