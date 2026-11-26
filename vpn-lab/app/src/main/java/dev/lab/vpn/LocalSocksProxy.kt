package dev.lab.vpn

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Minimal in-app SOCKS5 proxy that accepts CONNECT commands from the
 * tun2socks engine (LOCAL scenario) and relays target traffic through
 * outbound sockets protected by [SocketProtector].
 *
 * Why a Java-side proxy at all: the Go engine's sockets cannot call
 * VpnService.protect(), so tun2socks is pointed at this loopback listener
 * (127.0.0.1:1081) instead of the lab proxy. Outbound sockets created HERE
 * protect themselves, keeping victim traffic on the wire instead of looping
 * back into the TUN (the Java-side half of the loop-prevention design).
 *
 * Relay design: BOTH directions pump concurrently (one back-pump thread per
 * session), otherwise an HTTP client that keeps its request half open while
 * awaiting the response would deadlock. Half-close propagation: an EOF in
 * one direction shuts down the peer's output ([Socket.shutdownOutput]) so
 * pending data can drain; the final close happens in the session's finally.
 *
 * Testability: [dialer] is injectable, so JVM unit tests exercise both the
 * SOCKS5 parsing ([Socks5], pure functions) and the live concurrent relay.
 *
 * Bind configurability: [bindHost] defaults to the loopback address used by
 * the LOCAL scenario. The PEER scenario ([PeerProxyActivity]) binds
 * 0.0.0.0:1080 instead so a second Android device (device A, running the VPN
 * in PEER mode) can connect over the lab Wi-Fi. NOTE: an 0.0.0.0 listener on
 * a phone is only acceptable inside the lab AP network (10.42.0.0/24) —
 * never enable the peer proxy on any other network.
 */
