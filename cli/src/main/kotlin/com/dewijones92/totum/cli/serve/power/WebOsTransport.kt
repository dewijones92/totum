package com.dewijones92.totum.cli.serve.power

import com.dewijones92.totum.common.Diag
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

internal interface WebOsLine : AutoCloseable {
    val certSeen: String?

    fun send(message: JsonObject)

    fun receive(timeoutMs: Long): JsonObject?
}

internal fun interface WebOsDial {
    fun open(pinnedCert: String?): WebOsLine
}

internal class WebOsClosedByTv(val code: Int, message: String) : IOException(message)

internal class PinnedTrust(private val pinned: String?) : X509TrustManager {
    @Volatile var seen: String? = null
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        val fingerprint = fingerprintOf(chain.first())
        seen = fingerprint
        if (pinned != null && !pinned.equals(fingerprint, ignoreCase = true)) {
            throw CertificateException("the TV's certificate is not the one it had when it was paired")
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
        throw CertificateException("this side only ever talks to a TV")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    companion object {
        fun fingerprintOf(certificate: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
    }
}

internal class OkWebOsDial(
    private val urls: List<String>,
    private val connectMs: Long = CONNECT_MS,
    private val base: OkHttpClient = SHARED,
) : WebOsDial {

    override fun open(pinnedCert: String?): WebOsLine {
        val candidates = if (pinnedCert != null) urls.filter { it.startsWith("wss://") } else urls
        if (candidates.isEmpty()) throw IOException("no secure address for the TV, and its certificate is pinned")
        val failures = mutableListOf<String>()
        for (url in candidates) {
            try {
                return connect(url, pinnedCert)
            } catch (e: IOException) {
                failures += "${schemeOf(url)}: ${e.message}"
            }
        }
        throw IOException("could not connect (${failures.joinToString("; ")})")
    }

    private fun connect(url: String, pinnedCert: String?): WebOsLine {
        val trust = PinnedTrust(pinnedCert)
        val client = base.newBuilder().apply {
            if (url.startsWith("wss://")) {
                val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
                sslSocketFactory(context.socketFactory, trust)
                hostnameVerifier { _, _ -> true }
            }
            connectTimeout(connectMs, TimeUnit.MILLISECONDS)
        }.build()
        val inbox = LinkedBlockingQueue<Any>()
        val opened = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val socket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.countDown()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val parsed = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
                    if (parsed == null) {
                        Diag.warn("webos", "an unreadable message of ${text.length} characters from the TV was ignored")
                    } else {
                        inbox.add(parsed)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    inbox.add(Gone("the TV closed the connection ($code)", code))
                    webSocket.close(NORMAL_CLOSE, "")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    inbox.add(Gone("the TV closed the connection ($code)", code))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val why = t.message ?: t.javaClass.simpleName
                    failure.set(why)
                    inbox.add(Gone(why))
                    opened.countDown()
                }
            },
        )
        if (!opened.await(connectMs, TimeUnit.MILLISECONDS) || failure.get() != null) {
            socket.cancel()
            throw IOException(failure.get() ?: "no answer within ${connectMs}ms")
        }
        return OkLine(socket, inbox, trust)
    }

    private fun schemeOf(url: String): String =
        url.substringBefore("://") + "://" + url.substringAfter("://").substringBefore('/')

    private class Gone(val why: String, val code: Int? = null)

    private class OkLine(
        private val socket: WebSocket,
        private val inbox: LinkedBlockingQueue<Any>,
        private val trust: PinnedTrust,
    ) : WebOsLine {
        override val certSeen: String? get() = trust.seen

        override fun send(message: JsonObject) {
            if (socket.send(message.toString())) return
            val closed = (inbox.peek() as? Gone)?.takeIf { it.code != null }
            throw closed?.let { goneAsFailure(it) } ?: IOException("the TV's connection is closed")
        }

        override fun receive(timeoutMs: Long): JsonObject? {
            val item = inbox.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return null
            if (item is Gone) {
                inbox.add(item)
                throw goneAsFailure(item)
            }
            return item as JsonObject
        }

        private fun goneAsFailure(item: Gone): IOException =
            item.code?.let { WebOsClosedByTv(it, item.why) } ?: IOException(item.why)

        override fun close() {
            socket.close(NORMAL_CLOSE, "")
        }
    }

    private companion object {
        const val CONNECT_MS = 5_000L
        const val NORMAL_CLOSE = 1000
        val SHARED = OkHttpClient()
    }
}
