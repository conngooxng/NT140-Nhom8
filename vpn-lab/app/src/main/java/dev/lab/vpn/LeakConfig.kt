package dev.lab.vpn

/**
 * Leak-scenario helpers (Task 9). Pure logic so it can be unit-tested on the
 * JVM (no Android imports).
 *
 * DNS_LEAK: the TUN gets ONLY the route to the gateway [GATEWAY_HOST]/32.
 * Every other destination — in particular the lab resolver at 10.42.0.2 —
 * stays OUTSIDE the VPN, and because no DNS server is pushed into the TUN
 * (dnsServer = null), the phone resolves names via the DHCP-provided
 * resolver 10.42.0.2 over the plain Wi-Fi link. The DNS queries are then
 * directly visible in the capture (the "leak": DNS traffic the VPN does not
 * carry).
 *
 * IPV6_LEAK: the TUN establishes WITHOUT a ::/0 route (includeIpv6 = false).
 * Any IPv6-capable destination therefore bypasses the VPN entirely.
 */
object LeakConfig {

    /** Lab gateway (HTTP/HTTPS/mitmproxy host) — the ONLY host routed into the TUN for DNS_LEAK. */
    const val GATEWAY_HOST = "10.42.0.1"

    /** Lab resolver (dnsmasq) — deliberately NOT routed into the TUN for DNS_LEAK. */
    const val DNS_HOST = "10.42.0.2"

    /** IPv4 split-tunnel route pushed into the TUN for [config]. */
    fun ipv4Route(scenario: Scenario, routeAll: Boolean): String = when {
        routeAll -> "0.0.0.0/0"
        scenario == Scenario.DNS_LEAK -> "$GATEWAY_HOST/32" // split tunnel: only the gateway
        else -> "10.42.0.0/24" // other narrow-route scenarios: whole lab subnet
    }

    /**
     * Human-readable leak explanation shown in the notification and logged at
     * tunnel start. Null for scenarios without a leak-related note.
     */
    fun leakNotice(scenario: Scenario): String? = when (scenario) {
        Scenario.DNS_LEAK ->
            "DNS_LEAK: no DNS in TUN; resolver $DNS_HOST resolved OUTSIDE the VPN (queries bypass the tunnel)"
        Scenario.IPV6_LEAK ->
            "IPV6_LEAK: no ::/0 route in TUN; IPv6 destinations bypass the VPN"
        else -> null
    }

    // ---- Intent-extra defaulting (used by LabVpnService.configFromIntent) ----
    //
    // An Intent extra that is ABSENT and one that is present-but-null are
    // indistinguishable via getStringExtra/getBooleanExtra (both return null /
    // the fallback). These helpers give DNS_LEAK scenario-correct defaults even
    // when MainActivity sends null-valued extras (putExtra(name, null) on a
    // String extra) or supplies ONLY EXTRA_SCENARIO on an adb-initiated intent.

    /** Default for EXTRA_ROUTE_ALL when the intent does not set it. */
    fun defaultRouteAll(scenario: Scenario): Boolean = scenario != Scenario.DNS_LEAK

    /** Default for EXTRA_DNS_SERVER when the intent does not set it (or sets it to null). */
    fun defaultDnsServer(scenario: Scenario): String? =
        if (scenario == Scenario.DNS_LEAK) null else DNS_HOST

    /** Resolves EXTRA_ROUTE_ALL (null = extra absent / not readable) to a value. */
    fun routeAllFor(scenario: Scenario, extraRouteAll: Boolean?): Boolean =
        extraRouteAll ?: defaultRouteAll(scenario)

    /** Resolves EXTRA_DNS_SERVER (null = extra absent OR present-but-null) to a value. */
    fun dnsServerFor(scenario: Scenario, extraDnsServer: String?): String? =
        extraDnsServer ?: defaultDnsServer(scenario)
}