class LocalSocksProxy(
    val port: Int,
    private val protector: SocketProtector,
    private val dialer: Dialer = ProtectedDialer(protector),
    val bindHost: String = LOCAL_HOST,
) {

    /** Creates the outbound socket for a CONNECT target. */
    fun interface Dialer {
        fun dial(host: String, port: Int): Socket
    }

    /** Default dialer: fresh socket, protected before connecting. */
    class ProtectedDialer(private val protector: SocketProtector) : Dialer {
        override fun dial(host: String, port: Int): Socket {
            val socket = Socket()
            check(protector.protect(socket)) { "protect() failed for $host:$port" }
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            return socket
        }
    }

    /**
     * Peer dialer: plain connect, NO protect() call. Device B (the peer
     * proxy) runs no VpnService, so there is nothing to protect against — its
     * relayed connections simply leave via the normal network interface.
     */
    class DirectDialer : Dialer {
        override fun dial(host: String, port: Int): Socket {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            return socket
        }
    }

    private var listener: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val active = mutableListOf<Socket>()
    @Volatile private var running = false

    /** The actually bound port (port 0 supported for tests). */
    val actualPort: Int
        get() = listener?.localPort ?: port

    /** Starts listening on [bindHost]:[port]. Idempotent. */
    @Synchronized
    fun start() {
        if (running) return
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getByName(bindHost), port))
        listener = server
        running = true
        acceptThread = Thread({ acceptLoop(server) }, "local-socks-accept").also { it.start() }
        Log.i(TAG, "socks5 proxy listening on $bindHost:$port")
    }

    /** Stops listening and closes in-flight sessions. Idempotent. */
    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        try {
            listener?.close()
        } catch (e: Exception) {
            Log.w(TAG, "listener close failed", e)
        } finally {
            listener = null
        }
        val toClose: List<Socket>
        synchronized(active) { toClose = active.toList(); active.clear() }
        toClose.forEach { try { it.close() } catch (_: Exception) {} }
        acceptThread = null
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running) {
            val client = try {
                server.accept()
            } catch (e: Exception) {
                // stop() closes the listener; anything else is transient
                // (too many open files, EINTR...) — log, keep accepting.
                if (running) {
                    Log.w(TAG, "accept failed, retrying: ${e.message}")
                    continue
                }
                break
            }
            if (!running) { try { client.close() } catch (_: Exception) {} ; break }
            synchronized(active) { active.add(client) }
            Thread({ serve(client) }, "local-socks-session").start()
        }
    }

    /** Handles one client: SOCKS5 handshake, protected outbound connect, relay. */
    internal fun serve(client: Socket) {
        var upstream: Socket? = null
        var handshakeDone = false
        try {
            client.tcpNoDelay = true
            val input = client.getInputStream()
            val output = client.getOutputStream()

            val target = Socks5.handshake(input, output)
            handshakeDone = true
            Log.i(TAG, "CONNECT -> ${target.host}:${target.port}")

            var sent = 0L
            var received = 0L
            try {
                upstream = dialer.dial(target.host, target.port).also {
                    it.tcpNoDelay = true
                    synchronized(active) { active.add(it) }
                }
            } catch (e: Exception) {
                // CONNECT failed after method selection: tell the client with
                // the protocol-correct failure reply, then close.
                Socks5.writeReply(output, Socks5.REP_CONNECT_FAILED)
                throw IllegalStateException("dial ${target.host}:${target.port} failed: ${e.message}", e)
            }
            Socks5.writeReply(output, Socks5.REP_SUCCESS)
            Log.i(TAG, "session open to ${target.host}:${target.port}, relaying")

            // Back pump (upstream -> client) runs in its own thread so a
            // client that keeps its request half open still gets responses.
            val backPump = Thread({
                received = relay("<- ${target.host}", upstream.getInputStream(), output)
                takeSafe { client.shutdownOutput() } // half-close: let pending data drain
            }, "local-socks-relay-back").also { it.start() }

            sent = relay("-> ${target.host}", input, upstream.getOutputStream())
            takeSafe { upstream.shutdownOutput() } // half-close: upstream drains pending data
            backPump.join(RELAY_JOIN_TIMEOUT_MS)
            Log.i(TAG, "${target.host}:${target.port} closed (sent=$sent, received=$received bytes)")
        } catch (e: Exception) {
            Log.w(TAG, "session failed: ${e.message}")
        } finally {
            closeQuietly(upstream)
            closeQuietly(client)
            synchronized(active) {
                active.remove(client)
                upstream?.let { active.remove(it) }
            }
        }
    }

    /** Runs [block], swallowing its exceptions (best-effort socket ops). */
    private inline fun takeSafe(block: () -> Unit) {
        try { block() } catch (_: Exception) {}
    }

    /** Copies [from] into [to], returning the byte count. */
    private fun relay(tag: String, from: InputStream, to: OutputStream): Long {
        val buf = ByteArray(RELAY_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val n = try { from.read(buf) } catch (_: Exception) { break }
            if (n < 0) break
            try {
                to.write(buf, 0, n)
                to.flush()
            } catch (_: Exception) {
                break
            }
            total += n
            if (total / LOG_BYTES_EVERY != (total - n) / LOG_BYTES_EVERY) {
                Log.d(TAG, "$tag $total bytes")
            }
        }
        return total
    }

    private fun closeQuietly(socket: Socket?) {
        try {
            socket?.close()
        } catch (_: Exception) {}
    }

    /** Test hook: number of currently tracked in-flight session sockets. */
    internal fun activeListSize(): Int {
        var n: Int
        synchronized(active) { n = active.size }
        return n
    }

    companion object {
        private const val TAG = "LocalSocksProxy"
        const val DEFAULT_PORT = 1081
        const val LOCAL_HOST = "127.0.0.1"
        /** Peer-mode listener: all interfaces on the well-known lab SOCKS5 port. */
        const val PEER_BIND_HOST = "0.0.0.0"
        const val PEER_PORT = 1080
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val RELAY_BUFFER_SIZE = 8 * 1024
        private const val LOG_BYTES_EVERY = 64 * 1024
        /** Bound on how long serve() waits for the back pump after half-close. */
        private const val RELAY_JOIN_TIMEOUT_MS = 5_000L
    }
}

/**
 * SOCKS5 wire-format helpers. The request parsing is a pure byte-array
 * function ([parseTarget]) so the handshake is unit-testable on the JVM.
 */
object Socks5 {

    /** Parsed CONNECT target. */
    data class Target(val host: String, val port: Int)

