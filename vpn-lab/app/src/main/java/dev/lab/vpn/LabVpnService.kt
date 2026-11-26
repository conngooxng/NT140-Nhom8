package dev.lab.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.net.Socket

/**
 * The lab VPN gateway. Establishes a TUN open to exactly one app
 * (dev.lab.victim) and hands the TUN fd to a [Tunnel] (tun2socks) so its
 * traffic flows through the selected SOCKS5 proxy. Runs as a foreground
 * service with a persistent notification while the tunnel is up.
 *
 * Start with action [ACTION_START] and the tunnel-config extras; stop with
 * [ACTION_STOP]. Later tasks add the local SOCKS proxy (whose sockets must be
 * protected via VpnService.protect) and scenario-specific routing behavior.
 */
class LabVpnService : VpnService() {

    companion object {
        const val VICTIM_PACKAGE = "dev.lab.victim"

        const val ACTION_START = "dev.lab.vpn.START"
        const val ACTION_STOP = "dev.lab.vpn.STOP"

        const val EXTRA_SCENARIO = "dev.lab.vpn.extra.SCENARIO"
        const val EXTRA_PROXY_HOST = "dev.lab.vpn.extra.PROXY_HOST"
        const val EXTRA_PROXY_PORT = "dev.lab.vpn.extra.PROXY_PORT"
        const val EXTRA_ROUTE_ALL = "dev.lab.vpn.extra.ROUTE_ALL"
        const val EXTRA_INCLUDE_IPV6 = "dev.lab.vpn.extra.INCLUDE_IPV6"
        const val EXTRA_DNS_SERVER = "dev.lab.vpn.extra.DNS_SERVER"

        private const val TAG = "LabVpnService"
        private const val TUN_ADDR = "10.255.0.2"
        private const val MTU = 1500
        private const val CHANNEL_ID = "vpn-lab-tunnel"
        private const val NOTIFICATION_ID = 1
    }

