package com.dewijones92.totum.cli.serve.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UpnpTest {

    private val description = """<?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0"><device>
        <friendlyName>Kodi (192.168.0.211)</friendlyName><modelName>Kodi</modelName>
        <serviceList>
        <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <controlURL>/AVTransport/abc/control.xml</controlURL></service>
        <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
        <controlURL>RenderingControl/abc/control.xml</controlURL></service>
        </serviceList></device></root>"""

    @Test
    fun `a description lists the services with their control addresses resolved against where it was fetched`() {
        val device = UpnpDevice.parse(description, "http://192.168.0.211:1285/")

        assertEquals("Kodi (192.168.0.211)", device.friendlyName)
        assertEquals("Kodi", device.model)
        assertEquals(
            "http://192.168.0.211:1285/AVTransport/abc/control.xml",
            device.service("AVTransport")!!.controlUrl
        )
        assertEquals(
            "http://192.168.0.211:1285/RenderingControl/abc/control.xml",
            device.service("RenderingControl")!!.controlUrl,
        )
        assertNull(device.service("ConnectionManager"))
    }

    @Test
    fun `a description that tries an external entity is refused rather than read`() {
        val hostile = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><root>&e;</root>"""

        assertThrows(org.xml.sax.SAXException::class.java) { UpnpXml.parse(hostile) }
    }

    @Test
    fun `an action is a SOAP envelope naming the service type, with its arguments escaped`() {
        val text = UpnpSoap.envelope(
            "urn:schemas-upnp-org:service:AVTransport:1",
            "SetAVTransportURI",
            listOf("InstanceID" to "0", "CurrentURI" to "http://pi/a?b=1&c=2"),
        )

        assertTrue(text, text.contains("<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"))
        assertTrue(text, text.contains("<CurrentURI>http://pi/a?b=1&amp;c=2</CurrentURI>"))
    }

    @Test
    fun `an answer is read as its named outputs`() {
        val answer = UpnpSoap.outputs(
            "<s:Envelope xmlns:s=\"x\"><s:Body><u:GetPositionInfoResponse xmlns:u=\"y\">" +
                "<RelTime>0:00:07</RelTime><TrackDuration>0:03:00</TrackDuration></u:GetPositionInfoResponse>" +
                "</s:Body></s:Envelope>",
        )

        assertEquals(mapOf("RelTime" to "0:00:07", "TrackDuration" to "0:03:00"), answer)
    }

    @Test
    fun `a renderer's refusal is a fault with its UPnP code`() {
        FakeRenderer().use { renderer ->
            renderer.refuse = setOf("Stop")
            val device = UpnpDevice.parse(JdkUpnpHttp().get(renderer.descriptionUrl).body, renderer.descriptionUrl)

            val fault = assertThrows(UpnpFault::class.java) {
                UpnpSoap(JdkUpnpHttp()).call(device.service("AVTransport")!!, "Stop")
            }

            assertEquals(701, fault.code)
            assertEquals(500, fault.status)
        }
    }

    @Test
    fun `DIDL carries the title and address escaped, and the protocol info the renderer will check`() {
        val text = Didl.item("A <b> & \"c\"", "http://pi/x?a=1&b=2", Didl.protocolInfo("application/x-mpegURL"))

        assertTrue(text, text.contains("<dc:title>A &lt;b&gt; &amp; &quot;c&quot;</dc:title>"))
        assertTrue(text, text.contains(">http://pi/x?a=1&amp;b=2</res>"))
        assertTrue(text, text.contains("protocolInfo=\"http-get:*:application/x-mpegURL:*\""))
        assertTrue(UpnpXml.parse(text).documentElement != null)
    }

    @Test
    fun `times are H-MM-SS both ways, and a time that is not one is nothing`() {
        assertEquals("0:02:05", UpnpTime.format(125_900))
        assertEquals("1:00:00", UpnpTime.format(3_600_000))
        assertEquals("0:00:00", UpnpTime.format(-5))
        assertEquals(125_000L, UpnpTime.parse("0:02:05"))
        assertEquals(3_723_500L, UpnpTime.parse("01:02:03.500"))
        assertEquals(7_000L, UpnpTime.parse("0:00:07/0:03:00"))
        assertNull(UpnpTime.parse("NOT_IMPLEMENTED"))
        assertNull(UpnpTime.parse(""))
        assertNull(UpnpTime.parse(null))
        assertFalse(UpnpTime.parse("12:34") != null)
    }
}
