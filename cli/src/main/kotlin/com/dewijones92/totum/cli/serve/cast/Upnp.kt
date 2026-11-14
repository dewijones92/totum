package com.dewijones92.totum.cli.serve.cast

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

internal class UpnpAnswer(val status: Int, val body: String)

internal interface UpnpHttp {
    fun get(url: String): UpnpAnswer
    fun post(url: String, headers: Map<String, String>, body: String): UpnpAnswer
}

internal class JdkUpnpHttp(private val timeoutMs: Int = TIMEOUT_MS) : UpnpHttp {

    override fun get(url: String): UpnpAnswer = exchange(url, "GET", emptyMap(), null)

    override fun post(url: String, headers: Map<String, String>, body: String): UpnpAnswer =
        exchange(url, "POST", headers, body)

    private fun exchange(url: String, method: String, headers: Map<String, String>, body: String?): UpnpAnswer {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status < HTTP_ERROR) connection.inputStream else connection.errorStream
            return UpnpAnswer(status, stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 8_000
        const val HTTP_ERROR = 400
    }
}

internal class UpnpFault(val code: Int?, val description: String?, val status: Int) :
    IOException("UPnP error ${code ?: "?"} ${description ?: ""} (HTTP $status)".trim())

internal class UpnpService(val type: String, val controlUrl: String)

internal class UpnpDevice(val friendlyName: String?, val model: String?, val services: List<UpnpService>) {

    fun service(kind: String): UpnpService? = services.firstOrNull { it.type.contains(":$kind:") }

    companion object {
        const val AV_TRANSPORT = "AVTransport"
        const val RENDERING_CONTROL = "RenderingControl"
        const val CONNECTION_MANAGER = "ConnectionManager"

        fun parse(xml: String, descriptionUrl: String): UpnpDevice {
            val root = UpnpXml.parse(xml).documentElement
            val base = UpnpXml.first(root, "URLBase")?.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: descriptionUrl
            val device = UpnpXml.first(root, "device")
            val services = UpnpXml.all(root, "service").mapNotNull { service ->
                val type = UpnpXml.first(service, "serviceType")?.textContent?.trim()
                val control = UpnpXml.first(service, "controlURL")?.textContent?.trim()
                if (type.isNullOrEmpty() || control.isNullOrEmpty()) {
                    null
                } else {
                    UpnpService(type, URI(base).resolve(control).toString())
                }
            }
            return UpnpDevice(
                device?.let { UpnpXml.first(it, "friendlyName")?.textContent?.trim() },
                device?.let { UpnpXml.first(it, "modelName")?.textContent?.trim() },
                services,
            )
        }
    }
}

internal object UpnpXml {
    fun parse(xml: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        isXIncludeAware = false
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

    fun all(from: Element, local: String): List<Element> {
        val found = from.getElementsByTagNameNS("*", local)
        return (0 until found.length).map { found.item(it) as Element }
    }

    fun first(from: Element, local: String): Element? = all(from, local).firstOrNull()

    fun children(from: Element): List<Element> =
        (0 until from.childNodes.length).map { from.childNodes.item(it) }.filter { it.nodeType == Node.ELEMENT_NODE }
            .map { it as Element }

    fun escape(text: String): String = buildString {
        text.forEach {
            when (it) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(it)
            }
        }
    }
}

internal class UpnpSoap(private val http: UpnpHttp) {

    fun call(
        service: UpnpService,
        action: String,
        arguments: List<Pair<String, String>> = emptyList(),
    ): Map<String, String> {
        val body = envelope(service.type, action, arguments)
        val answer = http.post(
            service.controlUrl,
            mapOf(
                "Content-Type" to "text/xml; charset=\"utf-8\"",
                "SOAPACTION" to "\"${service.type}#$action\"",
            ),
            body,
        )
        if (answer.status !in OK_RANGE) throw fault(answer)
        return outputs(answer.body)
    }

    private fun fault(answer: UpnpAnswer): UpnpFault {
        val parsed = runCatching { UpnpXml.parse(answer.body).documentElement }.getOrNull()
        val code = parsed?.let { UpnpXml.first(it, "errorCode")?.textContent?.trim()?.toIntOrNull() }
        val text = parsed?.let { UpnpXml.first(it, "errorDescription")?.textContent?.trim() }
        return UpnpFault(code, text, answer.status)
    }

    companion object {
        private val OK_RANGE = 200..299

        fun envelope(type: String, action: String, arguments: List<Pair<String, String>>): String = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
            append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
            append("<u:$action xmlns:u=\"$type\">")
            arguments.forEach { (name, value) -> append("<$name>${UpnpXml.escape(value)}</$name>") }
            append("</u:$action></s:Body></s:Envelope>")
        }

        fun outputs(xml: String): Map<String, String> {
            val root = UpnpXml.parse(xml).documentElement
            val body = UpnpXml.first(root, "Body") ?: return emptyMap()
            val response = UpnpXml.children(body).firstOrNull() ?: return emptyMap()
            return UpnpXml.children(response).associate { it.localName to it.textContent.orEmpty() }
        }
    }
}

internal object Didl {

    fun item(
        title: String,
        url: String,
        protocolInfo: String,
        upnpClass: String = "object.item.audioItem.musicTrack",
    ): String =
        "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>${UpnpXml.escape(title)}</dc:title>" +
            "<upnp:class>$upnpClass</upnp:class>" +
            "<res protocolInfo=\"${UpnpXml.escape(protocolInfo)}\">${UpnpXml.escape(url)}</res>" +
            "</item></DIDL-Lite>"

    fun protocolInfo(mime: String): String = "http-get:*:$mime:*"
}

internal object UpnpTime {
    private const val SECONDS = 60
    private const val HOUR = 3600
    private const val MS = 1000L

    fun format(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / MS).toInt()
        return "%d:%02d:%02d".format(total / HOUR, total % HOUR / SECONDS, total % SECONDS)
    }

    fun parse(text: String?): Long? {
        val clean = text?.trim()?.substringBefore('/')
            ?.takeIf { it.isNotEmpty() && !it.startsWith("NOT_") } ?: return null
        val sign = if (clean.startsWith("-")) -1 else 1
        val parts = clean.removePrefix("-").removePrefix("+").split(':')
        if (parts.size != PARTS) return null
        val hours = parts[0].toLongOrNull() ?: return null
        val minutes = parts[1].toLongOrNull() ?: return null
        val seconds = parts[2].toDoubleOrNull() ?: return null
        return sign * ((hours * HOUR + minutes * SECONDS) * MS + (seconds * MS).toLong())
    }

    private const val PARTS = 3
}