    /**
     * Runs greeting + CONNECT request exchange over [input]/[output] and
     * returns the target. Throws IOException on malformed/unsupported input.
     */
    fun handshake(input: InputStream, output: OutputStream): Target {
        val greeting = readExact(input, 2)
        if (greeting[0] != VERSION) throw IOException("not socks5 (ver=${greeting[0]})")
        val methods = readExact(input, greeting[1].toInt())
        if (!methods.contains(NO_AUTH)) throw IOException("no-auth method not offered")
        output.write(byteArrayOf(VERSION, NO_AUTH))
        output.flush()

        val request = readExact(input, 4)
        val addr = when (val atyp = request[3].toInt()) {
            ATYP_IPV4 -> readExact(input, 4)
            ATYP_IPV6 -> readExact(input, 16)
            ATYP_DOMAIN -> {
                val len = readExact(input, 1)
                len + readExact(input, len[0].toInt())
            }
            else -> throw IOException("unsupported address type $atyp")
        }
        return parseTarget(request + addr + readExact(input, 2))
    }

    /** Writes a reply with REP [rep] (bound 0.0.0.0:0; client ignores it). */
    fun writeReply(output: OutputStream, rep: Int = REP_SUCCESS) {
        output.write(REPLY_TEMPLATE.clone().also { it[1] = rep.toByte() })
        output.flush()
    }

    /**
     * Parses a CONNECT request: VER(1) CMD(1) RSV(1) ATYP(1) ADDR[-len] PORT(2).
     * [buf] may contain trailing bytes (streamed reads over-read).
     */
    fun parseTarget(buf: ByteArray): Target {
        require(buf.size >= REQ_MIN_LEN) { "short request (${buf.size} bytes)" }
        if (buf[0] != VERSION) throw IOException("bad request version ${buf[0]}")
        if (buf[1] != CMD_CONNECT.toByte()) throw IOException("only CONNECT supported (cmd=${buf[1]})")
        var i = REQ_MIN_LEN
        val host: String = when (val atyp = buf[3].toInt()) {
            ATYP_IPV4 -> {
                if (i + 4 + 2 > buf.size) throw IOException("short IPv4 address")
                parseIpv4(buf, i).also { i += 4 }
            }
            ATYP_DOMAIN -> {
                val n = buf[i++].toInt()
                if (i + n + 2 > buf.size) throw IOException("short domain")
                String(buf, i, n, Charsets.US_ASCII).also { i += n }
            }
            ATYP_IPV6 -> {
                if (i + 16 + 2 > buf.size) throw IOException("short IPv6 address")
                parseIpv6(buf, i).also { i += 16 }
            }
            else -> throw IOException("unsupported address type $atyp")
        }
        if (i + 2 > buf.size) throw IOException("short port")
        val port = ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
        return Target(host, port)
    }

    private fun parseIpv4(buf: ByteArray, off: Int): String =
        (0 until 4).joinToString(".") { (buf[off + it].toInt() and 0xFF).toString() }

    /** Formats IPv6 without a java.net dependency (max 8 groups, "x:x:...:x"). */
    private fun parseIpv6(buf: ByteArray, off: Int): String =
        (0 until 8).joinToString(":") {
            "%x".format(((buf[off + it * 2].toInt() and 0xFF) shl 8) or (buf[off + it * 2 + 1].toInt() and 0xFF))
        }

    private fun readExact(input: InputStream, n: Int): ByteArray {
        require(n >= 0)
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r < 0) throw IOException("stream closed mid-handshake")
            off += r
        }
        return out
    }

    internal const val VERSION: Byte = 0x05
    internal const val NO_AUTH: Byte = 0x00
    internal const val CMD_CONNECT: Int = 1
    internal const val ATYP_IPV4: Int = 1
    internal const val ATYP_DOMAIN: Int = 3
    internal const val ATYP_IPV6: Int = 4
    private val REPLY_TEMPLATE = byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)
    /** REP codes (RFC 1928). */
    internal const val REP_SUCCESS: Int = 0x00
    internal const val REP_CONNECT_FAILED: Int = 0x01
    private const val REQ_MIN_LEN = 4
}
