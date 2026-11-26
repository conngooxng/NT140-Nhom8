package dev.lab.vpn

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * JVM unit tests for the SOCKS5 wire parsing used by the LOCAL scenario's
 * in-app proxy. Covers handshake framing (IPv4/domain/IPv6 targets) and
 * malformed-input rejection. The relay itself needs android.util.Log plus
 * real sockets, so it is verified on-device (see README).
 */
class LocalSocksProxyTest {

    private fun request(atyp: Byte, addr: ByteArray, port: Int): ByteArray {
        val portBytes = byteArrayOf((port shr 8).toByte(), (port and 0xFF).toByte())
        return when (atyp.toInt()) {
            Socks5.ATYP_IPV4, Socks5.ATYP_IPV6 ->
                byteArrayOf(5, 1, 0, atyp) + addr + portBytes
            else -> throw IllegalArgumentException()
        }
    }

    private fun domainRequest(domain: String, port: Int): ByteArray {
        val d = domain.toByteArray(Charsets.US_ASCII)
        return byteArrayOf(5, 1, 0, Socks5.ATYP_DOMAIN.toByte(), d.size.toByte()) + d +
            byteArrayOf((port shr 8).toByte(), (port and 0xFF).toByte())
    }

    @Test
    fun parsesIpv4Target() {
        val t = Socks5.parseTarget(request(0x01, byteArrayOf(10, 42, 0, 1), 8080))
        assertEquals("10.42.0.1", t.host)
        assertEquals(8080, t.port)
    }

    @Test
    fun parsesDomainTarget() {
        val t = Socks5.parseTarget(domainRequest("lab.example", 443))
        assertEquals("lab.example", t.host)
        assertEquals(443, t.port)
    }

    @Test
    fun parsesIpv6Target() {
        val addr = ByteArray(16).also { it[15] = 1 }
        val t = Socks5.parseTarget(request(0x04, addr, 9999))
        // Groups are formatted uncompressed (no :: compression); good enough
        // for lab logging purposes.
        assertEquals("0:0:0:0:0:0:0:1", t.host)
        assertEquals(9999, t.port)
    }

    @Test
    fun rejectsShortRequest() {
        try {
            Socks5.parseTarget(byteArrayOf(5, 1, 0))
            throw AssertionError("expected exception")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    @Test
    fun rejectsWrongVersion() {
        try {
            Socks5.parseTarget(request(0x01, byteArrayOf(1, 1, 1, 1), 1).also { it[0] = 4 })
            throw AssertionError("expected exception")
        } catch (_: IOException) { /* expected */ }
    }

    @Test
    fun fullHandshakeParsesDomainRequest() {
        val greeting = byteArrayOf(5, 1, 0)
        val upstream = ByteArrayInputStream(greeting + domainRequest("10.42.0.1", 8080))
        val downstream = ByteArrayOutputStream()
        val target = Socks5.handshake(upstream, downstream)
        assertEquals("10.42.0.1", target.host)
        assertEquals(8080, target.port)
        // Server picked the no-auth method.
        assertEquals(byteArrayOf(5, 0).toList(), downstream.toByteArray().toList())
    }

    @Test
    fun handshakeFailsWithoutNoAuth() {
        val upstream = ByteArrayInputStream(byteArrayOf(5, 1, 2))
        try {
            Socks5.handshake(upstream, ByteArrayOutputStream())
            throw AssertionError("expected exception")
        } catch (_: IOException) { /* expected */ }
    }

    @Test
    fun writeReplyIsTenByteSuccess() {
        val out = ByteArrayOutputStream()
        Socks5.writeReply(out)
        assertEquals(10, out.size())
        assertEquals(0, out.toByteArray()[1].toInt())
    }
}
