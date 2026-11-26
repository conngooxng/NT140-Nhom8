package dev.lab.vpn

import java.net.Socket

/**
 * Abstracts VpnService.protect() so proxy code stays testable off-device.
 *
 * The production implementation lives in [LabVpnService] (only a VpnService
 * can protect sockets). Java-side outbound sockets MUST be protected, or the
 * LOCAL scenario's own traffic would loop back into the TUN.
 */
interface SocketProtector {
    /** Marks [socket] to bypass the VPN. Returns false on failure. */
    fun protect(socket: Socket): Boolean
}
