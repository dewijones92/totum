package com.dewijones92.totum.ytdlp.chaquopy

import android.content.Context
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import androidx.javascriptengine.SandboxDeadException
import com.dewijones92.totum.common.Diag
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class SandboxIsolates(
    context: Context,
    private val evaluateTimeoutMs: Long = EVALUATE_TIMEOUT_MS,
) : JsIsolates {

    private val appContext = context.applicationContext
    private var sandbox: JavaScriptSandbox? = null

    @Volatile
    private var refusal: String? = null

    private val supported: Boolean by lazy {
        JavaScriptSandbox.isSupported().also {
            if (!it) Diag.log("engine", "v8 sandbox not supported by this WebView — QuickJS solves every challenge")
        }
    }

    override fun usable(): Boolean = supported && refusal == null

    @Synchronized
    override fun open(): JsIsolate {
        check(usable()) { "v8 sandbox unusable: ${refusal ?: "not supported"}" }
        val box = sandbox ?: connect()
        val params = IsolateStartupParameters()
        if (box.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE)) {
            params.maxHeapSizeBytes = MAX_HEAP_BYTES
        }
        val isolate = try {
            box.createIsolate(params)
        } catch (e: IllegalStateException) {
            drop("createIsolate failed: ${e.message}")
            throw e
        }
        return SandboxIsolate(isolate)
    }

    @Synchronized
    override fun close() {
        drop("closed")
    }

    private fun connect(): JavaScriptSandbox {
        val started = System.nanoTime()
        val box = JavaScriptSandbox.createConnectedInstanceAsync(
            appContext
        ).get(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val unlimited = box.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT)
        Diag.log(
            "engine",
            "v8 sandbox connected in ${(System.nanoTime() - started) / NANOS_PER_MILLI}ms " +
                "[large scripts=$unlimited heap cap=" +
                "${box.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE)}]",
        )
        if (!unlimited) {
            box.close()
            refusal = "this WebView cannot pass a multi-megabyte player script"
            Diag.log("engine", "v8 sandbox refused: $refusal — QuickJS solves every challenge")
            error(refusal!!)
        }
        sandbox = box
        return box
    }

    private fun drop(reason: String) {
        val box = sandbox ?: return
        sandbox = null
        Diag.log("engine", "v8 sandbox dropped: $reason")
        runCatching { box.close() }.onFailure { Diag.warn("engine", "v8 sandbox close failed: $it") }
    }

    private inner class SandboxIsolate(private val isolate: JavaScriptIsolate) : JsIsolate {
        override fun evaluate(code: String): String =
            try {
                isolate.evaluateJavaScriptAsync(code).get(evaluateTimeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                throw IllegalStateException("v8 evaluation took over ${evaluateTimeoutMs}ms", e)
            } catch (e: ExecutionException) {
                val cause = e.cause ?: e
                if (cause is SandboxDeadException) {
                    synchronized(this@SandboxIsolates) { drop("the sandbox died: ${cause.message}") }
                }
                throw IllegalStateException("${cause.javaClass.simpleName}: ${cause.message}", cause)
            }

        override fun close() {
            isolate.close()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val EVALUATE_TIMEOUT_MS = 20_000L
        const val MAX_HEAP_BYTES = 512L * 1024 * 1024
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
