package dev.lab.vpn

import android.os.ParcelFileDescriptor
import engine.Engine
import engine.Key

/**
 * Bridges the TUN to a SOCKS5 proxy using the gomobile-bound tun2socks engine
 * (xjasonlyu/tun2socks v2, package `engine`) from app/libs/tun2socks.aar.
 *
 * Packet flow: the Go side owns the TUN file descriptor (device "fd://<fd>"),
 * so the Kotlin side does not run its own read/write loop; it installs a Key
 * and starts/stops the engine.
 *
 * Loopback avoidance: the victim-only allowlist automatically excludes this
 * app's own UID, so the engine's SOCKS5 sockets leave via the default
 * interface and never re-enter the TUN. Java-side sockets (e.g. the later
 * LocalSocksProxy) must call LabVpnService.protect()/VpnService.protect().
 */
class Tun2SocksAdapter : Tunnel {

    private var running = false

    @Synchronized
    override fun start(service: LabVpnService, fd: ParcelFileDescriptor, config: TunnelConfig) {
        check(!running) { "tunnel already running" }
        require(config.proxyHost.isNotBlank()) { "empty proxy host" }
        require(config.proxyPort in 1..65535) { "bad proxy port: ${config.proxyPort}" }

        // Engine fully owns (and closes) the duplicated descriptor; the
        // original is closed through the PFD in the finally block.
        val engineFd = fd.dup().detachFd()
        try {
            Engine.insert(key(engineFd, config))
            Engine.start()
            running = true
        } catch (e: Exception) {
            closeFdQuietly(engineFd)
            throw IllegalStateException("tun2socks engine failed to start: ${e.message}", e)
        } finally {
            fd.close()
        }
    }

    @Synchronized
    override fun stop() {
        if (!running) return
        try {
            Engine.stop()
        } catch (e: Exception) {
            android.util.Log.w("Tun2SocksAdapter", "engine stop failed", e)
        } finally {
            running = false
        }
    }

    private fun key(engineFd: Int, config: TunnelConfig): Key {
        return Key().apply {
            device = "fd://$engineFd"
            proxy = "socks5://${config.proxyHost}:${config.proxyPort}"
            mtu = DEFAULT_MTU.toLong()
            logLevel = DEFAULT_LOG_LEVEL
        }
    }

    companion object {
        const val DEFAULT_MTU = 1500
        const val DEFAULT_LOG_LEVEL = "warn"

        /**
         * Closes a raw fd without a ParcelFileDescriptor: android.system.Os.close
         * needs a FileDescriptor, whose fd field is hidden, so set it reflectively.
         */
        fun closeFdQuietly(fd: Int) {
            try {
                val descriptor = java.io.FileDescriptor()
                val field = java.io.FileDescriptor::class.java.getDeclaredField("descriptor")
                field.isAccessible = true
                field.setInt(descriptor, fd)
                android.system.Os.close(descriptor)
            } catch (_: Throwable) {
                // best-effort: only reached on engine start-up failure
            }
        }
    }
}
