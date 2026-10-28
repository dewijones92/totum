package com.dewijones92.totum.support

import android.util.Log
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.browse.InnerTubeResponse
import com.dewijones92.totum.innertube.player.PlayerResponseParser
import com.dewijones92.totum.innertube.player.PlayerResult
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.AssumptionViolatedException
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

class YouTubeAnswers : TestRule {

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            refusal?.let {
                Log.w(TAG, "dewidebug ${description.methodName} skipped: YouTube refuses this address ($it)")
                throw AssumptionViolatedException("YouTube refuses this address today, so nothing can be measured: $it")
            }
            try {
                base.evaluate()
            } catch (skipped: AssumptionViolatedException) {
                throw skipped
            } catch (failure: Throwable) {
                val said = generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
                if (!BLOCKED.containsMatchIn(said)) throw failure
                Log.w(
                    TAG,
                    "dewidebug ${description.methodName} skipped: YouTube refused part-way (${said.take(SAID_CHARS)})"
                )
                throw AssumptionViolatedException("YouTube refused part-way: ${said.take(SAID_CHARS)}", failure)
            }
        }
    }

    companion object {
        private const val TAG = "dewidebug"
        private const val PROBE_ID = "aqz-KE-bpKQ"
        private const val SAID_CHARS = 300
        private val BLOCKED = Regex("not a bot|too many requests|HTTP 429|bot check", RegexOption.IGNORE_CASE)

        val refusal: String? by lazy {
            val answer = runCatching { runBlocking { InnerTubeClient(OkHttpClient()).player(PROBE_ID) } }
            val said = when (val response = answer.getOrNull()) {
                is InnerTubeResponse.Success -> when (val parsed = PlayerResponseParser.parse(response.body)) {
                    is PlayerResult.Unplayable -> parsed.reason
                    else -> null
                }
                is InnerTubeResponse.Failure -> response.detail
                else -> null
            }
            Log.i(TAG, "dewidebug YouTube probe for live tests: ${said ?: "answers"}")
            said?.takeIf { BLOCKED.containsMatchIn(it) }
        }
    }
}
