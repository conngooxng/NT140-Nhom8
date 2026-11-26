package dev.lab.vpn

/**
 * Lab scenario under test. The VPN service adapts its routing/leak behavior
 * based on the selected scenario (later tasks wire scenario-specific knobs).
 */
enum class Scenario {
    BASELINE,
    LOCAL,
    CLOUD,
    PEER,
    DNS_LEAK,
    IPV6_LEAK
}
