package com.dewijones92.totum.cli.serve.cast

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

internal class FakeRenderer(
    private val sink: String = "http-get:*:application/x-mpegURL:*,http-get:*:audio/mpeg:*",
    private val withVolume: Boolean = true,
) : AutoCloseable {

    class Call(val action: String, val args: Map<String, String>)

    val calls = mutableListOf<Call>()

    @Volatile var transportState = "STOPPED"

    @Volatile var transportStatus = "OK"

    @Volatile var relTime = "0:00:00"

    @Volatile var trackDuration = "0:00:00"

    @Volatile var volume = 30

    @Volatile var refuse: Set<String> = emptySet()

    @Volatile var down = false

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange -> answer(exchange) }
        start()
    }

    val port: Int get() = server.address.port

    val descriptionUrl: String get() = "http://127.0.0.1:$port/description.xml"

    fun actions(): List<String> = synchronized(calls) { calls.map { it.action } }

    fun last(action: String): Call = synchronized(calls) { calls.last { it.action == action } }

    fun count(action: String): Int = synchronized(calls) { calls.count { it.action == action } }

    private fun description(): String {
        val rendering = if (withVolume) {
            "<service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>" +
                "<controlURL>/rc/control</controlURL></service>"
        } else {
            ""
        }
        return """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device>
            <friendlyName>Fake renderer</friendlyName><modelName>Fake</modelName><serviceList>
            <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/av/control</controlURL></service>
            <service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType><controlURL>/cm/control</controlURL></service>
            $rendering
            </serviceList></device></root>"""
    }

    private fun answer(exchange: HttpExchange) {
        if (down) {
            exchange.close()
            return
        }
        val path = exchange.requestURI.path
        if (path == "/description.xml") {
            reply(exchange, 200, description())
            return
        }
        val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        val action = exchange.requestHeaders.getFirst("SOAPACTION").orEmpty().trim('"').substringAfter('#')
        val args = UpnpXml.parse(body).documentElement.let { root ->
            UpnpXml.all(root, action).firstOrNull()?.let { element ->
                UpnpXml.children(element).associate { it.localName to it.textContent.orEmpty() }
            }.orEmpty()
        }
        synchronized(calls) { calls += Call(action, args) }
        if (action in refuse) {
            reply(
                exchange,
                500,
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault><detail>" +
                    "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>701</errorCode>" +
                    "<errorDescription>Transition not available</errorDescription></UPnPError></detail></s:Fault>" +
                    "</s:Body></s:Envelope>",
            )
            return
        }
        reply(exchange, 200, response(action, args))
    }

    private fun response(action: String, args: Map<String, String>): String {
        val out = when (action) {
            "GetProtocolInfo" -> mapOf("Source" to "", "Sink" to sink)
            "GetTransportInfo" -> mapOf(
                "CurrentTransportState" to transportState,
                "CurrentTransportStatus" to transportStatus,
                "CurrentSpeed" to "1",
            )
            "GetPositionInfo" -> mapOf("RelTime" to relTime, "TrackDuration" to trackDuration)
            "GetVolume" -> mapOf("CurrentVolume" to volume.toString())
            "SetVolume" -> {
                volume = args.getValue("DesiredVolume").toInt()
                emptyMap()
            }
            "Play" -> {
                transportState = "PLAYING"
                emptyMap()
            }
            "Pause" -> {
                transportState = "PAUSED_PLAYBACK"
                emptyMap()
            }
            "Stop" -> {
                transportState = "STOPPED"
                emptyMap()
            }
            "Seek" -> {
                relTime = args.getValue("Target")
                emptyMap()
            }
            else -> emptyMap()
        }
        return "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
            "<u:${action}Response xmlns:u=\"urn:schemas-upnp-org:service:X:1\">" +
            out.entries.joinToString("") { "<${it.key}>${UpnpXml.escape(it.value)}</${it.key}>" } +
            "</u:${action}Response></s:Body></s:Envelope>"
    }

    private fun reply(exchange: HttpExchange, status: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/xml")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() = server.stop(0)
}
