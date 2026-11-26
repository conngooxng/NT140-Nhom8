package dev.lab.vpn

import android.os.ParcelFileDescriptor

/**
 * A tunnel moves IP packets between the TUN and the SOCKS5 proxy.
 *
 * Implementations are expected to protect their outbound sockets
 * ([VpnService.protect]) so proxy traffic does not loop back into the TUN.
 */
interface Tunnel {
    /** Begin forwarding IP packets from [fd] through the configured proxy. */
    fun start(service: LabVpnService, fd: ParcelFileDescriptor, config: TunnelConfig)

    /** Stop forwarding and release resources. Safe to call more than once. */
    fun stop()
}
