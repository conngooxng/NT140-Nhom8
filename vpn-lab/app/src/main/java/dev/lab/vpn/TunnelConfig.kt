package dev.lab.vpn

/**
 * Everything tun2socks needs to shuttle TUN packets through the lab proxy.
 *
 * @param scenario      lab scenario under test
 * @param proxyHost     SOCKS5 proxy address (mitmproxy listens on 10.42.0.1:1080)
 * @param proxyPort     SOCKS5 proxy port
 * @param routeAll      true: route all victim traffic through the TUN; false: only lab subnets
 * @param includeIpv6   true: also route IPv6 traffic (used by IPV6_LEAK checks)
 * @param dnsServer     DNS server pushed into the TUN (lab resolver on 10.42.0.2)
 */
data class TunnelConfig(
    val scenario: Scenario,
    val proxyHost: String,
    val proxyPort: Int,
    val routeAll: Boolean = true,
    val includeIpv6: Boolean = false,
    val dnsServer: String? = "10.42.0.2",
)