    private var tunnel: Tunnel? = null
    private var localProxy: LocalSocksProxy? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_START -> startTunnel(configFromIntent(intent))
            else -> Log.w(TAG, "ignoring intent without action ${intent?.action}")
        }
        return Service.START_NOT_STICKY
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    /** Parses the tunnel config from a start intent. */
    fun configFromIntent(intent: Intent): TunnelConfig {
        val scenario = intent.getStringExtra(EXTRA_SCENARIO)?.let { name ->
            Scenario.entries.firstOrNull { it.name == name }
        } ?: Scenario.BASELINE
        return TunnelConfig(
            scenario = scenario,
            proxyHost = intent.getStringExtra(EXTRA_PROXY_HOST) ?: "10.42.0.1",
            proxyPort = intent.getIntExtra(EXTRA_PROXY_PORT, 1080),
            // Scenario-aware defaults (Task 9 fix): a null-valued EXTRA_DNS_SERVER
            // (Bundle stores null) must NOT re-default to 10.42.0.2 for DNS_LEAK,
            // and a bare EXTRA_SCENARIO=DNS_LEAK must not establish 0.0.0.0/0.
            routeAll = LeakConfig.routeAllFor(
                scenario,
                if (intent.hasExtra(EXTRA_ROUTE_ALL)) intent.getBooleanExtra(EXTRA_ROUTE_ALL, false) else null,
            ),
            includeIpv6 = intent.getBooleanExtra(EXTRA_INCLUDE_IPV6, false),
            dnsServer = LeakConfig.dnsServerFor(scenario, intent.getStringExtra(EXTRA_DNS_SERVER)),
        )
    }

    /** Establishes the TUN and starts forwarding through the proxy. */
    fun startTunnel(config0: TunnelConfig) {
        stopTunnel()
        var config = config0

        if (config.scenario == Scenario.LOCAL) {
            // tun2socks' Go sockets cannot call protect(), so its outbound
            // sockets are not covered by the victim-only allowlist trick.
            // Instead, point it at an in-app SOCKS5 proxy whose outbound
            // sockets ARE protected.
            config = config.copy(proxyHost = LocalSocksProxy.LOCAL_HOST, proxyPort = LocalSocksProxy.DEFAULT_PORT)
        } else {
            // CLOUD (and BASELINE/PEER/leak scenarios): tun2socks forwards
            // directly to the remote mitmproxy SOCKS5 listener at
            // proxyHost:proxyPort (10.42.0.1:1080), simulating an untrusted
            // "cloud" VPN exit (Task 7). No local-proxy override, and the
            // in-app proxy must NOT be running here.
            stopLocalProxy()
        }

        // Reach startForeground BEFORE any failing step so startForegroundService
        // never sees a service that returns without a foreground notification.
        startForeground(NOTIFICATION_ID, buildNotification(config))

        try {
            if (config.scenario == Scenario.LOCAL) {
                startLocalProxy()
            }
            val fd = establishInterface(config)
            val t = Tun2SocksAdapter()
            t.start(this, fd, config)
            tunnel = t
        } catch (e: Exception) {
            // Keep the app alive and in the same graceful failure path whether
            // the local proxy bind or the TUN establishment fails: log, surface
            // to the user, release resources, stop the service.
            Log.e(TAG, "failed to start tunnel", e)
            android.widget.Toast.makeText(
                this, "tunnel start failed: ${e.message}", android.widget.Toast.LENGTH_LONG
            ).show()
            stopLocalProxy()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** Starts the in-app SOCKS5 proxy used by the LOCAL scenario. */
    private fun startLocalProxy() {
        stopLocalProxy()
        val proxy = LocalSocksProxy(LocalSocksProxy.DEFAULT_PORT, object : SocketProtector {
            override fun protect(socket: Socket): Boolean = this@LabVpnService.protect(socket)
        })
        // Throws on bind failure; the caller's catch turns it into the
        // graceful failure path instead of an app crash.
        proxy.start()
        localProxy = proxy
    }

    /** Stops the in-app SOCKS5 proxy, if running. */
    private fun stopLocalProxy() {
        localProxy?.stop()
        localProxy = null
    }

    /** Tears down the active tunnel and any local proxy, if any. */
    fun stopTunnel() {
        tunnel?.stop()
        tunnel = null
        stopLocalProxy()
    }

    /**
     * Builds the TUN (routing decided by [LeakConfig]): routeAll → 0.0.0.0/0;
     * DNS_LEAK → only the gateway 10.42.0.1/32 with NO pushed DNS (so DNS to
     * 10.42.0.2 goes OUTSIDE the VPN); other narrow-route scenarios → the lab
     * subnet. Optionally routes ::/0 (NOT for IPV6_LEAK), pushes the lab DNS
     * server when configured, and allows ONLY the victim app.
     *
     * Loop prevention: the victim-only allowlist automatically excludes this
     * app's own UID, so the engine's SOCKS5 connections (and any other
     * vpn-lab traffic) leave via the default interface and never re-enter
     * the TUN. (addDisallowedApplication is mutually exclusive with
     * addAllowedApplication and would throw.) Java-side sockets must still
     * call VpnService.protect().
     */
    private fun establishInterface(config: TunnelConfig): ParcelFileDescriptor {
        // Make leak-scenario behavior explicit up front (Task 9).
        LeakConfig.leakNotice(config.scenario)?.let { notice ->
            Log.i(TAG, notice)
        }

        val builder = Builder()
            .setMtu(MTU)
            .addAddress(TUN_ADDR, 32)

        val ipv4Route = LeakConfig.ipv4Route(config.scenario, config.routeAll)
        val (addr, prefix) = ipv4Route.split("/").let { it[0] to it[1].toInt() }
        builder.addRoute(addr, prefix)
        if (config.includeIpv6) {
            builder.addRoute("::", 0)
        }

        config.dnsServer?.let { builder.addDnsServer(it) }

        builder.setSession("vpn-lab: ${config.scenario}")
        builder.addAllowedApplication(VICTIM_PACKAGE)

        return builder.establish()
            ?: error("VpnService.establish() returned null; user must grant VPN permission")
    }

    /** Persistent, low-importance notification required for the foreground VPN service. */
    private fun buildNotification(config: TunnelConfig): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "vpn-lab tunnel", NotificationManager.IMPORTANCE_LOW)
        )
        val leakNote = LeakConfig.leakNotice(config.scenario)
        val text = buildString {
            append("scenario ${config.scenario} -> socks5://${config.proxyHost}:${config.proxyPort}")
            leakNote?.let { append(" | ").append(it) }
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("vpn-lab tunnel running")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }
}
