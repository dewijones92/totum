package com.dewijones92.totum.cli.serve.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class PinnedTrustTest {

    private val certificate: X509Certificate = CertificateFactory.getInstance("X.509")
        .generateCertificate(ByteArrayInputStream(PEM.toByteArray())) as X509Certificate

    private val fingerprint = "3bf72f4af55c4acdb9776831a2a73c6e38299740e155a4d1973c827d4848a5a5"

    @Test
    fun `a certificate is named by the SHA-256 of its encoding, in lower case hex`() {
        assertEquals(fingerprint, PinnedTrust.fingerprintOf(certificate))
    }

    @Test
    fun `with nothing pinned any certificate is accepted and the one seen is remembered`() {
        val trust = PinnedTrust(null)
        assertNull(trust.seen)

        trust.checkServerTrusted(arrayOf(certificate), "ECDHE_ECDSA")

        assertEquals(fingerprint, trust.seen)
    }

    @Test
    fun `the pinned certificate is accepted whatever the case of its fingerprint`() {
        PinnedTrust(fingerprint).checkServerTrusted(arrayOf(certificate), "ECDHE_ECDSA")
        PinnedTrust(fingerprint.uppercase()).checkServerTrusted(arrayOf(certificate), "ECDHE_ECDSA")
    }

    @Test
    fun `any other certificate is refused with words that say to pair again`() {
        val trust = PinnedTrust("0".repeat(fingerprint.length))

        val refused = runCatching { trust.checkServerTrusted(arrayOf(certificate), "ECDHE_ECDSA") }.exceptionOrNull()

        assertTrue(refused is CertificateException)
        assertTrue(refused!!.message!!, refused.message!!.contains("not the one it had when it was paired"))
        assertEquals("what it presented is still recorded", fingerprint, trust.seen)
    }

    @Test
    fun `a client certificate is never accepted`() {
        val refused = runCatching {
            PinnedTrust(
                null
            ).checkClientTrusted(arrayOf(certificate), "RSA")
        }.exceptionOrNull()

        assertTrue(refused is CertificateException)
    }

    private companion object {
        const val PEM = """-----BEGIN CERTIFICATE-----
MIIBhjCCAS2gAwIBAgIUcabRXuHutZwBHVmTZVf4jL698aQwCgYIKoZIzj0EAwIw
GDEWMBQGA1UEAwwNdG90dW0tdGVzdC10djAgFw0yNjExMjAxNzQ5MDlaGA8yMTI2
MTAyNzE3NDkwOVowGDEWMBQGA1UEAwwNdG90dW0tdGVzdC10djBZMBMGByqGSM49
AgEGCCqGSM49AwEHA0IABBZDe/ORWj9jXJFIWMjeWHN2M28BvYabLB6JaGxXtt8d
DN+S6pouDHNXgKw9STz5Kw3v9FA6xMHdhuiRu9doJNyjUzBRMB0GA1UdDgQWBBQr
LQE1VsdfNR8qC1tiHRgLJFlshTAfBgNVHSMEGDAWgBQrLQE1VsdfNR8qC1tiHRgL
JFlshTAPBgNVHRMBAf8EBTADAQH/MAoGCCqGSM49BAMCA0cAMEQCICIBW/2/vt1v
oDLYcBFs4JbWycgK2Luiz3PPcA2sFnlpAiANwSDRmA5nrCGxfXbeT3RcxjfFcBC0
CjrO4763Y7PxSw==
-----END CERTIFICATE-----
"""
    }
}
