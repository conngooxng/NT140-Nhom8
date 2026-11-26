package dev.lab.vpn

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Live-relay tests for the LOCAL scenario's in-app proxy, running against
 * real loopback sockets (android.util.Log stubbed via
 * unitTests.isReturnDefaultValues = true). The Dialer seam is used so the
 * tests dial the local echo target directly without Android protection.
 */
class LocalSocksProxyRelayTest {

    /** Single-shot loopback echo server: echoes bytes until our EOF. */
    private fun startEchoThread(): Pair<ServerSocket, Thread> {
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val thread = Thread {
            val client = server.accept()
            try {
                val buf = ByteArray(4096)
                while (true) {
                    val n = client.getInputStream().read(buf)
                    if (n < 0) break
                    client.getOutputStream().write(buf, 0, n)
                    client.getOutputStream().flush()
                }
            } finally {
                client.close()
            }
        }.also { it.start() }
        return server to thread
    }

    private val noopProtector = object : SocketProtector {
        override fun protect(socket: Socket): Boolean = true
    }

    /** SOCKS5 CONNECT handshake from a test client; returns the reply. */
    private fun socks5Connect(client: Socket, targetPort: Int): ByteArray {
        client.getOutputStream().write(byteArrayOf(5, 1, 0))
        client.getOutputStream().flush()
        val method = ByteArray(2).also { readFully(client, it) }
        assertEquals(5, method[0].toInt())
        assertEquals(0, method[1].toInt())
        client.getOutputStream().write(
            byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1) +
                byteArrayOf((targetPort shr 8).toByte(), (targetPort and 0xFF).toByte())
        )
        client.getOutputStream().flush()
        val reply = ByteArray(10).also { readFully(client, it) }
        assertEquals(5, reply[0].toInt())
        return reply
    }

    private fun readFully(socket: Socket, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = socket.getInputStream().read(buf, off, buf.size - off)
            if (n < 0) throw IOException("unexpected EOF in test handshake")
            off += n
        }
    }

    @Test
    fun `concurrent relays a response while the request half stays open`() {
        // This is the regression test for the sequential-relay deadlock: an
        // HTTP client keeps its request side open while awaiting the response,
        // so the response MUST arrive before we close our send side.
        val (echoServer, echoThread) = startEchoThread()
        val proxy = LocalSocksProxy(0, noopProtector,
            LocalSocksProxy.Dialer { h, p -> Socket(h, p) })
        proxy.start()
        try {
            val client = Socket(InetAddress.getLoopbackAddress(), proxy.actualPort)
            client.soTimeout = 5_000
            val reply = socks5Connect(client, echoServer.localPort)
            assertEquals(Socks5.REP_SUCCESS, reply[1].toInt())

            // Send a request WITHOUT closing or half-closing the connection.
            client.getOutputStream().write("ping".toByteArray())
            client.getOutputStream().flush()

            // The echo must come back while we are still "awaiting".
            val ack = ByteArray(4).also { readFully(client, it) }
            assertEquals("ping", String(ack))
        } finally {
            proxy.stop()
            echoServer.close()
            echoThread.join(5_000)
        }
    }

    @Test
    fun `half close propagates and pending data drains before close`() {
        val (echoServer, echoThread) = startEchoThread()
        val proxy = LocalSocksProxy(0, noopProtector,
            LocalSocksProxy.Dialer { h, p -> Socket(h, p) })
        proxy.start()
        try {
            val client = Socket(InetAddress.getLoopbackAddress(), proxy.actualPort)
            client.soTimeout = 5_000
            assertEquals(Socks5.REP_SUCCESS, socks5Connect(client, echoServer.localPort)[1].toInt())

            client.getOutputStream().write("hello".toByteArray())
            client.getOutputStream().flush()
            client.shutdownOutput() // client → upstream EOF
            echoThread.join(5_000) // echo drains and closes after our EOF

            val echoed = readUntilEof(client)
            assertEquals("hello", String(echoed))

            // Session ended (client hit EOF); the proxy must not have leaked
            // its tracked in-flight sockets. Poll briefly for teardown.
            val deadline = System.currentTimeMillis() + 5_000
            var tracked = -1
            while (System.currentTimeMillis() < deadline) {
                tracked = proxy.activeCount()
                if (tracked == 0) break
                Thread.sleep(20)
            }
            assertEquals(0, tracked)
        } finally {
            proxy.stop()
            echoServer.close()
        }
    }

    private fun readUntilEof(socket: Socket): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) {
            val n = try { socket.getInputStream().read(buf) } catch (e: Exception) { break }
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    @Test
    fun `dial failure returns SOCKS5 failure reply then closes`() {
        val proxy = LocalSocksProxy(
            0, noopProtector,
            LocalSocksProxy.Dialer { _, _ -> throw IOException("refused") },
        )
        proxy.start()
        try {
            val client = Socket(InetAddress.getLoopbackAddress(), proxy.actualPort)
            client.soTimeout = 5_000
            val reply = socks5Connect(client, 8080) // target unreachable anyway; dialer fails
            assertEquals(Socks5.REP_CONNECT_FAILED, reply[1].toInt())
            // Client must see EOF promptly afterwards.
            assertEquals(-1, client.getInputStream().read())
            client.close()
        } finally {
            proxy.stop()
        }
    }

    @Test
    fun `bindHost is configurable so PEER can listen on an arbitrary interface`() {
        // Same relay roundtrip as above, but the listener binds a configured
        // host ("localhost" resolves to loopback) instead of the default
        // 127.0.0.1. The PEER scenario uses this with "0.0.0.0" (device B
        // accepts connections from device A over the lab Wi-Fi).
        val (echoServer, echoThread) = startEchoThread()
        val proxy = LocalSocksProxy(
            0, noopProtector,
            LocalSocksProxy.Dialer { h, p -> Socket(h, p) },
            bindHost = "localhost",
        )
        assertEquals("localhost", proxy.bindHost)
        proxy.start()
        try {
            val client = Socket(InetAddress.getLoopbackAddress(), proxy.actualPort)
            client.soTimeout = 5_000
            assertEquals(Socks5.REP_SUCCESS, socks5Connect(client, echoServer.localPort)[1].toInt())
            client.getOutputStream().write("hi".toByteArray())
            client.getOutputStream().flush()
            val ack = ByteArray(2).also { readFully(client, it) }
            assertEquals("hi", String(ack))
            client.close()
        } finally {
            proxy.stop()
            echoServer.close()
            echoThread.join(5_000)
        }
    }

    @Test
    fun `DirectDialer connects without protecting the socket`() {
        val echoServer = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val socketsReachingEcho = mutableListOf<Socket>()
        val accepted = Thread {
            val socket = echoServer.accept()
            synchronized(socketsReachingEcho) { socketsReachingEcho.add(socket) }
            try {
                val first = socket.getInputStream().read()
                if (first >= 0) socket.getOutputStream().write(first)
                socket.close()
            } catch (_: Exception) {}
        }.also { it.start() }
        // No SocketProtector should be invoked: DirectDialer takes none.
        val socket = LocalSocksProxy.DirectDialer().dial(InetAddress.getLoopbackAddress().hostAddress!!, echoServer.localPort)
        try {
            socket.getOutputStream().write('x'.code)
            socket.getOutputStream().flush()
            assertEquals('x'.code, socket.getInputStream().read())
            synchronized(socketsReachingEcho) { assertEquals(1, socketsReachingEcho.size) }
        } finally {
            socket.close()
            accepted.join(5_000)
            echoServer.close()
        }
    }

    @Test
    fun `peer scenario constants are the documented 0_0_0_0_1080`() {
        assertEquals("0.0.0.0", LocalSocksProxy.PEER_BIND_HOST)
        assertEquals(1080, LocalSocksProxy.PEER_PORT)
        assertEquals(1080, MainActivity.DEFAULT_PROXY_PORT)
    }

    /** Test-visible helper over the internal session tracker. */
    private fun LocalSocksProxy.activeCount(): Int = activeListSize()
}
