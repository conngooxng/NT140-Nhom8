package dev.lab.victim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * Instrumentation tests against the real lab server (server/labserver.py).
 * Run them with the phone attached to the VPN-LAB AP:
 *
 *   adb shell am instrument -w \
 *     -e lab.http.baseUrl http://http.lab.test:8080 \
 *     -e lab.https.baseUrl https://secure.lab.test:8443 \
 *     dev.lab.victim.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Base URLs can be overridden per-run with the instrumentation arguments
 * `lab.http.baseUrl` and `lab.https.baseUrl` (defaults fall back to the
 * names served by dnsmasq on the lab AP).
 */
class TrafficClientTest {

    private fun httpBase(): String =
        androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("lab.http.baseUrl", "http://http.lab.test:8080")

    private fun httpsBase(): String =
        androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("lab.https.baseUrl", "https://secure.lab.test:8443")

    private val client = TrafficClient()

    @Test
    fun baselineReturnsMarker() {
        val body = runBlocking { client.fetchBaseline(httpBase()) }
        assertTrue("expected VPNLAB-BASELINE in body", body.contains("VPNLAB-BASELINE"))
    }

    @Test
    fun redirectLandsOnLandingPage() {
        // followRedirect() follows redirects, so the final body is the landing
        // page (marker VPNLAB-BASELINE in labserver.py's /landing alias), not
        // the 302 itself.
        val body = runBlocking { client.followRedirect(httpBase()) }
        assertTrue(body.contains("VPNLAB-BASELINE"))
    }

    @Test
    fun secureReturnsMarkerOverTls() {
        val body = runBlocking { client.fetchSecure(httpsBase()) }
        assertTrue("expected VPNLAB-SECURE in body", body.contains("VPNLAB-SECURE"))
    }

    @Test
    fun resolveHostReturnsAddresses() {
        val addrs = runBlocking { client.resolveHost("secure.lab.test") }
        assertTrue("expected at least one address", addrs.isNotEmpty())
        assertTrue(addrs.all { it is java.net.InetAddress })
    }

    @Test
    fun pinnedSecureRejectsMalformedPin() {
        try {
            runBlocking { client.fetchPinnedSecure(httpsBase(), "not-a-pin") }
            fail("expected IllegalArgumentException for malformed pin")
        } catch (expected: IllegalArgumentException) {
            assertEquals(true, expected.message!!.contains("pinSha256"))
        }
    }
}
