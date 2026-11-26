package dev.lab.victim

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Minimal programmatic UI (no layout XML): one row per lab scenario. Each
 * button drives the matching TrafficClient call and prints the response body
 * (or error) in the log pane, ready to be compared with the pcap evidence.
 *
 * Under the mitmproxy addon (server/mitm_addon.py) the four manipulation
 * classes are all observable from this UI:
 *   - Header rewriting: `X-VPNLab: rewritten` is added to every request and
 *     response — visible in pcaps/tshark and the mitmdump log, not in the app
 *     log (OkHttp exposes it, but the lab prints bodies only).
 *   - Ad blocking: edit the HTTP base URL to a blocked host (e.g.
 *     http://ads.lab.test:8080) and press "Ads"; the proxy answers locally
 *     with HTTP 403, shown in the log as "FAILED: ... HTTP 403"; the body
 *     carrying VPNLAB-REWRITTEN is in the pcap/mitmdump evidence.
 *   - Body rewriting: "Ads" on http.lab.test:8080 shows the blocked page with
 *     VPNLAB-REWRITTEN (VPNLAB-ADS gone).
 *   - JavaScript injection: every 200 text/html body shown in the log embeds
 *     the //host:port/injected.js tag and the inline VPNLAB-INJECTED marker.
 *   - Redirect: "Redirect" ends on the hijacked partner page (Location
 *     rewritten to partner.lab.test:8080), so the final body is
 *     VPNLAB-PARTNER.
 */
class MainActivity : Activity() {

    companion object {
        // Lab defaults; the fields stay editable for peer/proxy experiments.
        const val DEFAULT_HTTP_BASE_URL = "http://http.lab.test:8080"
        const val DEFAULT_SECURE_BASE_URL = "https://secure.lab.test:8443"
        const val LOG_LIMIT_CHARS = 8000
        const val EXTRA_PIN_SHA256 = "dev.lab.victim.extra.PIN_SHA256"
    }

    private lateinit var httpUrlField: EditText
    private lateinit var secureUrlField: EditText
    private lateinit var pinField: EditText
    private lateinit var logView: TextView
    private val mainScope = CoroutineScope(Dispatchers.Main.immediate)
    private var runningJob: Job? = null

    private fun doRun(label: String, block: suspend () -> String) {
        runningJob?.cancel()
        log("== $label ==")
        runningJob = mainScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { block() }
                log(result)
            } catch (e: Exception) {
                log("FAILED: ${e.javaClass.simpleName}: ${e.message ?: "(no message)"}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dp = resources.displayMetrics.density
        fun pad(view: View, l: Int, t: Int, r: Int, b: Int) {
            view.setPadding((l * dp).toInt(), (t * dp).toInt(), (r * dp).toInt(), (b * dp).toInt())
        }

        httpUrlField = EditText(this).apply { setText(DEFAULT_HTTP_BASE_URL); id = View.generateViewId() }
        secureUrlField = EditText(this).apply { setText(DEFAULT_SECURE_BASE_URL); id = View.generateViewId() }
        pinField = EditText(this).apply {
            hint = "pin-sha256 (SPKI SHA-256, hex or base64)"
            setText(computeSelfPinHint() ?: "")
            id = View.generateViewId()
        }
        logView = TextView(this).apply {
            id = View.generateViewId()
            setTextIsSelectable(true)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            pad(this, 8, 8, 8, 24)
        }

        fun fieldLabel(text: String) = TextView(this).apply { this.text = text; textSize = 14f }
        fun buttonLabel(text: String, action: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { action() }
        }

        val root = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -1)
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    pad(this, 16, 16, 16, 16)
                    addView(fieldLabel("HTTP base URL (cleartext only for *.lab.test)"))
                    addView(httpUrlField)
                    addView(buttonLabel("Baseline (GET /baseline)") {
                        doRun("baseline") { client().fetchBaseline(httpUrlField.text.toString()) }
                    })
                    addView(buttonLabel("Ads (GET /ads)") {
                        doRun("ads") { client().fetchAds(httpUrlField.text.toString()) }
                    })
                    addView(buttonLabel("Redirect (GET /redirect)") {
                        doRun("redirect") { client().followRedirect(httpUrlField.text.toString()) }
                    })
                    addView(View(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(-1, (8 * dp).toInt()) })
                    addView(fieldLabel("HTTPS base URL (user mitmproxy CA for secure.lab.test)"))
                    addView(secureUrlField)
                    addView(buttonLabel("Secure (GET /secure, HTTPS)") {
                        doRun("secure") { client().fetchSecure(secureUrlField.text.toString()) }
                    })
                    addView(fieldLabel("SPKI SHA-256 pin"))
                    addView(pinField)
                    addView(buttonLabel("Pinned secure (fails under MITM)") {
                        doRun("pinned secure") {
                            client().fetchPinnedSecure(secureUrlField.text.toString(), pinField.text.toString())
                        }
                    })
                    addView(View(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(-1, (8 * dp).toInt()) })
                    addView(buttonLabel("DNS resolve") {
                        doRun("dns resolve") {
                            val addrs = client().resolveHost(hostOf(secureUrlField.text.toString()))
                            addrs.joinToString("\n") { "$it" }
                        }
                    })
                    addView(View(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(-1, (12 * dp).toInt()) })
                    addView(logView)
                }
            )
        }

        setContentView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(root) })
        log("victim-lab ready. Run scenarios on the VPN-LAB AP (10.42.0.0/24).")
    }

    private fun client() = TrafficClient()

    private var logBuffer = StringBuilder()

    private fun log(text: String) {
        logBuffer.append(text).append('\n')
        if (logBuffer.length > LOG_LIMIT_CHARS) logBuffer.delete(0, logBuffer.length - LOG_LIMIT_CHARS)
        logView.text = logBuffer.toString()
    }

    /** SPKI SHA-256 pin must be supplied by the operator, derived from
     * server/certs/secure.lab.test.pem:
     *   openssl x509 -in secure.lab.test.pem -pubkey -noout \
     *     | openssl pkey -pubin -outform DER \
     *     | openssl dgst -sha256
     * Prefill the field when the VPN app launches this activity with the pin. */
    private fun computeSelfPinHint(): String? =
        intent?.getStringExtra(EXTRA_PIN_SHA256)

    private fun hostOf(url: String): String =
        url.trim().removePrefix("https://").removePrefix("http://")
            .substringBefore('/').substringBefore(':')
}
