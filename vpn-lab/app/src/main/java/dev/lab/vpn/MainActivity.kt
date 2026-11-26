package dev.lab.vpn

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/**
 * Minimal programmatic control panel (no layout XML): pick a lab scenario,
 * enter the SOCKS5 proxy host/port, and start/stop the tunnel. Status output
 * appears in the log pane.
 */
class MainActivity : Activity() {

    companion object {
        const val DEFAULT_PROXY_HOST = "10.42.0.1"
        const val DEFAULT_PROXY_PORT = 1080
        const val DEFAULT_DNS_SERVER = "10.42.0.2"
        const val VPN_REQUEST_CODE = 4711
        const val NOTIF_REQUEST_CODE = 4712
        // Scenario defaults per Task 9's configs: IPV6_LEAK does NOT route
        // IPv6 into the TUN (the leak check watches v6 traffic escape past
        // the VPN); DNS_LEAK uses narrow lab routes and no pushed DNS.
        val SCENARIO_NARROW_ROUTES = setOf(Scenario.DNS_LEAK)
        const val LOG_LIMIT_CHARS = 8000
    }

    private lateinit var scenarioSpinner: Spinner
    private lateinit var proxyHostField: EditText
    private lateinit var proxyPortField: EditText
    private lateinit var logView: TextView
    private var startButton: Button? = null
    private var pendingConfig: TunnelConfig? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val panel = buildControls()
        setContentView(ScrollView(this).apply { addView(panel) })
        logScenarioSelection()
    }

    private fun buildControls(): LinearLayout {
        val dp = resources.displayMetrics.density
        fun pad(view: View, l: Int, t: Int, r: Int, b: Int) {
            view.setPadding((l * dp).toInt(), (t * dp).toInt(), (r * dp).toInt(), (b * dp).toInt())
        }

        fun label(text: String) = TextView(this).apply { this.text = text; textSize = 14f }

        scenarioSpinner = Spinner(this).apply {
            id = View.generateViewId()
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                Scenario.entries.map { it.name }
            )
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    logScenarioSelection()
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        }

        proxyHostField = EditText(this).apply { setText(DEFAULT_PROXY_HOST); hint = "proxy host"; id = View.generateViewId() }
        proxyPortField = EditText(this).apply { setText(DEFAULT_PROXY_PORT.toString()); hint = "proxy port"; id = View.generateViewId() }

        logView = TextView(this).apply {
            id = View.generateViewId()
            setTextIsSelectable(true)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            text = "idle\n"
        }

        fun button(text: String, action: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { action() }
        }

        startButton = button("START TUNNEL", ::onStartClicked)
        val stopButton = button("STOP TUNNEL", ::onStopClicked)
        val peerProxyButton = button("PEER PROXY (DEVICE B)", ::onPeerProxyClicked)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            pad(this, 16, 16, 16, 16)
            addView(label("Scenario"))
            addView(scenarioSpinner)
            addView(label("SOCKS5 proxy host"))
            addView(proxyHostField)
            addView(label("SOCKS5 proxy port"))
            addView(proxyPortField)
            addView(startButton)
            addView(stopButton)
            addView(peerProxyButton)
            addView(label("Log"))
            addView(logView)
        }
    }

    private fun readConfig(scenario: Scenario): TunnelConfig? {
        val host = proxyHostField.text.toString().trim()
        val port = proxyPortField.text.toString().trim().toIntOrNull()
        if (host.isEmpty() || port == null) {
            toast("proxy host/port are invalid")
            return null
        }
        return TunnelConfig(
            scenario = scenario,
            proxyHost = host,
            proxyPort = port,
            // Narrow lab routes only in the DNS_LEAK scenario (full
            // per-scenario routing behavior arrives in Tasks 7/9). IPv6 is
            // never routed into the TUN by default (includeIpv6=false per
            // Task 9's IPV6_LEAK config).
            routeAll = scenario !in SCENARIO_NARROW_ROUTES,
            includeIpv6 = false,
            dnsServer = if (scenario == Scenario.DNS_LEAK) null else DEFAULT_DNS_SERVER,
        )
    }

    private fun onStartClicked() {
        // POST_NOTIFICATIONS on API 33+; proceed regardless of the outcome —
        // the foreground-service notification shows on the FGS UI anyway.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), NOTIF_REQUEST_CODE
            )
        }
        val scenario = Scenario.entries[scenarioSpinner.selectedItemPosition]
        val config = readConfig(scenario) ?: return
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            pendingConfig = config
            startActivityForResult(prepare as Intent, VPN_REQUEST_CODE)
        } else {
            startWith(config)
        }
    }

    /** Opens the device-B peer proxy screen (PEER scenario's other half). */
    private fun onPeerProxyClicked() {
        startActivity(Intent(this, PeerProxyActivity::class.java))
    }

    private fun onStopClicked() {
        val intent = Intent(this, LabVpnService::class.java).apply { action = LabVpnService.ACTION_STOP }
        startService(intent)
        log("stop requested\n")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIF_REQUEST_CODE) {
            if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                log("notification permission granted\n")
            } else {
                toast("notifications denied; the FGS banner still appears")
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE) {
            val config = pendingConfig
            pendingConfig = null
            if (resultCode == RESULT_OK && config != null) {
                startWith(config)
            } else {
                toast("VPN permission denied")
                log("VPN permission denied\n")
            }
        }
    }

    private fun startWith(config: TunnelConfig) {
        val intent = Intent(this, LabVpnService::class.java).apply {
            action = LabVpnService.ACTION_START
            putExtra(LabVpnService.EXTRA_SCENARIO, config.scenario.name)
            putExtra(LabVpnService.EXTRA_PROXY_HOST, config.proxyHost)
            putExtra(LabVpnService.EXTRA_PROXY_PORT, config.proxyPort)
            putExtra(LabVpnService.EXTRA_ROUTE_ALL, config.routeAll)
            putExtra(LabVpnService.EXTRA_INCLUDE_IPV6, config.includeIpv6)
            putExtra(LabVpnService.EXTRA_DNS_SERVER, config.dnsServer)
        }
        try {
            startForegroundService(intent)
            log("start requested: $config\n")
        } catch (e: Exception) {
            log("start FAILED: ${e.message}\n")
        }
    }

    private fun logScenarioSelection() {
        val scenario = Scenario.entries[scenarioSpinner.selectedItemPosition]
        if (scenario == Scenario.LOCAL) {
            // Tun2socks forwards into the in-app proxy; host/port are fixed.
            proxyHostField.setText(LocalSocksProxy.LOCAL_HOST)
            proxyPortField.setText(LocalSocksProxy.DEFAULT_PORT.toString())
            log("LOCAL: tun2socks -> in-app proxy 127.0.0.1:${LocalSocksProxy.DEFAULT_PORT} (protected outbound)\n")
        } else if (scenario == Scenario.CLOUD) {
            // CLOUD forwards straight to the remote mitmproxy SOCKS5
            // listener on the lab server (10.42.0.1:1080, started with
            // server/run_proxy.sh) — the "untrusted cloud VPN exit".
            proxyHostField.setText(DEFAULT_PROXY_HOST)
            proxyPortField.setText(DEFAULT_PROXY_PORT.toString())
            log("CLOUD: tun2socks -> socks5://$DEFAULT_PROXY_HOST:$DEFAULT_PROXY_PORT (remote mitmproxy)\n")
        } else if (scenario == Scenario.PEER) {
            // The user types DEVICE B's lab IP (10.42.0.x from dnsmasq) and
            // the peer proxy port; this device's tun2socks forwards the
            // victim's traffic there, and B relays it out with its own
            // identity (the server then sees B's IP, not A's).
            proxyPortField.setText(LocalSocksProxy.PEER_PORT.toString())
            log("PEER: enter device B's lab IP above (e.g. 10.42.0.x) + port ${LocalSocksProxy.PEER_PORT}; on B, open PEER PROXY and START it there\n")
        } else {
            // Any other scenario (BASELINE / DNS_LEAK / IPV6_LEAK) targets the
            // default mitm host. Reset unconditionally so a device-B IP typed
            // for a previous PEER run cannot silently persist into a new
            // scenario's tunnel config.
            proxyHostField.setText(DEFAULT_PROXY_HOST)
            proxyPortField.setText(DEFAULT_PROXY_PORT.toString())
        }
        logView.text = logView.text.trimEnd().toString() + "\nscenario: " + scenario + "\n"
    }

    private fun log(message: String) {
        val combined = (logView.text.toString() + message).takeLast(LOG_LIMIT_CHARS)
        logView.text = combined
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
