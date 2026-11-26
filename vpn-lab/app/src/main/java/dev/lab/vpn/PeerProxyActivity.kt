package dev.lab.vpn

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.Socket

/**
 * Device B's half of the PEER scenario: runs a plain SOCKS5 listener on
 * 0.0.0.0:1080 that device A's VPN (PEER mode) forwards the victim's traffic
 * into. Device B is NOT running a VPN — its relayed connections leave via its
 * normal network (DirectDialer, no protect()). The upstream server therefore
 * sees device B's IP, not device A's.
 *
 * SECURITY/SCOPE: this binds to ALL interfaces on a phone. That is acceptable
 * ONLY inside the lab AP network (10.42.0.0/24). Never run the peer proxy on
 * any other network.
 *
 * Keep-alive: while the proxy runs the screen is held on
 * (FLAG_KEEP_SCREEN_ON), and pressing Back (or rotating) destroys this
 * activity and STOPS the peer proxy via onDestroy — device A's tunnel would
 * keep a dead exit. Stay on this screen while the tunnel is up.
 */
class PeerProxyActivity : Activity() {

    companion object {
        private const val LOG_LIMIT_CHARS = 4000
    }

    private var proxy: LocalSocksProxy? = null
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        fun pad(view: View, l: Int, t: Int, r: Int, b: Int) {
            view.setPadding((l * dp).toInt(), (t * dp).toInt(), (r * dp).toInt(), (b * dp).toInt())
        }
        fun label(text: String) = TextView(this).apply { this.text = text; textSize = 14f }

        statusView = TextView(this).apply {
            setTextIsSelectable(true)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            text = "idle — use ONLY in the lab AP network (10.42.0.0/24)\n"
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            pad(this, 16, 16, 16, 16)
            addView(label("PEER proxy (this is device B)"))
            addView(
                label(
                    "Binds 0.0.0.0:${LocalSocksProxy.PEER_PORT}. On device A, run vpn-lab in " +
                        "PEER mode with this device's lab IP (10.42.0.x) as the proxy host. " +
                        "Relayed traffic exits via B's normal network (no VPN, no protect())."
                )
            )
            addView(
                label("NOTE: pressing Back (or closing this screen) STOPS the peer proxy; " +
                    "keep this screen open (and on) while device A's tunnel is up.")
            )
            addView(
                Button(this@PeerProxyActivity).apply {
                    text = "START PEER PROXY"
                    setOnClickListener { startPeerProxy() }
                }
            )
            addView(
                Button(this@PeerProxyActivity).apply {
                    text = "STOP PEER PROXY"
                    setOnClickListener { stopPeerProxy() }
                }
            )
            addView(label("Status"))
            addView(statusView)
        }
        setContentView(ScrollView(this).apply { addView(panel) })
    }

    private fun startPeerProxy() {
        stopPeerProxy()
        val p = LocalSocksProxy(
            port = LocalSocksProxy.PEER_PORT,
            protector = object : SocketProtector {
                override fun protect(socket: Socket): Boolean = true // unused by DirectDialer
            },
            dialer = LocalSocksProxy.DirectDialer(),
            bindHost = LocalSocksProxy.PEER_BIND_HOST,
        )
        val thread = Thread({
            try {
                p.start()
                runOnUiThread { log("peer proxy listening on 0.0.0.0:${LocalSocksProxy.PEER_PORT}\n") }
            } catch (e: Exception) {
                runOnUiThread {
                    log("start FAILED: ${e.message}\n")
                    Toast.makeText(this, "bind failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }, "peer-proxy-start")
        thread.start()
        proxy = p
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        log("starting peer proxy on 0.0.0.0:${LocalSocksProxy.PEER_PORT}...\n")
    }

    private fun stopPeerProxy() {
        proxy?.stop()
        proxy = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        log("peer proxy stopped\n")
    }

    override fun onDestroy() {
        proxy?.stop()
        proxy = null
        super.onDestroy()
    }

    private fun log(message: String) {
        runOnUiThread {
            statusView.text = (statusView.text.toString() + message).takeLast(LOG_LIMIT_CHARS)
        }
    }
}
