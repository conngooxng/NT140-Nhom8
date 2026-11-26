package dev.lab.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LeakConfigTest {

    private fun config(
        scenario: Scenario,
        routeAll: Boolean = true,
        dnsServer: String? = "10.42.0.2",
    ) = TunnelConfig(
        scenario = scenario,
        proxyHost = "10.42.0.1",
        proxyPort = 1080,
        routeAll = routeAll,
        includeIpv6 = false,
        dnsServer = dnsServer,
    )

    @Test
    fun `routeAll routes everything`() {
        assertEquals("0.0.0.0/0", LeakConfig.ipv4Route(Scenario.CLOUD, routeAll = true))
    }

    @Test
    fun `DNS_LEAK routes only the gateway into the TUN`() {
        assertEquals(
            "10.42.0.1/32",
            LeakConfig.ipv4Route(Scenario.DNS_LEAK, routeAll = false),
        )
    }

    @Test
    fun `DNS_LEAK has no pushed DNS by default`() {
        // configFromIntent path: explicit null / absent extra must stay null
        assertNull(LeakConfig.dnsServerFor(Scenario.DNS_LEAK, config(Scenario.DNS_LEAK, routeAll = false, dnsServer = null).dnsServer))
    }

    @Test
    fun `other narrow scenarios keep lab subnet route`() {
        assertEquals(
            "10.42.0.0/24",
            LeakConfig.ipv4Route(Scenario.PEER, routeAll = false),
        )
    }

    @Test
    fun `DNS_LEAK and IPV6_LEAK produce leak notices`() {
        assert(LeakConfig.leakNotice(Scenario.DNS_LEAK)!!.contains("10.42.0.2"))
        assert(LeakConfig.leakNotice(Scenario.IPV6_LEAK)!!.contains("::/0"))
        assertNull(LeakConfig.leakNotice(Scenario.BASELINE))
        assertNull(LeakConfig.leakNotice(Scenario.CLOUD))
        assertNull(LeakConfig.leakNotice(Scenario.LOCAL))
        assertNull(LeakConfig.leakNotice(Scenario.PEER))
    }

    // ---- Intent-extra defaulting (mirrors LabVpnService.configFromIntent) ----
    // configFromIntent itself needs an android.content.Intent and is
    // instrumentation-test only; these tests pin the pure defaulting logic it
    // delegates to, covering the reviewer's two bug paths:
    //  (a) null-valued EXTRA_DNS_SERVER from MainActivity's Bundle.putExtra
    //  (b) bare EXTRA_SCENARIO=DNS_LEAK intent with no route/DNS extras
    // Tests cover (a) via dnsServerFor — see `DNS_LEAK stays null even when the
    // extra is present-but-null`.
    @Test
    fun `DNS_LEAK stays null even when the extra is present-but-null`() {
        // null extra = absent OR present-with-null-value (Bundle putExtra(name, null))
        assertNull(LeakConfig.dnsServerFor(Scenario.DNS_LEAK, null))
    }

    @Test
    fun `non-leak scenarios default pushed DNS when extra absent`() {
        assertEquals("10.42.0.2", LeakConfig.dnsServerFor(Scenario.BASELINE, null))
        assertEquals("10.42.0.2", LeakConfig.dnsServerFor(Scenario.CLOUD, null))
        assertEquals("10.42.0.2", LeakConfig.dnsServerFor(Scenario.IPV6_LEAK, null))
    }

    @Test
    fun `explicit DNS extra always wins`() {
        assertEquals("10.42.0.9", LeakConfig.dnsServerFor(Scenario.DNS_LEAK, "10.42.0.9"))
        assertEquals("10.42.0.9", LeakConfig.dnsServerFor(Scenario.CLOUD, "10.42.0.9"))
    }

    @Test
    fun `routeAll default is scenario-aware for bare intents`() {
        // (b): intent with only EXTRA_SCENARIO — no EXTRA_ROUTE_ALL extra.
        assert(!LeakConfig.routeAllFor(Scenario.DNS_LEAK, null))
        assert(LeakConfig.routeAllFor(Scenario.CLOUD, null))
        assert(LeakConfig.routeAllFor(Scenario.BASELINE, null))
        assert(LeakConfig.routeAllFor(Scenario.IPV6_LEAK, null))
    }

    @Test
    fun `routeAll extra present wins over scenarios`() {
        assert(!LeakConfig.routeAllFor(Scenario.CLOUD, false))
        assert(LeakConfig.routeAllFor(Scenario.DNS_LEAK, true))
    }

    @Test
    fun `scenario defaults agree with resolved defaults`() {
        for (s in Scenario.entries) {
            assertEquals(
                "defaultRouteAll(s)",
                LeakConfig.defaultRouteAll(s),
                LeakConfig.routeAllFor(s, null),
            )
            assertEquals(
                "defaultDnsServer(s)",
                if (s == Scenario.DNS_LEAK) null else "10.42.0.2",
                LeakConfig.defaultDnsServer(s),
            )
        }
    }
}
